package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto

import com.google.gson.annotations.SerializedName

data class DisasterInfoDto(
    @SerializedName("id")
    val id: Int,
    @SerializedName("nombre")
    val nombre: String,
    @SerializedName("ubicacion")
    val ubicacion: String,
    @SerializedName("severidad")
    val severidad: Int,
    @SerializedName("descripcion")
    val descripcion: String,
    @SerializedName("latitud")
    val latitud: Float,
    @SerializedName("longitud")
    val longitud: Float
)
