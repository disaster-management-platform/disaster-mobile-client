package co.edu.javeriana.disasterplatform.disaster_mobile_client

import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterReportDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDatabase
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.SyncStatus
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.repository.DisasterRepository
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync.DisasterSyncScheduler
import co.edu.javeriana.disasterplatform.disaster_mobile_client.di.NetworkModule
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.FormViewModel
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineGatewayInstrumentedTest {
    @Test
    fun offlineRegistrationAndAutomaticRecovery() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val stage = InstrumentationRegistry.getArguments().getString("offline-stage", "complete")
        val preferences = context.getSharedPreferences("offline-gateway-test", Context.MODE_PRIVATE)
        val identity = dagger.hilt.android.EntryPointAccessors.fromApplication(context, DeploymentNetworkEntryPoint::class.java).session()
        identity.restore()
        InstrumentationRegistry.getArguments().getString("assessment-token")?.let { identity.accept(it) }
        check(identity.session.value?.isCurrent == true) { "Supply a current authorized identity" }
        val api = NetworkModule.providesDisasterRetrofitService(dagger.hilt.android.EntryPointAccessors.fromApplication(context, DeploymentNetworkEntryPoint::class.java).retrofit())
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun network(enabled: Boolean) {
            for (service in listOf("wifi", "data")) {
                instrumentation.uiAutomation.executeShellCommand("svc $service ${if (enabled) "enable" else "disable"}")
                    .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes() }
            }
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 120_000
            while (!condition()) {
                check(System.currentTimeMillis() < deadline) { message }
                Thread.sleep(200)
            }
        }
        val database = Room.databaseBuilder(context, DisasterDatabase::class.java, "disasters.db").addMigrations(DisasterDatabase.MIGRATION_1_2, DisasterDatabase.MIGRATION_2_3, DisasterDatabase.MIGRATION_3_4, DisasterDatabase.MIGRATION_4_5).build()
        try {
            val dao = database.disasterDao()
            if (stage != "recover") {
                val repository = DisasterRepository(api, dao, DisasterSyncScheduler(context))
                repository.refreshTypes()
                val typeId = dao.observeTypes().first().first { it.isActive }.id
                network(false)
                await("El emulador no perdió la conectividad") { connectivity.activeNetwork == null }
                val operation = UUID.randomUUID().toString()
                val viewModel = FormViewModel(repository)
                instrumentation.runOnMainSync {
                    viewModel.onNombreChanged("Offline $operation")
                    viewModel.onUbicacionChanged("Bogotá")
                    viewModel.onDescripcionChanged("Integración offline")
                    viewModel.onLatitudChanged("4.711")
                    viewModel.onLongitudChanged("-74.0721")
                    viewModel.onRadioChanged("1234.75")
                    viewModel.onTypeChanged(typeId)
                    viewModel.onStartedAtChanged("2026-11-17T12:00:00Z")
                    viewModel.onAttentionChanged(4)
                    viewModel.submitForm()
                }
                await("El formulario no guardó el reporte") {
                    runBlocking { dao.getPending().any { it.nombre == "Offline $operation" } }
                }
                val selected = dao.getPending().single { it.nombre == "Offline $operation" }
                val unknown = repository.createDisaster(DisasterReportDto("Unknown $operation", "Bogotá", 3,
                    "Integración offline", 4.711, -74.0721, null)).getOrThrow()
                assertEquals(1234.75, selected.radio)
                assertNull(unknown.radio)
                assertEquals(SyncStatus.PENDING, selected.syncStatus)
                assertEquals(SyncStatus.PENDING, unknown.syncStatus)
                check(preferences.edit().putString("selected", selected.clientRequestId)
                    .putString("unknown", unknown.clientRequestId).commit())
                if (stage == "store") return@runBlocking
            }
            val ids = listOf(checkNotNull(preferences.getString("selected", null)),
                checkNotNull(preferences.getString("unknown", null)))
            val before = ids.map { checkNotNull(dao.findByRequestId(it)) }
            assertEquals(1234.75, before[0].radio)
            assertNull(before[1].radio)
            assertEquals(4, before[0].attentionLevel)
            assertEquals("2026-11-17T12:00:00Z", before[0].startedAt)
            assertNotNull(before[0].disasterTypeId)
            assertTrue(before.all { it.syncStatus == SyncStatus.PENDING })
            network(true)
            await("WorkManager no sincronizó los pendientes") {
                runBlocking { ids.all { dao.findByRequestId(it)?.syncStatus == SyncStatus.SYNCED } }
            }
            val remote = api.getDisasters()
            InstrumentationRegistry.getArguments().getString("web-disaster-name")?.let { name ->
                assertEquals(8765.25, remote.single { it.nombre == name }.radio)
            }
            for (saved in before) {
                val local = checkNotNull(dao.findByRequestId(saved.clientRequestId))
                assertEquals(saved.radio, local.radio)
                val confirmed = remote.single { it.nombre == saved.nombre }
                assertEquals(local.serverId, confirmed.id)
                assertEquals(saved.radio, confirmed.radio)
                val replay = api.createDisaster(saved.clientRequestId, saved.toReport())
                assertEquals(200, replay.code())
                assertEquals(local.serverId, replay.body()?.id)
            }
            assertTrue(api.getDisasters().filter { item -> before.any { it.nombre == item.nombre } }.size == 2)
            for (id in ids) database.openHelper.writableDatabase.execSQL(
                "DELETE FROM disasters WHERE clientRequestId = ?", arrayOf(id))
            check(preferences.edit().clear().commit())
        } finally {
            if (stage != "store") network(true)
            database.close()
        }
    }
}
