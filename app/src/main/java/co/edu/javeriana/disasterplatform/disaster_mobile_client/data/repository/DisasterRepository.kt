package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.repository

import android.util.Log
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

    suspend fun refreshDisasters() {
        dao.cacheRemote(api.getDisasters())
    }

    suspend fun createDisaster(report: DisasterReportDto): Result<DisasterEntity> = try {
        val valid = report.validated()
        val disaster = DisasterEntity(
            clientRequestId = UUID.randomUUID().toString(),
            nombre = valid.nombre,
            ubicacion = valid.ubicacion,
            severidad = valid.severidad,
            descripcion = valid.descripcion,
            latitud = valid.latitud,
            longitud = valid.longitud,
            radio = valid.radio,
            desastreAsociado = valid.desastreAsociado,
            fechaHora = valid.fechaHora,
            evaluador = valid.evaluador,
            organizacion = valid.organizacion,
            tipoActivo = valid.tipoActivo,
            nivelDano = valid.nivelDano,
            evidencias = valid.evidencias,
            estadoEvaluacion = valid.estadoEvaluacion,
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
