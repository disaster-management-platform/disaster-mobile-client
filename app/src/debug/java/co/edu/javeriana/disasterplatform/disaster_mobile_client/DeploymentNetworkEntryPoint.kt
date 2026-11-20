package co.edu.javeriana.disasterplatform.disaster_mobile_client

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface DeploymentNetworkEntryPoint {
    fun retrofit(): retrofit2.Retrofit
    fun session(): co.edu.javeriana.disasterplatform.disaster_mobile_client.data.assessments.AssessmentSessionManager
}
