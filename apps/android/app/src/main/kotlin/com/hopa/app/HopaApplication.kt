package com.hopa.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.hopa.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HopaApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // An approval on disk means the process died while waiting: deny it
        // rather than resurrect a question whose turn is gone.
        scope.launch {
            runCatching { container.pendingApprovals.clearAll() }
        }
        // A fresh install has no FCM token until something asks for one, and the
        // gateway needs it in device.hello before it can wake this phone.
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            container.appScope.launch { container.backend.loadPushToken() }
        }
    }
}
