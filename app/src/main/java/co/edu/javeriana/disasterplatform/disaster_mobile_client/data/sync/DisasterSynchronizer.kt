package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync

import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterInfoDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterReportDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDao
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.SyncStatus
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote.DisasterApi
import com.google.gson.JsonParseException
import java.io.IOException
import javax.inject.Inject
import retrofit2.HttpException

class DisasterSynchronizer @Inject constructor(
    private val dao: DisasterDao,
    private val api: DisasterApi,
) {
    /** false conserva los pendientes para un reintento con la misma clave. */
    suspend fun syncPending(): Boolean {
        while (true) {
            val pending = dao.getPending()
            if (pending.isEmpty()) return true
            for (disaster in pending) {
                try {
                    val report = disaster.toReport()
                    val response = api.createDisaster(disaster.clientRequestId, report)
                    if (!response.isSuccessful) throw HttpException(response)
                    val saved = response.body()
                    if (response.code() !in listOf(200, 201) || saved == null)
                        throw IOException("El servidor no confirmó el desastre.")
                    if (!saved.confirms(report)) throw IOException("La respuesta no confirma el reporte enviado.")
                    dao.markSynced(disaster.clientRequestId, saved.id)
                } catch (error: HttpException) {
                    val code = error.code()
                    if (code == 408 || code == 429 || code >= 500) return false
                    val status = if (code == 409) SyncStatus.CONFLICT else SyncStatus.ERROR
                    val message = when (code) {
                        400 -> "El servidor rechazó los datos del reporte (400)."
                        409 -> "La clave ya está registrada con otros datos (409)."
                        else -> "No se pudo sincronizar el reporte (HTTP $code)."
                    }
                    dao.markError(disaster.clientRequestId, status, message)
                } catch (_: IOException) {
                    return false
                } catch (_: JsonParseException) {
                    return false
                }
            }
        }
    }

    private fun DisasterInfoDto.confirms(report: DisasterReportDto): Boolean =
        id > 0 && nombre == report.nombre && ubicacion == report.ubicacion &&
            severidad == report.severidad && descripcion == report.descripcion &&
            latitud == report.latitud && longitud == report.longitud &&
            (radio == null || radio == report.radio)
}
