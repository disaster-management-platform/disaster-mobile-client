package co.edu.javeriana.disasterplatform.disaster_mobile_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterInfoDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.*
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote.DisasterApi
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.repository.DisasterRepository
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync.DisasterSyncScheduler
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.screens.DisastersScreen
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.screens.FormScreen
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.DisastersViewModel
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.FormViewModel
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@RunWith(AndroidJUnit4::class)
class ReactiveBindingInstrumentedTest {

    @Test
    fun roomChangesUpdateListsStatusesAndCatalogWithoutHttpAvailabilityOrManualRefresh(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "reactive-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, DisasterDatabase::class.java, name).build()
        val dao = database.disasterDao()
        val unavailable = Retrofit.Builder().baseUrl("http://127.0.0.1:1/api/")
            .addConverterFactory(GsonConverterFactory.create()).build().create(DisasterApi::class.java)
        val repository = DisasterRepository(unavailable, dao, DisasterSyncScheduler(context))
        val list = DisastersViewModel(repository)
        val form = FormViewModel(repository)
        val showForm = mutableStateOf(false)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun find(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.isVisibleToUser && root.text?.contains(text) == true) return root
            for (index in 0 until root.childCount) find(root.getChild(index), text)?.let { return it }
            return null
        }
        fun node(text: String) = find(instrumentation.uiAutomation.rootInActiveWindow, text)
        suspend fun displayed(text: String) {
            val deadline = System.currentTimeMillis() + 10_000
            while (node(text) == null) {
                check(System.currentTimeMillis() < deadline) { "No se mostró el cambio reactivo: $text" }
                delay(100)
            }
        }
        suspend fun click(text: String) {
            displayed(text)
            val bounds = Rect()
            node(text)!!.getBoundsInScreen(bounds)
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, now, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                instrumentation.uiAutomation.injectInputEvent(event, true)
                event.recycle()
            }
        }
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        val navigation = rememberNavController()
                        if (showForm.value) FormScreen(navigation, form) else DisastersScreen(navigation, list)
                    }
                }
            }
            for ((index, status) in listOf(SyncStatus.PENDING, SyncStatus.SYNCED, SyncStatus.ERROR, SyncStatus.CONFLICT).withIndex()) {
                val id = "binding-$index"
                val title = "Reactivo $index"
                dao.insert(DisasterEntity(id, title, "Bogotá", 4, "Conservar offline", 4.7, -74.0))
                displayed(title)
                if (status == SyncStatus.SYNCED) dao.markSynced(id, 2_000_000_000)
                else if (status != SyncStatus.PENDING) dao.markError(id, status, "Revisión local")
                displayed(when (status) {
                    SyncStatus.SYNCED -> "Sincronizado"
                    SyncStatus.ERROR -> "No sincronizado: requiere revisión"
                    SyncStatus.CONFLICT -> "Conflicto: requiere revisión"
                    else -> "Guardado localmente, pendiente de sincronización"
                })
                if (status == SyncStatus.SYNCED) {
                    dao.cacheRemote(listOf(DisasterInfoDto(2_000_000_000, "Descarga reactiva", "Bogotá", 4,
                        "Nuevo estado", 4.7, -74.0, phase = "Recovery", version = 3)))
                    displayed("Descarga reactiva")
                }
                database.openHelper.writableDatabase.execSQL("DELETE FROM disasters WHERE clientRequestId = ?", arrayOf(id))
            }
            scenario.onActivity { showForm.value = true }
            displayed("Catálogo pendiente de obtener.")
            dao.cacheTypes(listOf(DisasterTypeEntity(12, "Catálogo reactivo", true)))
            click("Tipo de desastre (opcional)")
            click("Catálogo reactivo")
            instrumentation.runOnMainSync { assertEquals(12, form.formState.value.disasterTypeId) }
            dao.cacheTypes(listOf(DisasterTypeEntity(12, "Catálogo reactivo", false)))
            displayed("Catálogo reactivo (inactivo)")
        } finally {
            scenario.close()
            instrumentation.runOnMainSync { list.viewModelScope.cancel(); form.viewModelScope.cancel() }
            database.close()
            context.deleteDatabase(name)
        }
        Unit
    }
}
