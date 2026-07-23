package com.smartreimburse.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val manager = SyncManager(applicationContext)
        if (!manager.isConfigured || !manager.isPaired) return Result.success()
        return runCatching { manager.syncNow() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}
