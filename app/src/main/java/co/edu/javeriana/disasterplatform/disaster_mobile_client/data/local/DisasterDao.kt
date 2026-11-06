package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterInfoDto
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DisasterDao {
    @Insert
    abstract suspend fun insert(disaster: DisasterEntity)

    @Query("SELECT * FROM disasters ORDER BY rowid DESC")
    abstract fun observeAll(): Flow<List<DisasterEntity>>

    @Query("SELECT * FROM disasters WHERE syncStatus = 'PENDING' ORDER BY rowid LIMIT 50")
    abstract suspend fun getPending(): List<DisasterEntity>

    @Query("SELECT * FROM disasters WHERE serverId = :serverId LIMIT 1")
    abstract suspend fun findByServerId(serverId: Int): DisasterEntity?

    @Query("SELECT * FROM disasters WHERE clientRequestId = :requestId")
    abstract suspend fun findByRequestId(requestId: String): DisasterEntity?

    @Upsert
    protected abstract suspend fun upsert(disaster: DisasterEntity)

    @Query("DELETE FROM disasters WHERE serverId = :serverId AND clientRequestId != :requestId AND syncStatus = 'SYNCED'")
    protected abstract suspend fun removeCachedDuplicate(serverId: Int, requestId: String)

    @Query("UPDATE disasters SET syncStatus = 'SYNCED', serverId = :serverId, syncError = NULL WHERE clientRequestId = :requestId AND syncStatus = 'PENDING'")
    protected abstract suspend fun confirm(requestId: String, serverId: Int)

    @Transaction
    open suspend fun markSynced(requestId: String, serverId: Int) {
        // Un GET puede haber descargado la fila mientras terminaba el POST.
        removeCachedDuplicate(serverId, requestId)
        confirm(requestId, serverId)
    }

    @Query("UPDATE disasters SET syncStatus = :status, syncError = :message WHERE clientRequestId = :requestId AND syncStatus = 'PENDING'")
    abstract suspend fun markError(requestId: String, status: String, message: String)

    @Transaction
    open suspend fun cacheRemote(disasters: List<DisasterInfoDto>) {
        for (disaster in disasters) {
            val existing = findByServerId(disaster.id)
            upsert(disaster.toEntity(existing?.clientRequestId ?: "server:${disaster.id}"))
        }
    }
}
