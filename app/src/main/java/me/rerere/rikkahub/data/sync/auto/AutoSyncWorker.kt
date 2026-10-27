package me.rerere.rikkahub.data.sync.auto

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.system.exitProcess

class AutoSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val autoSync: AutoSync by inject()
    private val chatService: ChatService by inject()

    override suspend fun doWork(): Result {
        // 正在生成回复时不打断；在用户眼前时也不做「整库替换」这种动作。
        // 查不到状态时宁可等下一次，也不要冒险把用户正在看的东西换掉。
        val generating = withTimeoutOrNull(2_000) {
            chatService.getConversationJobs().first().isNotEmpty()
        } ?: true
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        val allowPull = !generating && !foreground

        val outcome = autoSync.sync(allowPull = allowPull)
        Log.i(TAG, "sync outcome: $outcome (allowPull=$allowPull)")

        when (outcome) {
            is SyncOutcome.Applied -> {
                // 数据已经暂存，Application.onCreate 会在下次启动时安装它。
                // 先把「为什么关了」告诉用户，再退进程，等于立刻生效。
                AutoSyncNotifier.notifyApplied(applicationContext, outcome.name)
                exitProcess(0)
            }

            is SyncOutcome.Failed -> {
                // 之前失败是静默的，出问题时完全看不出在干什么
                AutoSyncNotifier.notifyFailed(applicationContext, outcome.message)
            }

            else -> Unit
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "AutoSyncWorker"
    }
}
