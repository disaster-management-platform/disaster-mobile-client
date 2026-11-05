package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote

import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterInfoDto
import retrofit2.http.GET

interface DisasterApi {

    @GET("desastres")
    suspend fun getDisasters(): List<DisasterInfoDto>
}