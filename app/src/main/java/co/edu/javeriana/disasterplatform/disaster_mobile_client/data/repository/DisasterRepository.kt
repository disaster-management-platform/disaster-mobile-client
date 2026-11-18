package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.repository

import android.util.Log
import com.google.gson.Gson
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterTypeEntity
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterReportDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDao
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterEntity
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote.DisasterApi
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync.DisasterSyncScheduler
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.validated
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class DisasterRepository @Inject constructor(
    private val api: DisasterApi,
    private val dao: DisasterDao,
    private val syncScheduler: DisasterSyncScheduler,
) {
    fun observeDisasters() = dao.observeAll()
    fun observeTypes() = dao.observeTypes()

    suspend fun refreshTypes() {
        val types = api.getDisasterTypes()
        require(types.map { it.id }.distinct().size == types.size) { "Catálogo con identificadores duplicados." }
        val valid = types.map {
            require(it.id > 0 && !it.name.isNullOrBlank() && it.name.length <= 120 && it.isActive != null) { "Tipo de desastre inválido." }
            DisasterTypeEntity(it.id, it.name, it.isActive)
        }
        dao.cacheTypes(valid)
    }

    suspend fun refreshDisasters() {
        val remote = api.getDisasters().map { it.validated(requireVersion = true) }
        require(remote.map { it.id }.distinct().size == remote.size) { "Respuesta con identificadores duplicados." }
        dao.cacheRemote(remote)
    }

    suspend fun createDisaster(report: DisasterReportDto): Result<DisasterEntity> = try {
        val valid = report.validated()
        require(valid.disasterTypeId == null || dao.findType(valid.disasterTypeId)?.isActive == true) { "El tipo no está disponible en el catálogo local." }
        val disaster = DisasterEntity(
            clientRequestId = UUID.randomUUID().toString(),
            nombre = valid.nombre, ubicacion = valid.ubicacion, severidad = valid.severidad,
            descripcion = valid.descripcion, latitud = valid.latitud, longitud = valid.longitud, radio = valid.radio,
            disasterTypeId = valid.disasterTypeId, startedAt = valid.startedAt, attentionLevel = valid.attentionLevel,
            creationPayload = Gson().toJson(valid),
        )
        dao.insert(disaster)
        try {
            syncScheduler.enqueue()
        } catch (error: IllegalStateException) {
            // El reporte ya está guardado. Al abrir la app se vuelve a programar.
            Log.w("DisasterRepository", "No se pudo programar la sincronización", error)
        }
        Result.success(disaster)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
