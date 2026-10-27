package me.rerere.rikkahub.data.sync.auto

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object AutoSyncNotifier {
    private const val CHANNEL_ID = "auto_sync"
    private const val APPLIED_ID = 0x5e9
    private const val FAILED_ID = 0x5ea
    private const val TAG = "AutoSyncNotifier"

    private fun ensureChannel(context: Context): NotificationManagerCompat {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("自动同步")
                .setDescription("多设备同步的状态提示")
                .build()
        )
        return manager
    }

    private fun launchIntent(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    fun notifyApplied(context: Context, archiveName: String) {
        val manager = ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("RikkaHub 已同步")
            .setContentText("其他设备的数据已合并，重启应用后生效")
            .setStyle(NotificationCompat.BigTextStyle().bigText("合并结果：$archiveName"))
            .setAutoCancel(true)
            .apply { launchIntent(context)?.let { setContentIntent(it) } }
            .build()

        runCatching { manager.notify(APPLIED_ID, notification) }
            .onFailure { Log.w(TAG, "notify failed", it) }
    }

    fun notifyFailed(context: Context, message: String) {
        val manager = ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("RikkaHub 自动同步失败")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .apply { launchIntent(context)?.let { setContentIntent(it) } }
            .build()

        runCatching { manager.notify(FAILED_ID, notification) }
            .onFailure { Log.w(TAG, "notify failed", it) }
    }
}
