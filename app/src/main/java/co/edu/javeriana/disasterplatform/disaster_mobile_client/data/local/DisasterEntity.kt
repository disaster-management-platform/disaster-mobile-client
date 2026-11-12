package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterInfoDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterReportDto

object SyncStatus {
    const val PENDING = "PENDING"
    const val SYNCED = "SYNCED"
    const val ERROR = "ERROR"
    const val CONFLICT = "CONFLICT"
}

@Entity(
    tableName = "disasters",
    indices = [Index(value = ["serverId"], unique = true), Index(value = ["syncStatus"])],
)
data class DisasterEntity(
    @PrimaryKey val clientRequestId: String,
    val nombre: String,
    val ubicacion: String,
    val severidad: Int,
    val descripcion: String,
    val latitud: Double,
    val longitud: Double,
    val radio: Double? = null,
    val syncStatus: String = SyncStatus.PENDING,
    val serverId: Int? = null,
    val syncError: String? = null,
    val desastreAsociado: String? = null,
    val fechaHora: String? = null,
    val evaluador: String? = null,
    val organizacion: String? = null,
    val tipoActivo: String? = null,
    val nivelDano: String? = null,
    val evidencias: String? = null,
    val estadoEvaluacion: String? = null,
) {
    fun toReport() = DisasterReportDto(
        nombre = nombre,
        ubicacion = ubicacion,
        severidad = severidad,
        descripcion = descripcion,
        latitud = latitud,
        longitud = longitud,
        radio = radio ?: 500.0,
        desastreAsociado = desastreAsociado,
        fechaHora = fechaHora,
        evaluador = evaluador,
        organizacion = organizacion,
        tipoActivo = tipoActivo,
        nivelDano = nivelDano,
        evidencias = evidencias,
        estadoEvaluacion = estadoEvaluacion,
    )
}

fun DisasterInfoDto.toEntity(localId: String = "server:$id") = DisasterEntity(
    clientRequestId = localId,
    nombre = nombre,
    ubicacion = ubicacion,
    severidad = severidad,
    descripcion = descripcion,
    latitud = latitud,
    longitud = longitud,
    radio = radio,
    syncStatus = SyncStatus.SYNCED,
    serverId = id,
    desastreAsociado = desastreAsociado,
    fechaHora = fechaHora,
    evaluador = evaluador,
    organizacion = organizacion,
    tipoActivo = tipoActivo,
    nivelDano = nivelDano,
    evidencias = evidencias,
    estadoEvaluacion = estadoEvaluacion,
)
