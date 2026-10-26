package me.rerere.rikkahub.data.sync.auto

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
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
 * 三个触发点：
 *
 * - 启动后 20 秒：先把本机改动推上去
 * - 退到后台：这时才允许整库替换，不打断任何操作
 * - 每 30 分钟：兜底（WorkManager 最短周期就是 15 分钟）
 */
object AutoSyncScheduler {
    private const val PERIODIC_WORK = "auto_sync_periodic"
    private const val LAUNCH_WORK = "auto_sync_launch"
    private const val BACKGROUND_WORK = "auto_sync_background"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build(),
        )

        workManager.enqueueUniqueWork(
            LAUNCH_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoSyncWorker>()
                .setConstraints(constraints())
                // 稍微等一下，让 Application 里那堆初始化先跑完
                .setInitialDelay(20, TimeUnit.SECONDS)
                .build(),
        )

        // 用户在用的时侯不能动数据库（暂存完就退进程会把人吓一跳），
        // 所以真正「拉取 + 应用」安排在切到后台之后。
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    workManager.enqueueUniqueWork(
                        BACKGROUND_WORK,
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<AutoSyncWorker>()
                            .setConstraints(constraints())
                            .setInitialDelay(15, TimeUnit.SECONDS)
                            .build(),
                    )
                }
            }
        )
    }
}
