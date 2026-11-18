package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto

import com.google.gson.annotations.SerializedName

data class DisasterTypeDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("isActive") val isActive: Boolean? = null,
)
