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
    val latitud: Double,
    @SerializedName("longitud")
    val longitud: Double,
    @SerializedName("radio")
    val radio: Double? = null,
    @SerializedName("desastreAsociado")
    val desastreAsociado: String? = null,
    @SerializedName("fechaHora")
    val fechaHora: String? = null,
    @SerializedName("evaluador")
    val evaluador: String? = null,
    @SerializedName("organizacion")
    val organizacion: String? = null,
    @SerializedName("tipoActivo")
    val tipoActivo: String? = null,
    @SerializedName("nivelDano")
    val nivelDano: String? = null,
    @SerializedName("evidencias")
    val evidencias: String? = null,
    @SerializedName("estadoEvaluacion")
    val estadoEvaluacion: String? = null,
)
