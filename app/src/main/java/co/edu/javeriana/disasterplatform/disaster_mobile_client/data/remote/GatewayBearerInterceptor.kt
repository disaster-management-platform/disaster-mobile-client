package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

class GatewayBearerInterceptor(
    private val gateway: HttpUrl,
    private val authorization: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val trusted = url.scheme == gateway.scheme && url.host == gateway.host &&
            url.port == gateway.port && url.encodedPath.startsWith(gateway.encodedPath)
        val token = if (trusted && request.header("Authorization") == null) authorization() else null
        return chain.proceed(if (token == null) request else request.newBuilder().header("Authorization", token).build())
    }
}
