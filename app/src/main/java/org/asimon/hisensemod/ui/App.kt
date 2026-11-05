package org.asimon.hisensemod.ui

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

/** Receives the Vector service binder so the UI can write remote preferences. */
class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        App.service = service
        listeners.forEach { it(service) }
    }

    override fun onServiceDied(service: XposedService) {
        App.service = null
        listeners.forEach { it(null) }
    }

    companion object {
        @Volatile
        var service: XposedService? = null
            private set
        val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()
    }
}
