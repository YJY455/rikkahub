package me.rerere.rikkahub.data.sync.auto

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.util.cio.readChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.webdav.WebDavClient
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val TAG = "AutoSync"

sealed interface SyncOutcome {
    /** 没配 WebDAV，或者地址没填 */
    data object Disabled : SyncOutcome

    /** 服务端没有这台设备缺的东西，不用下载 */
    data object UpToDate : SyncOutcome

    /** 服务端有这台设备缺的数据，但当前不方便应用（前台 / 正在生成），等下一次 */
    data object PendingDownload : SyncOutcome

    /** 别人的数据已经暂存，下次启动生效 */
    data class Applied(val name: String) : SyncOutcome

    data class Failed(val message: String) : SyncOutcome
}

/**
 * 复用 App 自带的 WebDAV 备份能力做双向同步：
 *
 * 1. 把本机整包 PUT 上去，服务端负责与权威库做三方合并；
 * 2. 服务端回的 JSON 里 `upstream_changes == 0` 表示「你已经是最新的」，直接结束；
 * 3. 否则把合并结果下载下来交给 [BackupManager.stageRestore] 暂存，下次启动生效。
 */
class AutoSync(
    private val context: Context,
    private val backupManager: BackupManager,
    private val webDavSync: WebDavSync,
    private val settingsStore: SettingsStore,
    private val httpClient: HttpClient,
) {
    suspend fun sync(allowPull: Boolean = false): SyncOutcome = withContext(Dispatchers.IO) {
        val config = settingsStore.awaitLoaded().webDavConfig
        if (config.url.isBlank() || config.username.isBlank()) {
            return@withContext SyncOutcome.Disabled
        }

        val outcome = try {
            doSync(config, allowPull)
        } catch (e: Exception) {
            Log.e(TAG, "sync failed", e)
            SyncOutcome.Failed(e.message ?: e::class.simpleName ?: "未知错误")
        }

        // 把结果写回服务端，出问题时不用连电脑抓 logcat
        runCatching { postLog(config, outcome) }
            .onFailure { Log.w(TAG, "postLog failed", it) }

        outcome
    }

    private suspend fun doSync(config: WebDavConfig, allowPull: Boolean): SyncOutcome {
        val archive = try {
            backupManager.createBackup(includeDatabase = true, includeFiles = true)
        } catch (e: Exception) {
            Log.e(TAG, "createBackup failed", e)
            return SyncOutcome.Failed("打包失败: ${e.message}")
        }

        try {
            val name = "backup_${
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            }_${UUID.randomUUID().toString().take(8)}.zip"

            val summary = upload(config, name, archive)
            val status = (summary["status"] as? JsonPrimitive)?.contentOrNull
            Log.i(TAG, "upload done, status=$status")

            if (status == null || status == "up_to_date" || status == "initialized") {
                // 服务端没有这台设备缺的数据；null 说明对面不是 rikka-sync（比如普通网盘），
                // 这种情况下也不主动下载，避免把旧备份盖回本地。
                return SyncOutcome.UpToDate
            }

            // 下载完会立刻暂存、下次启动整库替换。如果此刻用户还在用，先不拉，
            // 否则这期间新写的对话会在重启时被覆盖掉。
            if (!allowPull) {
                return SyncOutcome.PendingDownload
            }

            val latest = webDavSync.listBackupFiles(config).firstOrNull()
                ?: return SyncOutcome.UpToDate

            val target = File.createTempFile("auto-sync-", ".zip", context.cacheDir)
            try {
                WebDavClient(config, httpClient).downloadToFile(latest.displayName, target).getOrThrow()
                // 明确要求连数据库一起恢复，不看用户在「备份内容」里勾了什么
                backupManager.stageRestore(target, includeDatabase = true, includeFiles = true)
            } finally {
                target.delete()
            }
            return SyncOutcome.Applied(latest.displayName)
        } finally {
            archive.delete()
        }
    }

    private suspend fun upload(
        config: WebDavConfig,
        name: String,
        file: File,
    ): Map<String, JsonElement> {
        val response = httpClient.request(buildUrl(config, name)) {
            method = HttpMethod.Put
            basicAuth(config.username, config.password)
            headers {
                append("Content-Type", "application/zip")
                append("Content-Length", file.length().toString())
            }
            setBody(file.readChannel())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IOException("上传失败 ${response.status.value}: ${text.take(200)}")
        }
        return runCatching { JsonInstant.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: emptyMap()
    }

    private suspend fun postLog(config: WebDavConfig, outcome: SyncOutcome) {
        val text = when (outcome) {
            is SyncOutcome.Applied -> "applied ${outcome.name}"
            is SyncOutcome.Failed -> "failed ${outcome.message}"
            SyncOutcome.UpToDate -> "up_to_date"
            SyncOutcome.PendingDownload -> "pending_download"
            SyncOutcome.Disabled -> "disabled"
        }
        httpClient.request(buildUrl(config, "__log")) {
            method = HttpMethod.Post
            basicAuth(config.username, config.password)
            setBody(TextContent(text, ContentType.Text.Plain))
        }
    }

    private fun buildUrl(config: WebDavConfig, segment: String): String {
        val base = config.url.trimEnd('/')
        val segments = listOfNotNull(
            config.path.takeIf { it.isNotBlank() }?.trim('/'),
            segment.trim('/'),
        ).filter { it.isNotEmpty() }
        return if (segments.isEmpty()) base else "$base/${segments.joinToString("/")}"
    }
}
