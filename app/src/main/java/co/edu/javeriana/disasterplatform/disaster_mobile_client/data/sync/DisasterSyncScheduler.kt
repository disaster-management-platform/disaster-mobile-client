package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DisasterSyncScheduler @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val scheduling = Mutex()

    suspend fun enqueue(): Unit = scheduling.withLock {
        val manager = WorkManager.getInstance(context)
        if (manager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return@withLock
        val request = OneTimeWorkRequestBuilder<SyncDisastersWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request,
        ).await()
    }

    companion object {
        const val WORK_NAME = "sync-disasters"
    }
}
