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
    val syncStatus: String = SyncStatus.PENDING,
    val serverId: Int? = null,
    val syncError: String? = null,
) {
    fun toReport() = DisasterReportDto(nombre, ubicacion, severidad, descripcion, latitud, longitud)
}

fun DisasterInfoDto.toEntity(localId: String = "server:$id") = DisasterEntity(
    clientRequestId = localId, nombre = nombre, ubicacion = ubicacion, severidad = severidad,
    descripcion = descripcion, latitud = latitud, longitud = longitud,
    syncStatus = SyncStatus.SYNCED, serverId = id,
)
