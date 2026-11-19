package co.edu.javeriana.disasterplatform.disaster_mobile_client

import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote.GatewayBearerInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GatewayBearerInterceptorTest {
    @Test fun gatewayReceivesSessionToken() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("[]"))
        val client = OkHttpClient.Builder().addInterceptor(GatewayBearerInterceptor(server.url("/api/")) { "Bearer synthetic-session" }).build()
        client.newCall(Request.Builder().url(server.url("/api/desastres")).build()).execute().use { assertEquals(200, it.code) }
        assertEquals("Bearer synthetic-session", server.takeRequest().getHeader("Authorization"))
    }
    @Test fun storageReceivesNoGatewayToken() = MockWebServer().use { gateway ->
        MockWebServer().use { storage ->
            storage.enqueue(MockResponse())
            val client = OkHttpClient.Builder().addInterceptor(GatewayBearerInterceptor(gateway.url("/api/")) { error("Session must not be read for Storage") }).build()
            client.newCall(Request.Builder().url(storage.url("/storage/object")).build()).execute().close()
            assertNull(storage.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun explicitAuthorizationIsPreserved() = MockWebServer().use { server ->
        server.enqueue(MockResponse())
        val client = OkHttpClient.Builder().addInterceptor(GatewayBearerInterceptor(server.url("/api/")) { error("Explicit authorization must be preserved") }).build()
        client.newCall(Request.Builder().url(server.url("/api/evaluaciones")).header("Authorization", "Bearer explicit-session").build()).execute().close()
        assertEquals("Bearer explicit-session", server.takeRequest().getHeader("Authorization"))
    }
    @Test fun publicCatalogWorksWithoutSession() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("[]"))
        val client = OkHttpClient.Builder().addInterceptor(GatewayBearerInterceptor(server.url("/api/")) { null }).build()
        client.newCall(Request.Builder().url(server.url("/api/tipos-desastre")).build()).execute().close()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}
