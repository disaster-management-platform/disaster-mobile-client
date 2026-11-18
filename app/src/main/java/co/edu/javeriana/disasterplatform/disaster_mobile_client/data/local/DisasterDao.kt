package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local

import com.google.gson.Gson
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

    @Query("SELECT * FROM disasters WHERE syncStatus IN ('PENDING','ACCEPTED') AND rowid > COALESCE((SELECT rowid FROM disasters WHERE clientRequestId = :after), 0) ORDER BY rowid LIMIT 50")
    abstract suspend fun getPending(after: String? = null): List<DisasterEntity>

    @Query("SELECT * FROM disasters WHERE serverId = :serverId LIMIT 1")
    abstract suspend fun findByServerId(serverId: Int): DisasterEntity?

    @Query("SELECT * FROM disasters WHERE clientRequestId = :requestId")
    abstract suspend fun findByRequestId(requestId: String): DisasterEntity?

    @Upsert
    protected abstract suspend fun upsert(disaster: DisasterEntity)

    @Query("DELETE FROM disasters WHERE serverId = :serverId AND clientRequestId != :requestId AND syncStatus = 'SYNCED'")
    protected abstract suspend fun removeCachedDuplicate(serverId: Int, requestId: String)

    @Transaction
    open suspend fun markSynced(requestId: String, serverId: Int, confirmation: DisasterInfoDto? = null) {
        val pending = findByRequestId(requestId) ?: return
        if (pending.syncStatus !in listOf(SyncStatus.PENDING, SyncStatus.ACCEPTED)) return
        val cached = findByServerId(serverId)
        if (cached != null && cached.clientRequestId != requestId && cached.syncStatus != SyncStatus.SYNCED) {
            markError(requestId, SyncStatus.CONFLICT, "La identidad remota pertenece a otro registro local que requiere revisión.")
            return
        }
        val receipt = confirmation?.toEntity(requestId) ?: pending.copy(serverId = serverId, syncStatus = SyncStatus.SYNCED)
        val latest = if (cached != null && (cached.version ?: 0) >= (receipt.version ?: 0)) cached else receipt
        removeCachedDuplicate(serverId, requestId)
        upsert(latest.copy(clientRequestId = requestId, syncStatus = SyncStatus.SYNCED, syncError = null,
            serverId = serverId, creationPayload = pending.creationPayload ?: Gson().toJson(pending.toReport())))
    }

    @Query("UPDATE disasters SET syncStatus = :status, syncError = :message WHERE clientRequestId = :requestId AND syncStatus IN ('PENDING','ACCEPTED')")
    abstract suspend fun markError(requestId: String, status: String, message: String)

    @Transaction
    open suspend fun cacheRemote(disasters: List<DisasterInfoDto>) {
        for (disaster in disasters) {
            val existing = findByServerId(disaster.id)
            if (existing != null && existing.syncStatus != SyncStatus.SYNCED) continue
            if (existing?.version != null && (disaster.version ?: 0) <= existing.version) continue
            upsert(disaster.toEntity(existing?.clientRequestId ?: "server:${disaster.id}")
                .copy(creationPayload = existing?.creationPayload))
        }
    }

    @Query("SELECT cursor FROM disaster_sync_state WHERE name = 'disasters'")
    abstract suspend fun getSyncCursor(): String?

    @Upsert
    protected abstract suspend fun saveSyncState(state: DisasterSyncState)

    @Transaction
    open suspend fun applySyncPage(expectedCursor: String?, nextCursor: String, items: List<DisasterInfoDto>): Boolean {
        if (getSyncCursor() != expectedCursor) return false
        cacheRemote(items)
        saveSyncState(DisasterSyncState(cursor = nextCursor))
        return true
    }

    @Query("SELECT * FROM disaster_types ORDER BY id")
    abstract fun observeTypes(): Flow<List<DisasterTypeEntity>>

    @Query("SELECT * FROM disaster_types WHERE id = :id")
    abstract suspend fun findType(id: Int): DisasterTypeEntity?

    @Upsert
    abstract suspend fun cacheTypes(types: List<DisasterTypeEntity>)

}
