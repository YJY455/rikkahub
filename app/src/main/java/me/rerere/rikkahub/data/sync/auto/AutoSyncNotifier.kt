package me.rerere.rikkahub.data.sync.auto

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object AutoSyncNotifier {
    private const val CHANNEL_ID = "auto_sync"
    private const val NOTIFICATION_ID = 0x5e9
    private const val TAG = "AutoSyncNotifier"

    fun notifyApplied(context: Context, archiveName: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannelCompat(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("自动同步")
                .setDescription("其他设备的数据已合并，重启后生效")
                .build()
        )

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("RikkaHub 已同步")
            .setContentText("其他设备的数据已合并，重启应用后生效")
            .setStyle(NotificationCompat.BigTextStyle().bigText("合并结果：$archiveName"))
            .setAutoCancel(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "notify failed", it) }
    }
}
