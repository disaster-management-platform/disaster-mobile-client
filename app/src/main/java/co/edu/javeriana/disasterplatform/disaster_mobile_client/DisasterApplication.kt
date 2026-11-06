package co.edu.javeriana.disasterplatform.disaster_mobile_client

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync.DisasterSyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DisasterApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: DisasterSyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Recupera pendientes si el proceso terminó entre el guardado y el enqueue.
        syncScheduler.enqueue()
    }
}
