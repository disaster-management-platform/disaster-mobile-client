package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncDisastersWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val synchronizer: DisasterSynchronizer,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        if (synchronizer.syncPending()) Result.success() else Result.retry()
}
