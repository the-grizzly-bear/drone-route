package com.grizzly.droneroute

import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

object MapDownload {
    data class Layer(val id: String, val label: String, val url: (Int, Int, Int) -> String, val format: String,
                     val maxZoom: Int, val avgKb: Int, val terrain: Boolean = false)

    private fun usgs(svc: String) = { z: Int, x: Int, y: Int -> "https://basemap.nationalmap.gov/arcgis/rest/services/$svc/MapServer/tile/$z/$y/$x" }

    val LAYERS = listOf(
        Layer("usgs_imagery_topo", "USGS Imagery + Topo (satellite with contours)", usgs("USGSImageryTopo"), "jpg", 16, 35),
        Layer("usgs_topo", "USGS Topo (paper-map style)", usgs("USGSTopo"), "jpg", 16, 25),
        Layer("usgs_imagery", "USGS Imagery only", usgs("USGSImageryOnly"), "jpg", 16, 30),
        Layer("terrain", "3D terrain (elevation, for the 3D view)",
            { z, x, y -> "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$x/$y.png" }, "png", 12, 110, terrain = true),
    )

    data class Box(val s: Double, val w: Double, val n: Double, val e: Double) {
        companion object {
            fun around(lat: Double, lon: Double, radiusKm: Double): Box {
                val dLat = radiusKm / 110.574
                val dLon = radiusKm / (111.320 * cos(Math.toRadians(lat)))
                return Box(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
            }
        }
    }

    fun dir(): File = File(Environment.getExternalStorageDirectory(), "DroneRoute/maps")
    fun fileFor(l: Layer) = File(dir(), "${l.id}.mbtiles")

    private fun tx(lon: Double, z: Int) = ((lon + 180) / 360 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    private fun ty(lat: Double, z: Int) =
        ((1 - asinh(tan(Math.toRadians(lat))) / PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun tiles(b: Box, maxZ: Int): Sequence<Triple<Int, Int, Int>> = sequence {
        for (z in 0..maxZ) for (x in tx(b.w, z)..tx(b.e, z)) for (y in ty(b.n, z)..ty(b.s, z)) yield(Triple(z, x, y))
    }

    fun count(b: Box, maxZ: Int): Long = (0..maxZ).sumOf { z -> (tx(b.e, z) - tx(b.w, z) + 1L) * (ty(b.s, z) - ty(b.n, z) + 1L) }

    class Job(val layer: Layer, val box: Box, val maxZ: Int) {
        val done = AtomicInteger(); val failed = AtomicInteger(); val skipped = AtomicInteger(); val bytes = AtomicLong()
        val cancel = AtomicBoolean(false)
        val total = count(box, maxZ)
    }

    fun run(job: Job, onProgress: (Job) -> Unit): File {
        val f = fileFor(job.layer).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("create table if not exists metadata (name text, value text)")
        db.execSQL("create table if not exists tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
        db.execSQL("create unique index if not exists tile_index on tiles (zoom_level, tile_column, tile_row)")
        db.execSQL("delete from metadata")
        val meta = mutableListOf("name" to job.layer.label, "format" to job.layer.format, "type" to "baselayer",
            "minzoom" to "0", "maxzoom" to job.layer.maxZoom.toString())
        if (job.layer.terrain) meta += "encoding" to "terrarium"
        meta.forEach { (k, v) -> db.execSQL("insert into metadata values (?, ?)", arrayOf(k, v)) }

        val pool = Executors.newFixedThreadPool(4)
        val lock = Any()
        for ((z, x, y) in tiles(job.box, job.maxZ)) {
            if (job.cancel.get()) break
            val row = (1 shl z) - 1 - y
            val have = synchronized(lock) {
                db.rawQuery("select 1 from tiles where zoom_level=? and tile_column=? and tile_row=?",
                    arrayOf("$z", "$x", "$row")).use { it.moveToFirst() }
            }
            if (have) { job.skipped.incrementAndGet(); continue }
            pool.submit {
                if (job.cancel.get()) return@submit
                val data = fetch(job.layer.url(z, x, y))
                if (data == null) job.failed.incrementAndGet() else {
                    synchronized(lock) {
                        db.execSQL("insert or replace into tiles values (?, ?, ?, ?)", arrayOf<Any>(z, x, row, data))
                    }
                    job.bytes.addAndGet(data.size.toLong()); job.done.incrementAndGet()
                }
                onProgress(job)
            }

            while ((pool as java.util.concurrent.ThreadPoolExecutor).queue.size > 64 && !job.cancel.get()) Thread.sleep(20)
        }
        pool.shutdown(); pool.awaitTermination(10, TimeUnit.MINUTES)
        db.close()
        onProgress(job)
        return f
    }

    private fun fetch(url: String): ByteArray? {
        repeat(3) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", "DroneRoute/0.2 (SAR offline maps)")
                if (c.responseCode == 200) return c.inputStream.use { it.readBytes() }
                if (c.responseCode == 404) return null
            } catch (_: Exception) {}
            Thread.sleep(500L * (attempt + 1))
        }
        return null
    }
}
