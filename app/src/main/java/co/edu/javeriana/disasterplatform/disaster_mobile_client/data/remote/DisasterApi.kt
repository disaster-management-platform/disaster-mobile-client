package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote

import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterInfoDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterReportDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface DisasterApi {

    @GET("desastres")
    suspend fun getDisasters(): List<DisasterInfoDto>

    @POST("desastres")
    suspend fun createDisaster(@Body disasterReport: DisasterReportDto): DisasterInfoDto
}