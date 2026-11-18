package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto

import com.google.gson.annotations.SerializedName

data class DisasterInfoDto(
    @SerializedName("id")
    val id: Int = 0,
    @SerializedName("nombre")
    val nombre: String = "",
    @SerializedName("ubicacion")
    val ubicacion: String = "",
    @SerializedName("severidad")
    val severidad: Int = 0,
    @SerializedName("descripcion")
    val descripcion: String = "",
    @SerializedName("latitud")
    val latitud: Double = Double.NaN,
    @SerializedName("longitud")
    val longitud: Double = Double.NaN,
    @SerializedName("radio")
    val radio: Double? = null,
    @SerializedName("disasterTypeId") val disasterTypeId: Int? = null,
    @SerializedName("startedAt") val startedAt: String? = null,
    @SerializedName("registeredAt") val registeredAt: String? = null,
    @SerializedName("updatedAt") val updatedAt: String? = null,
    @SerializedName("attentionLevel") val attentionLevel: Int? = null,
    @SerializedName("phase") val phase: String? = null,
    @SerializedName("version") val version: Long? = null,
)
