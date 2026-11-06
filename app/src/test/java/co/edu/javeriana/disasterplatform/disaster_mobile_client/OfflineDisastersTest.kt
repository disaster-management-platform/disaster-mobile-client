package co.edu.javeriana.disasterplatform.disaster_mobile_client

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterInfoDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterReportDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDatabase
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterEntity
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.SyncStatus
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote.DisasterApi
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.repository.DisasterRepository
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync.DisasterSyncScheduler
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync.DisasterSynchronizer
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync.SyncDisastersWorker
import com.google.gson.Gson
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.Response
import retrofit2.converter.gson.GsonConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OfflineDisastersTest {
    private lateinit var context: Context
    private lateinit var database: DisasterDatabase
    private lateinit var server: MockWebServer
    private lateinit var api: DisasterApi
    private val databaseName = "offline-test.db"
    private val report = DisasterReportDto("Deslizamiento", "Bogotá", 4, "Material sobre una vía", 4.711, -74.0721)
    private val gson = Gson()
    private val dao get() = database.disasterDao()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
        database = openDatabase()
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder().baseUrl(server.url("/api/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(DisasterApi::class.java)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        database.close()
        context.deleteDatabase(databaseName)
        server.shutdown()
    }

    private fun openDatabase() = Room.databaseBuilder(context, DisasterDatabase::class.java, databaseName).build()

    private fun pending(key: String = UUID.randomUUID().toString()) = DisasterEntity(
        key, report.nombre, report.ubicacion, report.severidad, report.descripcion, report.latitud, report.longitud,
    )

    private fun confirmation(id: Int = 7) = DisasterInfoDto(
        id, report.nombre, report.ubicacion, report.severidad, report.descripcion, report.latitud, report.longitud,
    )

    private fun respond(code: Int, body: Any? = null) {
        server.enqueue(MockResponse().setResponseCode(code).apply {
            if (body != null) setHeader("Content-Type", "application/json").setBody(gson.toJson(body))
        })
    }

    @Test
    fun savesWithoutHttpAndSurvivesDatabaseReopening() = runTest {
        val repository = DisasterRepository(api, dao, DisasterSyncScheduler(context))
        val saved = repository.createDisaster(report).getOrThrow()
        assertEquals(saved.clientRequestId, UUID.fromString(saved.clientRequestId).toString())
        assertEquals(SyncStatus.PENDING, saved.syncStatus)
        assertNull(saved.serverId)
        assertEquals(0, server.requestCount)

        database.close()
        database = openDatabase()
        assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(DisasterSyncScheduler.WORK_NAME).get().single()
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }

    @Test
    fun both201And200ConfirmTheServerId() = runTest {
        val first = pending()
        val second = pending()
        dao.insert(first)
        dao.insert(second)
        respond(201, confirmation(7))
        respond(200, confirmation(8))
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(7, dao.findByRequestId(first.clientRequestId)?.serverId)
        assertEquals(8, dao.findByRequestId(second.clientRequestId)?.serverId)
        assertTrue(dao.observeAll().first().all { it.syncStatus == SyncStatus.SYNCED })
    }

    @Test
    fun retryUsesTheSameUuidAndJson() = runTest {
        val saved = pending()
        dao.insert(saved)
        respond(503)
        val synchronizer = DisasterSynchronizer(dao, api)
        assertFalse(synchronizer.syncPending())
        assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        database.close()
        database = openDatabase()
        respond(200, confirmation())
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        val first = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val second = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/api/desastres", first.path)
        assertEquals("POST", first.method)
        assertEquals(saved.clientRequestId, first.getHeader("Idempotency-Key"))
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
        assertEquals(SyncStatus.SYNCED, dao.findByRequestId(saved.clientRequestId)?.syncStatus)
    }

    @Test
    fun permanentErrorsAreVisibleAndDoNotBlockOtherReports() = runTest {
        val rejected = pending()
        val conflict = pending()
        val accepted = pending()
        listOf(rejected, conflict, accepted).forEach { dao.insert(it) }
        respond(400)
        respond(409)
        respond(201, confirmation())
        val synchronizer = DisasterSynchronizer(dao, api)
        assertTrue(synchronizer.syncPending())
        assertEquals(SyncStatus.ERROR, dao.findByRequestId(rejected.clientRequestId)?.syncStatus)
        assertEquals(SyncStatus.CONFLICT, dao.findByRequestId(conflict.clientRequestId)?.syncStatus)
        assertNotNull(dao.findByRequestId(conflict.clientRequestId)?.syncError)
        assertEquals(SyncStatus.SYNCED, dao.findByRequestId(accepted.clientRequestId)?.syncStatus)
        assertTrue(synchronizer.syncPending())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun temporaryHttpErrorsKeepTheReportPending() = runTest {
        val saved = pending()
        dao.insert(saved)
        for (code in listOf(408, 429, 500, 503)) {
            respond(code)
            assertFalse(DisasterSynchronizer(dao, api).syncPending())
            assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        }
    }

    @Test
    fun invalidOrEmptyConfirmationDoesNotMarkAsSynced() = runTest {
        val saved = pending()
        dao.insert(saved)
        for (body in listOf(confirmation(0), confirmation().copy(nombre = "Otro reporte"))) {
            respond(200, body)
            assertFalse(DisasterSynchronizer(dao, api).syncPending())
            assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        }
        respond(204)
        assertFalse(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        respond(202, confirmation())
        assertFalse(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{invalid-json"))
        assertFalse(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
    }

    @Test
    fun cacheKeepsPendingReportsAndMergesAConcurrentDownload() = runTest {
        val saved = pending()
        dao.insert(saved)
        val remote = listOf(confirmation(7), confirmation(8))
        dao.cacheRemote(remote)
        assertEquals(3, dao.observeAll().first().size)
        assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        dao.markSynced(saved.clientRequestId, 7)
        dao.cacheRemote(remote)
        assertEquals(2, dao.observeAll().first().size)
        assertEquals(saved.clientRequestId, dao.findByServerId(7)?.clientRequestId)
    }

    @Test
    fun ioFailureRetriesAndCancellationPropagates() = runTest {
        val saved = pending()
        dao.insert(saved)
        val offlineApi = object : DisasterApi {
            override suspend fun getDisasters(): List<DisasterInfoDto> = throw IOException()
            override suspend fun createDisaster(requestId: String, report: DisasterReportDto): Response<DisasterInfoDto> =
                throw IOException("Sin conexión")
        }
        val sync = DisasterSynchronizer(dao, offlineApi)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                SyncDisastersWorker(appContext, workerParameters, sync)
        }
        val worker = TestListenableWorkerBuilder<SyncDisastersWorker>(context).setWorkerFactory(factory).build()
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        assertEquals(saved, dao.findByRequestId(saved.clientRequestId))

        val cancelledApi = object : DisasterApi by offlineApi {
            override suspend fun createDisaster(requestId: String, report: DisasterReportDto): Response<DisasterInfoDto> =
                throw CancellationException("Trabajo detenido")
        }
        try {
            DisasterSynchronizer(dao, cancelledApi).syncPending()
            fail("La cancelación no debe convertirse en éxito")
        } catch (_: CancellationException) {
            assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        }
    }

    @Test
    fun invalidDataIsNotQueued() = runTest {
        val repository = DisasterRepository(api, dao, DisasterSyncScheduler(context))
        for (invalid in listOf(report.copy(latitud = Double.NaN), report.copy(longitud = 181.0),
            report.copy(severidad = 6), report.copy(nombre = " "), report.copy(descripcion = "a".repeat(2001)))) {
            assertTrue(repository.createDisaster(invalid).isFailure)
        }
        assertTrue(dao.observeAll().first().isEmpty())
        assertEquals(0, server.requestCount)
    }
}
