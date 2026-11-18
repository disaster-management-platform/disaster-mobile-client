package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.repository.DisasterRepository
import java.io.IOException
import com.google.gson.JsonParseException
import retrofit2.HttpException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncDisastersWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val synchronizer: DisasterSynchronizer,
    private val repository: DisasterRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val uploaded = synchronizer.syncPending()
        return try {
            repository.refreshDisastersAndTypes()
            if (uploaded) Result.success() else Result.retry()
        } catch (error: HttpException) {
            if (uploaded && error.code() in listOf(401, 403, 404)) Result.success() else Result.retry()
        } catch (_: IOException) { Result.retry() }
        catch (_: JsonParseException) { Result.retry() }
        catch (_: IllegalArgumentException) { Result.retry() }
    }
}
