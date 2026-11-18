package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote

import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterTypeDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterInfoDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterReportDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterBatchRequest
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterBatchResponse
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterSyncPage

interface DisasterApi {

    @retrofit2.http.Headers("Prefer: respond-async")
    @POST("desastres/batch")
    suspend fun createBatch(@Body request: DisasterBatchRequest): Response<DisasterBatchResponse>

    @GET("desastres/sync")
    suspend fun syncDisasters(@Query("cursor") cursor: String?, @Query("limit") limit: Int = 50): DisasterSyncPage

    @GET("desastres")
    suspend fun getDisasters(): List<DisasterInfoDto>

    @GET("tipos-desastre")
    suspend fun getDisasterTypes(): List<DisasterTypeDto>

    @POST("desastres")
    suspend fun createDisaster(
        @Header("Idempotency-Key") requestId: String,
        @Body report: DisasterReportDto,
    ): Response<DisasterInfoDto>
}
