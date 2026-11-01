package com.grizzly.droneroute

import android.app.Application

object DroneSyncImpl {
    const val available = false
    fun init(app: Application) {}
    fun status() = "Drone sync needs the controller build"
    fun sync(kind: SyncKind, progress: (String) -> Unit): SyncResult =
        SyncResult(emptyList(), 0, listOf("Drone sync is only in the controller build"))
}
