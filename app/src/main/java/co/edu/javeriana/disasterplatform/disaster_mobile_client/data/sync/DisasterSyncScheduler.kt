package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DisasterSyncScheduler @Inject constructor(@param:ApplicationContext private val context: Context) {
    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<SyncDisastersWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Conserva el trabajo activo y agrega otro recorrido para los reportes nuevos.
        // KEEP podría perder un registro guardado justo cuando termina el worker.
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request,
        )
    }

    companion object {
        const val WORK_NAME = "sync-disasters"
    }
}
