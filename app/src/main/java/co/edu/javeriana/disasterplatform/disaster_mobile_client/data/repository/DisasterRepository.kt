package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.repository

import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterInfoDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote.DisasterApi
import javax.inject.Inject

class DisasterRepository @Inject constructor(
    private val disasterApi: DisasterApi
) {
    suspend fun getDisasters(): Result<List<DisasterInfoDto>> {
        return try {
            val disasters = disasterApi.getDisasters()
            Result.success(disasters)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
