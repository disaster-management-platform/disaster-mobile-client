package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto

data class DisasterReportDto(
    val nombre: String,
    val ubicacion: String,
    val severidad: Int,
    val descripcion: String,
    val latitud: Float,
    val longitud: Float,
    val radio: Float
)
