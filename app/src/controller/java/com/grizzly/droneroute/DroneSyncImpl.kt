package com.grizzly.droneroute

import android.app.Application
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.*
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.network.DJINetworkManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object DroneSyncImpl {
    const val available = true
    @Volatile private var initDone = false
    @Volatile private var registered = false
    @Volatile private var connected = false
    @Volatile private var lastError: String? = null

    fun init(app: Application) {
        try {
            SDKManager.getInstance().init(app, object : SDKManagerCallback {
                override fun onRegisterSuccess() { registered = true; lastError = null }
                override fun onRegisterFailure(error: IDJIError) { registered = false; lastError = "DJI SDK key rejected: ${error.description()}" }
                override fun onProductDisconnect(productId: Int) { connected = false }
                override fun onProductConnect(productId: Int) { connected = true }
                override fun onProductChanged(productId: Int) {}
                override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                    if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) { initDone = true; SDKManager.getInstance().registerApp() }
                }
                override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
            })
            DJINetworkManager.getInstance().addNetworkStatusListener { ok ->
                if (initDone && ok && !SDKManager.getInstance().isRegistered) SDKManager.getInstance().registerApp()
            }
        } catch (t: Throwable) {
            lastError = "DJI SDK failed to start: ${t.message}"
        }
    }

    fun status(): String = when {
        lastError != null -> lastError!!
        !initDone -> "DJI SDK starting…"
        !registered -> "DJI SDK not registered yet (needs internet the first time)"
        !connected -> "Drone not connected. Close Pilot 2, power on the drone, wait a few seconds."
        else -> "Drone connected"
    }

    fun sync(kind: SyncKind, progress: (String) -> Unit): SyncResult {
        if (!(initDone && registered && connected)) return SyncResult(emptyList(), 0, listOf(status()))
        val mm = MediaDataCenter.getInstance().mediaManager
        val errors = mutableListOf<String>(); val copied = mutableListOf<String>(); var skipped = 0
        try {
            val all = listFiles(mm, progress, errors)
            val kinds = all.groupingBy { it.fileName.substringAfterLast('.', "?").uppercase() }.eachCount()
            if (all.isNotEmpty()) errors.removeAll { true }
            val summary = if (kinds.isEmpty()) "none" else kinds.entries.joinToString { "${it.key} x${it.value}" }
            val files = all.filter { it.fileName.substringAfterLast('.', "").lowercase() in kind.exts }
            if (files.isEmpty()) errors += "No ${kind.label} found. Files the drone listed: $summary."
            files.forEachIndexed { i, mf ->
                val dir = File(syncDir(), dayOf(mf.fileName)).apply { mkdirs() }
                val out = File(dir, mf.fileName)
                if (out.exists() && out.length() == mf.fileSize) { skipped++; return@forEachIndexed }
                progress("Copying ${i + 1}/${files.size}: ${mf.fileName}")
                val err = download(mf, out) { cur, tot -> progress("Copying ${i + 1}/${files.size}: ${mf.fileName}  ${pct(cur, tot)}") }
                if (err == null) copied += mf.fileName else errors += "${mf.fileName}: $err"
            }
        } catch (t: Throwable) {
            errors += "Sync stopped: ${t.message}"
        } finally {
            runCatching { mm.disable(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {}; override fun onFailure(error: IDJIError) {} }) }
        }
        return SyncResult(copied, skipped, errors)
    }

    private fun listFiles(mm: dji.v5.manager.interfaces.IMediaManager, progress: (String) -> Unit, errors: MutableList<String>): List<MediaFile> {
        val tries = listOf<Pair<String, MediaFileListDataSource?>>(
            "SD card + main camera" to MediaFileListDataSource.Builder().setIndexType(ComponentIndexType.LEFT_OR_MAIN)
                .setLocation(dji.sdk.keyvalue.value.camera.CameraStorageLocation.SDCARD).build(),
            "main camera" to MediaFileListDataSource.Builder().setIndexType(ComponentIndexType.LEFT_OR_MAIN).build(),
            "SD card" to MediaFileListDataSource.Builder().setLocation(dji.sdk.keyvalue.value.camera.CameraStorageLocation.SDCARD).build(),
            "DJI default" to null,
        )
        val counts = listOf(-1, 100)
        for ((name, src) in tries) for (count in counts) {
            val tag = "$name, ${if (count < 0) "all" else "$count"}"
            progress("Reading the drone's file list ($tag)…")
            val before = errors.size
            try {
                val ready = CountDownLatch(1)
                val l = MediaFileListStateListener { st -> if (st == MediaFileListState.UP_TO_DATE) ready.countDown() }
                mm.addMediaFileListStateListener(l)
                if (src != null) mm.setMediaFileDataSource(src)

                if (!awaitOk("playback mode [$tag]", errors) { cb -> mm.enable(cb) }) { mm.removeMediaFileListStateListener(l); continue }

                val t0 = System.currentTimeMillis()
                while (mm.mediaFileListState != MediaFileListState.IDLE && mm.mediaFileListState != MediaFileListState.UP_TO_DATE
                    && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(200)
                if (awaitOk("read file list [$tag] (state was ${mm.mediaFileListState})", errors) { cb ->
                        mm.pullMediaFileListFromCamera(PullMediaFileListParam.Builder().mediaFileIndex(-1).count(count).build(), cb) }) {
                    ready.await(45, TimeUnit.SECONDS)
                }
                mm.removeMediaFileListStateListener(l)
                val list = mm.mediaFileListData?.data.orEmpty()
                if (list.isNotEmpty()) return list
                if (errors.size == before) errors += "[$tag]: list empty (state ${mm.mediaFileListState})"
            } catch (t: Throwable) {
                errors += "[$tag]: ${t.message}"
            }
            awaitOk("exit playback [$tag]", mutableListOf()) { cb -> mm.disable(cb) }
            Thread.sleep(800)
        }
        return emptyList()
    }

    private fun download(mf: MediaFile, out: File, onProgress: (Long, Long) -> Unit): String? {
        val tmp = File(out.path + ".part").apply { delete() }
        val done = CountDownLatch(1); var err: String? = null
        val os = tmp.outputStream().buffered()
        mf.pullOriginalMediaFileFromCamera(0, object : MediaFileDownloadListener {
            override fun onStart() {}
            override fun onProgress(total: Long, current: Long) = onProgress(current, total)
            override fun onRealtimeDataUpdate(data: ByteArray, position: Long) { os.write(data) }
            override fun onFinish() { done.countDown() }
            override fun onFailure(error: IDJIError?) { err = error?.description() ?: "download failed"; done.countDown() }
        })

        if (!done.await(if (out.extension.equals("srt", true)) 120 else 3600, TimeUnit.SECONDS)) err = "timed out"
        os.close()
        if (err == null && tmp.length() > 0) tmp.renameTo(out) else tmp.delete()
        return err
    }

    private fun awaitOk(what: String, errors: MutableList<String>, call: (CommonCallbacks.CompletionCallback) -> Unit): Boolean {
        val l = CountDownLatch(1); var ok = false
        call(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { ok = true; l.countDown() }
            override fun onFailure(error: IDJIError) { errors += "$what: ${error.description()} [${error.errorType()}/${error.errorCode()}]"; l.countDown() }
        })
        if (!l.await(20, TimeUnit.SECONDS)) { errors += "$what: no answer from the drone (20 s)"; return false }
        return ok
    }

    private fun await(what: String, errors: MutableList<String>, call: (CommonCallbacks.CompletionCallback) -> Unit) {
        val l = CountDownLatch(1)
        call(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { l.countDown() }
            override fun onFailure(error: IDJIError) { errors += "$what: ${error.description()} [${error.errorType()}/${error.errorCode()}]"; l.countDown() }
        })
        l.await(30, TimeUnit.SECONDS)
    }

    private fun dayOf(name: String) = Regex("""DJI_(\d{4})(\d{2})(\d{2})""").find(name)?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" } ?: "unsorted"
    private fun pct(c: Long, t: Long) = if (t > 0) "${c * 100 / t}%" else ""
}
