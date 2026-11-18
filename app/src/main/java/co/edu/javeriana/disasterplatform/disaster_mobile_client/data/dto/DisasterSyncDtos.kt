package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto

data class DisasterOperation(val clientRequestId: String, val disaster: DisasterReportDto)
data class DisasterBatchRequest(val operations: List<DisasterOperation>)
data class DisasterBatchResult(val clientRequestId: String? = null, val status: String? = null,
    val disaster: DisasterInfoDto? = null, val errors: Map<String, List<String?>?>? = null)
data class DisasterBatchResponse(val results: List<DisasterBatchResult?>? = null)
data class DisasterSyncPage(val items: List<DisasterInfoDto?>? = null, val nextCursor: String? = null, val hasMore: Boolean? = null)
