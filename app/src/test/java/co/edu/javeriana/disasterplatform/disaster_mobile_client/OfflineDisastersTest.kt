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
import co.edu.javeriana.disasterplatform.disaster_mobile_client.di.NetworkModule
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.FormViewModel
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.FormUiState
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

    private fun openDatabase() = Room.databaseBuilder(context, DisasterDatabase::class.java, databaseName).addMigrations(DisasterDatabase.MIGRATION_1_2).build()

    private fun pending(key: String = UUID.randomUUID().toString()) = DisasterEntity(
        key, report.nombre, report.ubicacion, report.severidad, report.descripcion, report.latitud, report.longitud,
    )

    private fun confirmation(id: Int = 7) = DisasterInfoDto(
        id, report.nombre, report.ubicacion, report.severidad, report.descripcion, report.latitud, report.longitud,
        phase = "Registered", version = 1,
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
        respond(200, confirmation(0))
        assertFalse(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
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
            override suspend fun getDisasterTypes(): List<co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterTypeDto> = throw IOException()
            override suspend fun createDisaster(requestId: String, report: DisasterReportDto): Response<DisasterInfoDto> =
                throw IOException("Sin conexión")
        }
        val sync = DisasterSynchronizer(dao, offlineApi)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                SyncDisastersWorker(appContext, workerParameters, sync, DisasterRepository(offlineApi, dao, DisasterSyncScheduler(context)))
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

    @Test
    fun radiusSurvivesRoomReopeningAndSyncWithoutSubstitution() = runTest {
        val repository = DisasterRepository(api, dao, DisasterSyncScheduler(context))
        val selected = repository.createDisaster(report.copy(radio = 1234.75)).getOrThrow()
        val unknown = repository.createDisaster(report.copy(nombre = "Sin área", radio = null)).getOrThrow()
        database.close()
        database = openDatabase()
        assertEquals(1234.75, dao.findByRequestId(selected.clientRequestId)?.radio)
        assertNull(dao.findByRequestId(unknown.clientRequestId)?.radio)
        respond(201, confirmation(21).copy(radio = 1234.75))
        respond(200, confirmation(22).copy(nombre = "Sin área", radio = null))
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(selected.clientRequestId, sent.getHeader("Idempotency-Key"))
        assertEquals(1234.75, gson.fromJson(sent.body.readUtf8(), DisasterReportDto::class.java).radio)
        val sentUnknown = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertNull(gson.fromJson(sentUnknown.body.readUtf8(), DisasterReportDto::class.java).radio)
        assertEquals(21, dao.findByRequestId(selected.clientRequestId)?.serverId)
        assertEquals(22, dao.findByRequestId(unknown.clientRequestId)?.serverId)
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun radiusMismatchIsVisibleAndNeverConfirmed() = runTest {
        for ((index, radii) in listOf(1234.75 to null, null to 500.0, 1234.75 to 500.0).withIndex()) {
            val saved = pending().copy(radio = radii.first)
            dao.insert(saved)
            respond(200, confirmation(30 + index).copy(radio = radii.second))
            assertTrue(DisasterSynchronizer(dao, api).syncPending())
            assertEquals(saved.copy(syncStatus = SyncStatus.CONFLICT,
                syncError = "El servidor confirmó un contenido diferente. Se conserva el reporte para revisión."),
                dao.findByRequestId(saved.clientRequestId))
        }
    }

    @Test
    fun historicalUnknownRadiusConflictPreservesIdentityAndOtherRecords() = runTest {
        val pending = pending()
        val others = listOf(pending().copy(syncStatus = SyncStatus.SYNCED, serverId = 90),
            pending().copy(syncStatus = SyncStatus.ERROR, syncError = "Error anterior"),
            pending().copy(syncStatus = SyncStatus.CONFLICT, syncError = "Conflicto anterior"))
        (others + pending).forEach { dao.insert(it) }
        database.close()
        database = openDatabase()
        respond(409)
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        val retained = checkNotNull(dao.findByRequestId(pending.clientRequestId))
        assertEquals(pending, retained.copy(syncStatus = SyncStatus.PENDING, syncError = null))
        assertEquals(SyncStatus.CONFLICT, retained.syncStatus)
        assertTrue(retained.syncError!!.contains("histórico"))
        others.forEach { assertEquals(it, dao.findByRequestId(it.clientRequestId)) }
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(1, server.requestCount)
        assertEquals(pending.clientRequestId, server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun invalidRadiiAreRejectedAndConfiguredGatewayIsUsed() = runTest {
        val repository = DisasterRepository(api, dao, DisasterSyncScheduler(context))
        for (radius in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 1_000_000.01)) {
            assertTrue(repository.createDisaster(report.copy(radio = radius)).isFailure)
        }
        assertTrue(dao.observeAll().first().isEmpty())
        assertEquals(1_000_000.0, repository.createDisaster(report.copy(radio = 1_000_000.0)).getOrThrow().radio)
        assertEquals(BuildConfig.API_BASE_URL,
            NetworkModule.providesRetrofit().baseUrl().toString())
    }


    @Test
    fun formRejectsInvalidTextInsteadOfInventingARadius() {
        val viewModel = FormViewModel(
            DisasterRepository(api, dao, DisasterSyncScheduler(context)))
        viewModel.onNombreChanged("Prueba")
        viewModel.onUbicacionChanged("Bogotá")
        viewModel.onDescripcionChanged("Prueba")
        viewModel.onLatitudChanged("4.7")
        viewModel.onLongitudChanged("-74")
        for (text in listOf("inválido", "NaN", "Infinity", "0", "1000001")) {
            viewModel.onRadioChanged(text)
            viewModel.submitForm()
            assertTrue(viewModel.uiState.value is FormUiState.Error)
            assertEquals(0, server.requestCount)
        }
    }


    @Test
    fun completeIncompatibleConfirmationsAreConflictsAndDoNotBlockOthers() = runTest {
        for (response in listOf(confirmation().copy(nombre = "Otro"), confirmation().copy(severidad = 1),
            confirmation().copy(latitud = 0.0), confirmation().copy(longitud = 0.0),
            confirmation().copy(ubicacion = "Otra"), confirmation().copy(descripcion = "Otra"))) {
            val rejected = pending()
            val accepted = pending()
            dao.insert(rejected)
            dao.insert(accepted)
            respond(200, response)
            respond(201, confirmation(100 + server.requestCount))
            assertTrue(DisasterSynchronizer(dao, api).syncPending())
            val retained = checkNotNull(dao.findByRequestId(rejected.clientRequestId))
            assertEquals(SyncStatus.CONFLICT, retained.syncStatus)
            assertEquals(rejected, retained.copy(syncStatus = SyncStatus.PENDING, syncError = null))
            assertEquals(SyncStatus.SYNCED, dao.findByRequestId(accepted.clientRequestId)?.syncStatus)
        }
    }

    @Test
    fun missingOrNullRequiredFieldsRemainPendingWithTheSameIdentity() = runTest {
        val saved = pending().copy(latitud = 0.0, longitud = 0.0)
        dao.insert(saved)
        val complete = gson.toJsonTree(confirmation().copy(latitud = 0.0, longitud = 0.0)).asJsonObject
        val synchronizer = DisasterSynchronizer(dao, api)
        for (field in listOf("id", "nombre", "ubicacion", "severidad", "descripcion", "latitud", "longitud")) {
            for (explicitNull in listOf(false, true)) {
                val incomplete = complete.deepCopy()
                if (explicitNull) incomplete.add(field, com.google.gson.JsonNull.INSTANCE) else incomplete.remove(field)
                server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(incomplete.toString()))
                assertFalse(synchronizer.syncPending())
                assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
                val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals(saved.clientRequestId, request.getHeader("Idempotency-Key"))
                assertEquals(gson.toJson(saved.toReport()), request.body.readUtf8())
            }
        }
        respond(200, confirmation().copy(latitud = 0.0, longitud = 0.0))
        assertTrue(synchronizer.syncPending())
        assertEquals(SyncStatus.SYNCED, dao.findByRequestId(saved.clientRequestId)?.syncStatus)
    }

    @Test
    fun downloadsAreValidatedBeforeWritingAndOlderVersionsNeverReplaceNewerState() = runTest {
        val saved = pending().copy(syncStatus = SyncStatus.SYNCED, serverId = 7, version = 3, phase = "Recovery")
        val conflict = pending().copy(syncStatus = SyncStatus.CONFLICT, serverId = 8, version = 2, syncError = "Revisión")
        dao.insert(saved); dao.insert(conflict)
        val repository = DisasterRepository(api, dao, DisasterSyncScheduler(context))
        val valid = confirmation().copy(version = 4, phase = "Reconstruction")
        val json = gson.toJsonTree(valid).asJsonObject
        for (field in listOf("id", "latitud", "longitud", "version", "phase", "nombre")) {
            val invalid = json.deepCopy(); invalid.remove(field)
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("[$invalid]"))
            try { repository.refreshDisasters(); fail("No debe guardar una respuesta incompleta") }
            catch (_: IllegalArgumentException) { }
            assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        }
        respond(200, emptyList<DisasterInfoDto>()); repository.refreshDisasters()
        assertEquals(2, dao.observeAll().first().size)
        respond(200, listOf(valid.copy(version = 2, nombre = "Antiguo"))); repository.refreshDisasters()
        assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        respond(200, listOf(valid.copy(nombre = "Actual"), confirmation(8).copy(version = 5))); repository.refreshDisasters()
        assertEquals("Actual", dao.findByServerId(7)?.nombre)
        assertEquals(4L, dao.findByServerId(7)?.version)
        assertEquals(conflict, dao.findByRequestId(conflict.clientRequestId))
    }

    @Test
    fun historicalCreationConfirmationKeepsNewerDownloadedStateAndItsOriginalCommand() = runTest {
        val saved = pending().copy(creationPayload = gson.toJson(report))
        dao.insert(saved)
        dao.cacheRemote(listOf(confirmation(7).copy(nombre = "Estado posterior", version = 5, phase = "Reconstruction")))
        respond(200, confirmation(7))
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        val reconciled = checkNotNull(dao.findByRequestId(saved.clientRequestId))
        assertEquals(SyncStatus.SYNCED, reconciled.syncStatus)
        assertEquals("Estado posterior", reconciled.nombre)
        assertEquals(5L, reconciled.version)
        assertEquals(report, reconciled.toReport())
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun catalogAndNewCreationAttributesSurviveOfflineReopeningWithoutDefaults() = runTest {
        val repository = DisasterRepository(api, dao, DisasterSyncScheduler(context))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("[{\"id\":12,\"name\":\"Tipo descargado\",\"isActive\":true}]"))
        repository.refreshTypes()
        assertEquals("/api/tipos-desastre", server.takeRequest().path)
        database.close(); database = openDatabase()
        val reopened = DisasterRepository(api, dao, DisasterSyncScheduler(context))
        assertEquals(12, dao.observeTypes().first().single().id)
        val operation = report.copy(disasterTypeId = 12, startedAt = "2026-11-17T12:00:00Z", attentionLevel = 4, radio = 1234.75)
        val saved = reopened.createDisaster(operation).getOrThrow()
        database.close(); database = openDatabase()
        assertEquals(operation, dao.findByRequestId(saved.clientRequestId)?.toReport())
        assertEquals(1, server.requestCount)
        dao.cacheTypes(listOf(co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterTypeEntity(12, "Tipo descargado", false)))
        assertEquals(operation, dao.findByRequestId(saved.clientRequestId)?.toReport())
    }

    @Test
    fun migrationPreservesVersionOneRowsAndAllSyncStates() = runTest {
        database.close(); context.deleteDatabase(databaseName)
        val schema = gson.fromJson(java.io.File("schemas/co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDatabase/1.json").readText(), com.google.gson.JsonObject::class.java)
            .getAsJsonObject("database")
        val entity = schema.getAsJsonArray("entities")[0].asJsonObject
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)
        old.execSQL(entity.get("createSql").asString.replace("\${TABLE_NAME}", "disasters"))
        for (index in entity.getAsJsonArray("indices")) old.execSQL(index.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", "disasters"))
        for (query in schema.getAsJsonArray("setupQueries")) old.execSQL(query.asString)
        val statuses = listOf(SyncStatus.PENDING, SyncStatus.SYNCED, SyncStatus.ERROR, SyncStatus.CONFLICT)
        statuses.forEachIndexed { index, status ->
            old.execSQL("INSERT INTO disasters(clientRequestId,nombre,ubicacion,severidad,descripcion,latitud,longitud,radio,syncStatus,serverId,syncError) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf("legacy-$index", "Legado", "Bogotá", 4, "Conservar", 4.7, -74.0, if(index == 0) null else 1234.75,
                    status, if(index == 1) 7 else null, if(index > 1) "Conservar error" else null))
        }
        old.version = 1; old.close(); database = openDatabase()
        val rows = dao.observeAll().first()
        assertEquals(4, rows.size)
        for ((index, status) in statuses.withIndex()) {
            val row = checkNotNull(dao.findByRequestId("legacy-$index"))
            assertEquals(status, row.syncStatus)
            assertNull(row.creationPayload); assertNull(row.version); assertNull(row.disasterTypeId); assertNull(row.registeredAt)
            assertEquals(if(index == 0) null else 1234.75, row.radio)
            assertEquals(if(index == 1) 7 else null, row.serverId)
        }
    }

}
