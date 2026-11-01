package com.grizzly.droneroute

import android.app.Application
import android.content.Context

class App : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        DroneSyncImpl.init(this)
    }
}
