package me.rerere.rikkahub.data.sync.auto

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 两个触发点：启动后跑一次（尽快把手上的改动推上去），之后每 30 分钟一次兜底。
 * WorkManager 最短周期就是 15 分钟，30 分钟是为了别太费电。
 */
object AutoSyncScheduler {
    private const val PERIODIC_WORK = "auto_sync_periodic"
    private const val ONE_SHOT_WORK = "auto_sync_launch"

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build(),
        )

        workManager.enqueueUniqueWork(
            ONE_SHOT_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoSyncWorker>()
                .setConstraints(constraints)
                // 稍微等一下，让 Application 里那堆初始化先跑完
                .setInitialDelay(20, TimeUnit.SECONDS)
                .build(),
        )
    }
}
