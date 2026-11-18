package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local

import com.google.gson.Gson
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
    val disasterTypeId: Int? = null,
    val startedAt: String? = null,
    val registeredAt: String? = null,
    val updatedAt: String? = null,
    val attentionLevel: Int? = null,
    val phase: String? = null,
    val version: Long? = null,
    val registeringOrganizationId: String? = null,
    val emergencyResponsibleId: String? = null,
    val geographyJson: String? = null,
    val organizationsJson: String? = null,
    val creationPayload: String? = null,
) {
    fun toReport(): DisasterReportDto = creationPayload?.let { Gson().fromJson(it, DisasterReportDto::class.java) }
        ?: DisasterReportDto(nombre, ubicacion, severidad, descripcion, latitud, longitud, radio,
            disasterTypeId, startedAt, attentionLevel)
}

fun DisasterInfoDto.toEntity(localId: String = "server:$id") = DisasterEntity(
    clientRequestId = localId, nombre = nombre, ubicacion = ubicacion, severidad = severidad,
    descripcion = descripcion, latitud = latitud, longitud = longitud, radio = radio,
    syncStatus = SyncStatus.SYNCED, serverId = id,
    disasterTypeId = disasterTypeId, startedAt = startedAt, registeredAt = registeredAt, updatedAt = updatedAt,
    attentionLevel = attentionLevel, phase = phase, version = version,
    registeringOrganizationId = registeringOrganizationId, emergencyResponsibleId = emergencyResponsibleId,
    geographyJson = geography?.toString(), organizationsJson = organizations?.let { Gson().toJson(it) },
)
