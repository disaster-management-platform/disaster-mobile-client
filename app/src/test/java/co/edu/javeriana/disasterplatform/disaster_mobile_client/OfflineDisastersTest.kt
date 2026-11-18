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
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterBatchRequest
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterBatchResponse
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterBatchResult
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterSyncPage
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

    private fun openDatabase() = Room.databaseBuilder(context, DisasterDatabase::class.java, databaseName).addMigrations(DisasterDatabase.MIGRATION_1_2, DisasterDatabase.MIGRATION_2_3, DisasterDatabase.MIGRATION_3_4).build()

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

    private fun result(saved: DisasterEntity, body: DisasterInfoDto? = null, status: String = "Created") =
        DisasterBatchResult(saved.clientRequestId, status, body, if (status in listOf("ValidationError", "Conflict")) mapOf("operation" to listOf("Revisar")) else null)

    private fun batch(vararg results: DisasterBatchResult) = respond(200, DisasterBatchResponse(results.toList()))
    private fun operations(request: okhttp3.mockwebserver.RecordedRequest) =
        gson.fromJson(request.body.readUtf8(), DisasterBatchRequest::class.java).operations

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
    fun createdAndAlreadyProcessedConfirmTheServerId() = runTest {
        val first = pending()
        val second = pending()
        dao.insert(first)
        dao.insert(second)
        batch(result(first, confirmation(7)), result(second, confirmation(8), "AlreadyProcessed"))
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
        batch(result(saved, confirmation(), "AlreadyProcessed"))
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        val first = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val second = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/api/desastres/batch", first.path)
        assertEquals("POST", first.method)
        val sent = operations(first)
        assertEquals(saved.clientRequestId, sent.single().clientRequestId)
        assertEquals(sent, operations(second))
        assertEquals(SyncStatus.SYNCED, dao.findByRequestId(saved.clientRequestId)?.syncStatus)
    }

    @Test
    fun permanentErrorsAreVisibleAndDoNotBlockOtherReports() = runTest {
        val rejected = pending()
        val conflict = pending()
        val accepted = pending()
        listOf(rejected, conflict, accepted).forEach { dao.insert(it) }
        batch(result(rejected, status = "ValidationError"), result(conflict, status = "Conflict"), result(accepted, confirmation()))
        val synchronizer = DisasterSynchronizer(dao, api)
        assertTrue(synchronizer.syncPending())
        assertEquals(SyncStatus.ERROR, dao.findByRequestId(rejected.clientRequestId)?.syncStatus)
        assertEquals(SyncStatus.CONFLICT, dao.findByRequestId(conflict.clientRequestId)?.syncStatus)
        assertNotNull(dao.findByRequestId(conflict.clientRequestId)?.syncError)
        assertEquals(SyncStatus.SYNCED, dao.findByRequestId(accepted.clientRequestId)?.syncStatus)
        assertTrue(synchronizer.syncPending())
        assertEquals(1, server.requestCount)
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
        batch(result(saved, confirmation(0)))
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
            override suspend fun createBatch(request: DisasterBatchRequest): Response<DisasterBatchResponse> = throw IOException()
            override suspend fun syncDisasters(cursor: String?, limit: Int): DisasterSyncPage = throw IOException()
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
            override suspend fun createBatch(request: DisasterBatchRequest): Response<DisasterBatchResponse> =
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
        batch(result(selected, confirmation(21).copy(radio = 1234.75)),
            result(unknown, confirmation(22).copy(nombre = "Sin área", radio = null), "AlreadyProcessed"))
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val uploaded = operations(sent)
        assertEquals(selected.clientRequestId, uploaded[0].clientRequestId)
        assertEquals(1234.75, uploaded[0].disaster.radio)
        assertNull(uploaded[1].disaster.radio)
        assertEquals(21, dao.findByRequestId(selected.clientRequestId)?.serverId)
        assertEquals(22, dao.findByRequestId(unknown.clientRequestId)?.serverId)
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun radiusMismatchIsVisibleAndNeverConfirmed() = runTest {
        for ((index, radii) in listOf(1234.75 to null, null to 500.0, 1234.75 to 500.0).withIndex()) {
            val saved = pending().copy(radio = radii.first)
            dao.insert(saved)
            batch(result(saved, confirmation(30 + index).copy(radio = radii.second)))
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
        batch(result(pending, status = "Conflict"))
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        val retained = checkNotNull(dao.findByRequestId(pending.clientRequestId))
        assertEquals(pending, retained.copy(syncStatus = SyncStatus.PENDING, syncError = null))
        assertEquals(SyncStatus.CONFLICT, retained.syncStatus)
        assertTrue(retained.syncError!!.contains("histórico"))
        others.forEach { assertEquals(it, dao.findByRequestId(it.clientRequestId)) }
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(1, server.requestCount)
        assertEquals(pending.clientRequestId, operations(server.takeRequest()).single().clientRequestId)
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
            batch(result(rejected, response), result(accepted, confirmation(100 + server.requestCount)))
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
                server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"results":[{"clientRequestId":"${saved.clientRequestId}","status":"Created","disaster":$incomplete}]}"""))
                assertFalse(synchronizer.syncPending())
                assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
                val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val operation = operations(request).single()
                assertEquals(saved.clientRequestId, operation.clientRequestId)
                assertEquals(saved.toReport(), operation.disaster)
            }
        }
        batch(result(saved, confirmation().copy(latitud = 0.0, longitud = 0.0)))
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
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"items":[$invalid],"nextCursor":"invalid-page","hasMore":false}"""))
            try { repository.refreshDisasters(); fail("No debe guardar una respuesta incompleta") }
            catch (_: IllegalArgumentException) { }
            assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        }
        respond(200, DisasterSyncPage(emptyList(), "empty", false)); repository.refreshDisasters()
        assertEquals(2, dao.observeAll().first().size)
        respond(200, DisasterSyncPage(listOf(valid.copy(version = 2, nombre = "Antiguo")), "older", false)); repository.refreshDisasters()
        assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        respond(200, DisasterSyncPage(listOf(valid.copy(nombre = "Actual"), confirmation(8).copy(version = 5)), "newer", false)); repository.refreshDisasters()
        assertEquals("Actual", dao.findByServerId(7)?.nombre)
        assertEquals(4L, dao.findByServerId(7)?.version)
        assertEquals(conflict, dao.findByRequestId(conflict.clientRequestId))
    }

    @Test
    fun historicalCreationConfirmationKeepsNewerDownloadedStateAndItsOriginalCommand() = runTest {
        val saved = pending().copy(creationPayload = gson.toJson(report))
        dao.insert(saved)
        dao.cacheRemote(listOf(confirmation(7).copy(nombre = "Estado posterior", version = 5, phase = "Reconstruction")))
        batch(result(saved, confirmation(7), "AlreadyProcessed"))
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

    @Test
    fun uploads130ReportsInThreeBatchesWithoutResendingConfirmedOperations() = runTest {
        val saved = (0 until 130).map { pending().copy(nombre = "Batch $it") }
        saved.forEach { dao.insert(it) }
        for ((index, chunk) in saved.chunked(50).withIndex()) batch(*chunk.mapIndexed { offset, item ->
            result(item, confirmation(index * 50 + offset + 1).copy(nombre = item.nombre))
        }.toTypedArray())
        val sync = DisasterSynchronizer(dao, api)
        assertTrue(sync.syncPending())
        assertEquals(3, server.requestCount)
        for ((index, chunk) in saved.chunked(50).withIndex()) {
            val request = server.takeRequest()
            assertEquals("/api/desastres/batch", request.path)
            val sent = operations(request)
            assertEquals(listOf(50, 50, 30)[index], sent.size)
            assertEquals(chunk.map { it.clientRequestId }, sent.map { it.clientRequestId })
            assertEquals(chunk.map { it.toReport() }, sent.map { it.disaster })
        }
        assertTrue(dao.observeAll().first().all { it.syncStatus == SyncStatus.SYNCED })
        assertTrue(sync.syncPending()); assertEquals(3, server.requestCount)
    }

    @Test
    fun partialDuplicateAndUnknownResultsNeverConfirmAnUnmatchedOperation() = runTest {
        val accepted = pending(); val missing = pending(); val ambiguous = pending()
        listOf(accepted, missing, ambiguous).forEach { dao.insert(it) }
        batch(result(accepted, confirmation(7)), result(ambiguous, confirmation(8)), result(ambiguous, confirmation(8)),
            DisasterBatchResult(UUID.randomUUID().toString(), "Created", confirmation(9)))
        assertFalse(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(SyncStatus.SYNCED, dao.findByRequestId(accepted.clientRequestId)?.syncStatus)
        assertEquals(missing, dao.findByRequestId(missing.clientRequestId))
        assertEquals(ambiguous, dao.findByRequestId(ambiguous.clientRequestId))
        server.takeRequest()
        batch(result(missing, confirmation(8), "AlreadyProcessed"), result(ambiguous, confirmation(9), "AlreadyProcessed"))
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(listOf(missing.clientRequestId, ambiguous.clientRequestId), operations(server.takeRequest()).map { it.clientRequestId })
    }

    @Test
    fun interruptedDeltaResumesAfterReopeningAndNeverOverwritesNewerVersions() = runTest {
        val repository = DisasterRepository(api, dao, DisasterSyncScheduler(context))
        respond(200, DisasterSyncPage(listOf(confirmation(7).copy(version = 5)), "page-one", true))
        respond(503)
        try { repository.refreshDisasters(); fail("La interrupción debe ser visible") } catch (_: retrofit2.HttpException) { }
        assertEquals("page-one", dao.getSyncCursor()); assertEquals(5L, dao.findByServerId(7)?.version)
        database.close(); database = openDatabase()
        respond(200, DisasterSyncPage(listOf(confirmation(7).copy(version = 2, nombre = "Antiguo"), confirmation(8)), "page-two", false))
        DisasterRepository(api, dao, DisasterSyncScheduler(context)).refreshDisasters()
        assertEquals("page-two", dao.getSyncCursor()); assertEquals(5L, dao.findByServerId(7)?.version)
        assertEquals(2, dao.observeAll().first().size)
        assertEquals("/api/desastres/sync?limit=50", server.takeRequest().path)
        assertTrue(server.takeRequest().path!!.contains("cursor=page-one"))
        assertTrue(server.takeRequest().path!!.contains("cursor=page-one"))
    }

    @Test
    fun cursorAndPageAreAtomicAndAStaleDownloadCannotMoveTheCursorBackwards() = runTest {
        assertTrue(dao.applySyncPage(null, "first", listOf(confirmation(7).copy(version = 3))))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cursor BEFORE INSERT ON disaster_sync_state BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
        try {
            dao.applySyncPage("first", "second", listOf(confirmation(7).copy(version = 4, nombre = "No guardar"), confirmation(8)))
            fail("La página debe revertirse con el cursor")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals("first", dao.getSyncCursor()); assertEquals(3L, dao.findByServerId(7)?.version)
        assertNull(dao.findByServerId(8))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cursor")
        assertFalse(dao.applySyncPage(null, "old", listOf(confirmation(8))))
        assertEquals("first", dao.getSyncCursor()); assertNull(dao.findByServerId(8))
    }

    @Test
    fun versionTwoMigrationKeepsCreationPayloadCatalogAndPendingIdentity() = runTest {
        database.close(); context.deleteDatabase(databaseName)
        val schema = gson.fromJson(java.io.File("schemas/co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDatabase/2.json").readText(), com.google.gson.JsonObject::class.java)
            .getAsJsonObject("database")
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)
        for (entry in schema.getAsJsonArray("entities")) {
            val entity = entry.asJsonObject
            val table = entity.get("tableName").asString
            old.execSQL(entity.get("createSql").asString.replace("$"+"{TABLE_NAME}", table))
            for (index in entity.getAsJsonArray("indices") ?: com.google.gson.JsonArray()) old.execSQL(index.asJsonObject.get("createSql").asString.replace("$"+"{TABLE_NAME}", table))
        }
        for (query in schema.getAsJsonArray("setupQueries")) old.execSQL(query.asString)
        old.execSQL("INSERT INTO disasters(clientRequestId,nombre,ubicacion,severidad,descripcion,latitud,longitud,radio,syncStatus,serverId,creationPayload) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf("stable-operation", report.nombre, report.ubicacion, report.severidad, report.descripcion, report.latitud, report.longitud, 1234.75, SyncStatus.PENDING, 7, gson.toJson(report)))
        old.execSQL("INSERT INTO disaster_types(id,name,isActive) VALUES(12,'Histórico',0)")
        old.version = 2; old.close(); database = openDatabase()
        assertNull(dao.getSyncCursor())
        val retained = checkNotNull(dao.findByRequestId("stable-operation"))
        assertEquals(7, retained.serverId); assertEquals(1234.75, retained.radio)
        assertEquals(SyncStatus.PENDING, retained.syncStatus); assertEquals(report, retained.toReport())
        assertFalse(dao.observeTypes().first().single().isActive)
    }

    @Test
    fun offlineRecoveryCoalescesQueuedWorkInsteadOfCreating130DownloadPasses() = runTest {
        val repository = DisasterRepository(api, dao, DisasterSyncScheduler(context))
        repeat(130) { repository.createDisaster(report.copy(nombre = "Pendiente $it")).getOrThrow() }
        assertEquals(130, dao.observeAll().first().size)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(DisasterSyncScheduler.WORK_NAME)
            .get().count { !it.state.isFinished })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun nullBatchResultsAndInvalidErrorDetailsRemainPendingWithoutStoppingOtherReports() = runTest {
        val saved = pending(); dao.insert(saved)
        respond(200, DisasterBatchResponse(listOf(null, DisasterBatchResult(saved.clientRequestId, "ValidationError",
            errors = mapOf("operation" to null)))))
        assertFalse(DisasterSynchronizer(dao, api).syncPending()); assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        respond(200, DisasterBatchResponse(listOf(DisasterBatchResult(saved.clientRequestId, "RetryableFailure"))))
        assertFalse(DisasterSynchronizer(dao, api).syncPending()); assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        val other = pending(); dao.insert(other)
        batch(result(other, confirmation(8)))
        assertFalse(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(SyncStatus.SYNCED, dao.findByRequestId(other.clientRequestId)?.syncStatus)
        assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
    }

    @Test
    fun nullDeltaEntriesNeverAdvanceTheCursorOrReplaceValidLocalData() = runTest {
        val saved = pending().copy(syncStatus = SyncStatus.SYNCED, serverId = 7, version = 3)
        dao.insert(saved)
        respond(200, DisasterSyncPage(listOf(null, confirmation(8)), "not-confirmed", false))
        try { DisasterRepository(api, dao, DisasterSyncScheduler(context)).refreshDisasters(); fail("La página incompleta no se confirma") }
        catch (_: IllegalArgumentException) { }
        assertNull(dao.getSyncCursor()); assertEquals(listOf(saved), dao.observeAll().first())
    }

    @Test
    fun permanentBatchRejectionsPreserveIdentityWithoutEndlessRetries() = runTest {
        for (code in listOf(400, 401, 403, 404, 413, 422)) {
            val saved = pending(); dao.insert(saved); respond(code)
            assertTrue(DisasterSynchronizer(dao, api).syncPending())
            val rejected = checkNotNull(dao.findByRequestId(saved.clientRequestId))
            assertEquals(SyncStatus.ERROR, rejected.syncStatus)
            assertEquals(saved.toReport(), rejected.toReport()); assertEquals(saved.clientRequestId, rejected.clientRequestId)
            val requests = server.requestCount
            assertTrue(DisasterSynchronizer(dao, api).syncPending()); assertEquals(requests, server.requestCount)
        }
    }

    @Test
    fun oversizedBatchesSplitAndUnconfirmedFirstPageDoesNotStarveLaterReports() = runTest {
        val rows = (1..51).map { pending() }; rows.forEach { dao.insert(it) }
        // The first 50 lack confirmations; the next report still proceeds.
        batch(); batch(result(rows.last(), confirmation(77)))
        assertFalse(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(SyncStatus.SYNCED, dao.findByRequestId(rows.last().clientRequestId)?.syncStatus)
        assertEquals(50, dao.getPending().size)
        respond(413)
        batch(*rows.take(25).mapIndexed { index, row -> result(row, confirmation(100 + index)) }.toTypedArray())
        batch(*rows.drop(25).take(25).mapIndexed { index, row -> result(row, confirmation(200 + index)) }.toTypedArray())
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        assertTrue(dao.getPending().isEmpty())
    }

    @Test
    fun catalogRefreshIsIndependentOfDownloadAndUploadFailures() = runTest {
        val saved = pending(); dao.insert(saved)
        respond(503)
        respond(200, listOf(mapOf("id" to 13, "name" to "Tipo recibido", "isActive" to true)))
        respond(503)
        val repository = DisasterRepository(api, dao, DisasterSyncScheduler(context))
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                SyncDisastersWorker(appContext, workerParameters, DisasterSynchronizer(dao, api), repository)
        }
        val worker = TestListenableWorkerBuilder<SyncDisastersWorker>(context).setWorkerFactory(factory).build()
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        assertEquals("Tipo recibido", dao.findType(13)?.name)
        assertEquals(saved, dao.findByRequestId(saved.clientRequestId))
        respond(200, listOf(mapOf("id" to 14, "name" to "Actualización manual", "isActive" to true)))
        respond(503)
        try { repository.refreshDisastersAndTypes(); fail("Download should fail") } catch (_: retrofit2.HttpException) { }
        assertEquals("Actualización manual", dao.findType(14)?.name)
    }

    @Test
    fun geographicStateIsValidatedAndPersistedReactivelyWithoutChangingCreationCommandsOrCursor() = runTest {
        val saved = pending().copy(syncStatus = SyncStatus.SYNCED, serverId = 7, version = 1, creationPayload = gson.toJson(report))
        dao.insert(saved)
        val area = com.google.gson.JsonParser.parseString("""{"polygon":{"type":"Polygon","coordinates":[[[-74,4],[-73,4],[-73,5],[-74,4]]]},"municipalities":["Bogotá"]}""").asJsonObject
        val remote = confirmation(7).copy(version = 2, geography = area, registeringOrganizationId = "eden:4", emergencyResponsibleId = "responsible:1")
        respond(200, DisasterSyncPage(listOf(remote), "new-page", false))
        DisasterRepository(api, dao, DisasterSyncScheduler(context)).refreshDisasters()
        val row = dao.observeAll().first().single()
        assertEquals("eden:4", row.registeringOrganizationId); assertEquals(area.toString(), row.geographyJson)
        assertEquals(saved.creationPayload, row.creationPayload); assertEquals("new-page", dao.getSyncCursor())
        database.close(); database = openDatabase()
        assertEquals(area.toString(), dao.findByServerId(7)?.geographyJson)
        val invalid = remote.copy(version = 3, geography = com.google.gson.JsonParser.parseString("""{"polygon":{"type":"Polygon","coordinates":[[[999,4],[1,4],[1,5],[999,4]]]}}""").asJsonObject)
        respond(200, DisasterSyncPage(listOf(invalid), "invalid-page", false))
        try { DisasterRepository(api, dao, DisasterSyncScheduler(context)).refreshDisasters(); fail("Invalid geometry") }
        catch (_: IllegalArgumentException) { }
        assertEquals(2L, dao.findByServerId(7)?.version); assertEquals("new-page", dao.getSyncCursor())
    }

    @Test
    fun versionThreeMigrationKeepsPendingCommandsErrorsCatalogAndCursor() = runTest {
        database.close(); context.deleteDatabase(databaseName)
        val schema = gson.fromJson(java.io.File("schemas/co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDatabase/3.json").readText(), com.google.gson.JsonObject::class.java).getAsJsonObject("database")
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)
        for (entry in schema.getAsJsonArray("entities")) {
            val entity = entry.asJsonObject; val name = entity.get("tableName").asString
            old.execSQL(entity.get("createSql").asString.replace("\${TABLE_NAME}", name))
            entity.getAsJsonArray("indices")?.forEach { old.execSQL(it.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", name)) }
        }
        for (query in schema.getAsJsonArray("setupQueries")) old.execSQL(query.asString)
        old.execSQL("INSERT INTO disasters(clientRequestId,nombre,ubicacion,severidad,descripcion,latitud,longitud,radio,syncStatus,creationPayload) VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf("original-id", "Histórico", "Local", 2, "Conservar", 4.7, -74.0, 1234.5, SyncStatus.CONFLICT, gson.toJson(report)))
        old.execSQL("INSERT INTO disaster_sync_state(name,cursor) VALUES('disasters','saved-cursor')")
        old.execSQL("INSERT INTO disaster_types(id,name,isActive) VALUES(12,'Histórico',0)")
        old.version = 3; old.close(); database = openDatabase()
        val row = checkNotNull(dao.findByRequestId("original-id"))
        assertEquals(SyncStatus.CONFLICT, row.syncStatus); assertEquals(gson.toJson(report), row.creationPayload)
        assertEquals(1234.5, row.radio); assertNull(row.geographyJson)
        assertEquals("saved-cursor", dao.getSyncCursor()); assertEquals(false, dao.findType(12)?.isActive)
    }

    @Test
    fun acceptedReceiptsSurviveRestartAndConfirmWithoutBlockingNewReports() = runTest {
        val first = pending(); val second = pending(); dao.insert(first); dao.insert(second)
        respond(202, DisasterBatchResponse(listOf(result(first, status = "Accepted"), result(second, confirmation(88)))))
        assertFalse(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(SyncStatus.ACCEPTED, dao.findByRequestId(first.clientRequestId)?.syncStatus)
        assertEquals(SyncStatus.SYNCED, dao.findByRequestId(second.clientRequestId)?.syncStatus)
        database.close(); database = openDatabase()
        batch(result(first, confirmation(89), "AlreadyProcessed"))
        assertTrue(DisasterSynchronizer(dao, api).syncPending())
        assertEquals(SyncStatus.SYNCED, dao.findByRequestId(first.clientRequestId)?.syncStatus)
        val original = operations(checkNotNull(server.takeRequest())).first()
        val retried = operations(checkNotNull(server.takeRequest())).single()
        assertEquals(original, retried)
    }

}
