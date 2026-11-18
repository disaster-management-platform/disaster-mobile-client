package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.assessments

import com.google.gson.JsonObject
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface AssessmentApi {
    @GET("evaluaciones/catalogos") suspend fun catalogs(@Header("Authorization") authorization: String): Response<JsonObject>
    @POST("evaluaciones/batch") suspend fun batch(@Header("Authorization") authorization: String, @Body body: JsonObject): Response<JsonObject>
    @GET("evaluaciones/sync") suspend fun delta(@Header("Authorization") authorization: String, @Query("disasterId") disaster: Int?, @Query("cursor") cursor: String?, @Query("limit") limit: Int = 50): Response<JsonObject>
    @GET("evaluaciones/{id}") suspend fun get(@Header("Authorization") authorization: String, @Path("id") id: String): Response<JsonObject>
    @PUT("evaluaciones/{id}") suspend fun edit(@Header("Authorization") authorization: String, @Path("id") id: String, @Body body: JsonObject): Response<JsonObject>
    @POST("evaluaciones/{id}/evidencias") suspend fun authorize(@Header("Authorization") authorization: String, @Path("id") id: String, @Header("Idempotency-Key") request: String, @Body body: JsonObject): Response<JsonObject>
    @POST("evaluaciones/{id}/evidencias/{file}/confirmar") suspend fun confirm(@Header("Authorization") authorization: String, @Path("id") id: String, @Path("file") file: String): Response<JsonObject>
    @GET("evaluaciones/{id}/evidencias/{file}") suspend fun evidence(@Header("Authorization") authorization: String, @Path("id") id: String, @Path("file") file: String): Response<JsonObject>
    @GET("evaluaciones/{id}/evidencias/{file}/descarga") suspend fun download(@Header("Authorization") authorization: String, @Path("id") id: String, @Path("file") file: String): Response<JsonObject>
    @Streaming @GET suspend fun readStorage(@Url url: String): Response<ResponseBody>
    @PUT suspend fun upload(@Url url: String, @HeaderMap headers: Map<String, String>, @Body body: RequestBody): Response<ResponseBody>
}
