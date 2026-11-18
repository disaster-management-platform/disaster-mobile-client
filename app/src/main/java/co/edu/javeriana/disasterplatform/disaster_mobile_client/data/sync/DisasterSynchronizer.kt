package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync

import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterBatchRequest
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterOperation
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterInfoDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterReportDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterEntity
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDao
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.SyncStatus
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote.DisasterApi
import com.google.gson.JsonParseException
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.validated
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.normalizedUtc
import java.io.IOException
import javax.inject.Inject

class DisasterSynchronizer @Inject constructor(
    private val dao: DisasterDao,
    private val api: DisasterApi,
) {
    /** false conserva los pendientes para un reintento con la misma clave. */
    suspend fun syncPending(): Boolean {
        var after: String? = null
        var retry = false
        val batches = ArrayDeque<List<DisasterEntity>>()
        while (true) {
            if (batches.isEmpty()) {
                val pending = dao.getPending(after)
                if (pending.isEmpty()) return !retry
                after = pending.last().clientRequestId
                batches.addLast(pending)
            }
            val pending = batches.removeFirst()
            val response = try {
                api.createBatch(DisasterBatchRequest(
                    pending.map { DisasterOperation(it.clientRequestId, it.toReport()) }))
            } catch (_: IOException) { return false }
            catch (_: JsonParseException) { return false }
            if (response.code() == 413 && pending.size > 1) {
                val half = pending.size / 2
                batches.addFirst(pending.drop(half))
                batches.addFirst(pending.take(half))
                continue
            }
            if (response.code() in 400..499 && response.code() !in listOf(408, 425, 429)) {
                val message = when (response.code()) {
                    401, 403 -> "Autorización rechazada; revise el acceso antes de reintentar."
                    413 -> "El reporte supera el tamaño admitido; requiere revisión."
                    400, 422 -> "El servidor rechazó el lote; revise los datos y la configuración."
                    else -> "Configuración o contrato Batch rechazado (HTTP ${response.code()}); requiere revisión."
                }
                pending.forEach { dao.markError(it.clientRequestId, SyncStatus.ERROR, message) }
                continue
            }
            if (response.code() != 200) return false
            val results = response.body()?.results
            if (results == null) { retry = true; continue }
            val correlated = results.filterNotNull().groupBy { it.clientRequestId }
            for (disaster in pending) {
                val result = correlated[disaster.clientRequestId]?.singleOrNull()
                when (result?.status) {
                    "Created", "AlreadyProcessed" -> {
                        val saved = try { result.disaster?.validated() }
                        catch (_: IllegalArgumentException) { null }
                        if (saved == null) { retry = true; continue }
                        if (!saved.confirms(disaster.toReport())) {
                            dao.markError(disaster.clientRequestId, SyncStatus.CONFLICT,
                                "El servidor confirmó un contenido diferente. Se conserva el reporte para revisión.")
                        } else dao.markSynced(disaster.clientRequestId, saved.id, saved)
                    }
                    "ValidationError" -> {
                        val messages = result.errors?.values
                        if (messages.isNullOrEmpty() || messages.any { it.isNullOrEmpty() || it.any { text -> text.isNullOrBlank() } }) {
                            retry = true; continue
                        }
                        dao.markError(disaster.clientRequestId, SyncStatus.ERROR,
                            "El servidor rechazó los datos del reporte: " + messages.filterNotNull().flatten().joinToString(" "))
                    }
                    "Conflict" -> dao.markError(disaster.clientRequestId, SyncStatus.CONFLICT,
                        if (disaster.radio == null) "La clave ya está registrada con otros datos. El radio histórico es desconocido; requiere revisión."
                        else "La clave ya está registrada con otros datos; requiere revisión.")
                    else -> retry = true
                }
            }
        }
    }

    private fun DisasterInfoDto.confirms(report: DisasterReportDto): Boolean =
        id > 0 && nombre == report.nombre && ubicacion == report.ubicacion &&
            severidad == report.severidad && descripcion == report.descripcion &&
            latitud == report.latitud && longitud == report.longitud &&
            radio == report.radio && disasterTypeId == report.disasterTypeId && attentionLevel == report.attentionLevel &&
            normalizedUtc(startedAt) == normalizedUtc(report.startedAt)
}
