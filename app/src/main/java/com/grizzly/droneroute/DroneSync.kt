package com.grizzly.droneroute

import java.io.File

data class SyncResult(val copied: List<String>, val skipped: Int, val errors: List<String>)

enum class SyncKind(val label: String, val exts: Set<String>) {
    SRT("SRTs", setOf("srt")),
    VIDEO("videos", setOf("mp4", "mov")),
}

fun syncDir(): File = File(android.os.Environment.getExternalStorageDirectory(), "DJI/DroneRoute/drone")
