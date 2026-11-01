package com.grizzly.droneroute

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MapActivity : Activity() {
    private lateinit var store: Store
    private lateinit var map: MapView
    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var seek: SeekBar
    private val tracks = mutableListOf<Track>()
    private val lines = mutableListOf<Polyline>()
    private var active = 0
    private var drone: Marker? = null
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val day = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        Configuration.getInstance().apply {
            userAgentValue = "DroneRoute/0.1 ($packageName)"
            osmdroidBasePath = File(getExternalFilesDir(null), "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }

        val root = FrameLayout(this)
        map = MapView(this).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0; maxZoomLevel = 20.0
        }
        setBaseMap()
        map.overlays += ScaleBarOverlay(map).apply { setAlignBottom(false); setScaleBarOffset(dp(10), dp(70)) }
        root.addView(map)

        title = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xCC15181C.toInt()); setPadding(dp(12), dp(8), dp(12), dp(8)); textSize = 13f
            text = "Loading…"
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xCC15181C.toInt()); gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply { text = "‹ Back"; isAllCaps = false; textSize = 15f; setOnClickListener { finish() } })
        title.setBackgroundColor(0)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xDD15181C.toInt()); setPadding(dp(8), dp(4), dp(8), dp(6))
        }
        info = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.MONOSPACE }
        info.setOnClickListener { copyCoords() }
        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) = scrub(p, center = false)
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = btns.addView(Button(this).apply { text = t; isAllCaps = false; textSize = 12f; setOnClickListener { f() } },
            LinearLayout.LayoutParams(0, -2, 1f))
        btn("Fit") { fit() }
        btn("3D") { open3d() }
        btn("Track") { chooseActive() }
        btn("Map") { chooseBase() }
        btn("KML") { export("kml") }
        btn("GPX") { export("gpx") }
        bottom.addView(info); bottom.addView(seek); bottom.addView(btns)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            header.setPadding(dp(4), ins.systemWindowInsetTop, 0, 0); title.setPadding(dp(8), dp(6), dp(12), dp(8))
            @Suppress("DEPRECATION")
            bottom.setPadding(dp(8), dp(4), dp(8), ins.systemWindowInsetBottom + dp(6))
            ins
        }
        setContentView(root)

        load(intent.getStringArrayListExtra("files").orEmpty().map(::File))
    }

    private val usgs = object : OnlineTileSourceBase("USGS-ImageryTopo", 0, 16, 256, "",
        arrayOf("https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/")) {
        override fun getTileURLString(i: Long) =
            baseUrl + MapTileIndex.getZoom(i) + "/" + MapTileIndex.getY(i) + "/" + MapTileIndex.getX(i)
    }

    private fun setBaseMap() {
        val mb = store.mbtiles.map(::File).filter { it.canRead() }
        when {
            store.mapMode == "offline" && mb.isNotEmpty() -> {
                val p = OfflineTileProvider(SimpleRegisterReceiver(this), mb.toTypedArray())
                map.setTileProvider(p)

                map.setTileSource(XYTileSource("offline", 0, 16, 256, ".jpg", arrayOf()))
                map.setUseDataConnection(false)
            }
            else -> {
                map.setTileProvider(MapTileProviderBasic(applicationContext))
                map.setTileSource(if (store.mapMode == "osm") TileSourceFactory.MAPNIK else usgs)
                map.setUseDataConnection(true)
            }
        }
        map.invalidate()
    }

    private fun chooseBase() {
        val modes = arrayOf("offline", "usgs", "osm")
        val names = arrayOf("Offline MBTiles (${store.mbtiles.size} files)", "USGS Imagery Topo (online)", "OpenStreetMap (online)")
        AlertDialog.Builder(this).setTitle("Base map").setSingleChoiceItems(names, modes.indexOf(store.mapMode)) { d, w ->
            store.mapMode = modes[w]; setBaseMap(); d.dismiss()
        }.show()
    }

    private fun load(files: List<File>) = thread {
        val errors = mutableListOf<String>()
        for (f in files) {
            try {
                val t = if (f.extension.equals("srt", true)) Srt.parse(f) else Loader.loadLog(store, f)
                if (t.pts.isEmpty()) errors += "${f.name}: no GPS points" else tracks += t
            } catch (e: Exception) {
                errors += "${f.name}: ${e.message}"
            }
        }
        tracks.sortBy { it.startTs }
        runOnUiThread {
            draw()
            if (errors.isNotEmpty()) AlertDialog.Builder(this).setTitle("Some files could not be mapped")
                .setMessage(errors.joinToString("\n\n")).setPositiveButton("OK", null).show()
        }
    }

    private val palette = intArrayOf(0xFFFF8A00.toInt(), 0xFF00C2FF.toInt(), 0xFFFF3B6B.toInt(), 0xFF7CFF4F.toInt(),
        0xFFB36BFF.toInt(), 0xFFFFE14F.toInt(), 0xFF4FFFD2.toInt(), 0xFFFF6BE1.toInt())

    private fun draw() {
        if (tracks.isEmpty()) { title.text = "Nothing to show"; return }
        tracks.forEachIndexed { i, t ->
            val c = palette[i % palette.size]
            val pl = Polyline(map).apply {
                setPoints(t.pts.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = c; outlinePaint.strokeWidth = dp(4).toFloat()
                outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.strokeJoin = Paint.Join.ROUND
                setOnClickListener { _, _, pos -> setActive(i); seekTo(pos); true }
            }
            lines += pl
            map.overlays += pl
            t.pts.filter { it.photo }.take(400).forEach { p ->
                map.overlays += Marker(map).apply {
                    position = GeoPoint(p.lat, p.lon); icon = dot(Color.WHITE, c, 5); setAnchor(.5f, .5f)
                    setOnMarkerClickListener { _, _ -> setActive(i); seekTo(position); true }
                }
            }
            marker(t.pts.first(), dot(c, Color.WHITE, 8), "Start ${t.name}")
            marker(t.pts.last(), square(c), "End ${t.name}")
            t.home?.let { (la, lo) -> map.overlays += Marker(map).apply {
                position = GeoPoint(la, lo); icon = label("H", c); setAnchor(.5f, .5f); title = "Home ${t.name}" } }
        }
        drone = Marker(map).apply { icon = arrow(); setAnchor(.5f, .5f); isFlat = true; setInfoWindow(null) }
        map.overlays += drone
        setActive(0)
        map.post { fit() }
    }

    private fun marker(p: TP, ic: android.graphics.drawable.Drawable, t: String) {
        map.overlays += Marker(map).apply { position = GeoPoint(p.lat, p.lon); icon = ic; setAnchor(.5f, .5f); title = t }
    }

    private fun setActive(i: Int) {
        active = i
        lines.forEachIndexed { k, l -> l.outlinePaint.alpha = if (k == i || lines.size == 1) 255 else 110 }
        val t = tracks[i]
        seek.max = t.pts.size - 1
        val sum = "%s  ·  %s  ·  %s  ·  %.1f km%s".format(
            day.format(Date(t.startTs)), if (t.source == "log") "flight log" else "video SRT",
            dur(t.durationS), t.distanceM / 1000, t.maxH?.let { "  ·  max %.0f m".format(it) } ?: "")
        title.text = (if (tracks.size > 1) "[${i + 1}/${tracks.size}] " else "") + (t.aircraft?.takeIf { it.isNotBlank() }?.let { "$it  ·  " } ?: "") +
            t.name + "\n" + sum
        seek.progress = 0; scrub(0, center = false)
    }

    private fun chooseActive() {
        if (tracks.size < 2) return
        AlertDialog.Builder(this).setTitle("Active track")
            .setItems(tracks.mapIndexed { i, t -> "${i + 1}. ${day.format(Date(t.startTs))}  ${t.name}" }.toTypedArray()) { _, w ->
                setActive(w); fitTrack(tracks[w]) }.show()
    }

    private fun seekTo(g: GeoPoint) {
        val t = tracks[active]
        val i = t.pts.indices.minBy { dist(t.pts[it].lat, t.pts[it].lon, g.latitude, g.longitude) }
        seek.progress = i
    }

    private fun scrub(i: Int, center: Boolean) {
        val t = tracks.getOrNull(active) ?: return
        val p = t.pts.getOrNull(i) ?: return
        drone?.position = GeoPoint(p.lat, p.lon)

        drone?.rotation = -(p.yaw ?: p.gy ?: 0.0).toFloat()
        val el = (p.ts - t.startTs) / 1000.0
        val sb = StringBuilder()
        sb.append("%s  +%s".format(clock.format(Date(p.ts)), dur(el)))
        p.h?.let { sb.append("   H %.0f m".format(it)) }
        p.abs?.let { sb.append("   MSL %.0f m".format(it)) }
        p.spd?.let { sb.append("   %.1f m/s".format(it)) }
        p.bat?.let { if (it > 0) sb.append("   bat %d%%".format(it)) }
        p.gp?.let { sb.append("   gimbal %.0f°".format(it)) }
        sb.append("\n%.6f, %.6f   (tap to copy)".format(p.lat, p.lon))
        t.home?.let { (la, lo) -> sb.append("   %.0f m from home".format(dist(la, lo, p.lat, p.lon))) }
        info.text = sb
        if (center) map.controller.animateTo(drone?.position)
        map.invalidate()
    }

    private fun copyCoords() {
        val p = tracks.getOrNull(active)?.pts?.getOrNull(seek.progress) ?: return
        val s = "%.6f, %.6f".format(p.lat, p.lon)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("coords", s))
        Toast.makeText(this, "Copied $s", Toast.LENGTH_SHORT).show()
    }

    private fun fit() = fitPts(tracks.flatMap { it.pts })
    private fun fitTrack(t: Track) = fitPts(t.pts)
    private fun fitPts(pts: List<TP>) {
        if (pts.isEmpty()) return
        val bb = BoundingBox(pts.maxOf { it.lat }, pts.maxOf { it.lon }, pts.minOf { it.lat }, pts.minOf { it.lon })
        if (bb.latitudeSpan < 1e-5 && bb.longitudeSpan < 1e-5) { map.controller.setZoom(17.0); map.controller.setCenter(bb.centerWithDateLine); return }
        map.zoomToBoundingBox(bb.increaseByScale(1.3f), false)
    }

    private fun open3d() {
        if (tracks.isEmpty()) return
        TrackHolder.tracks = tracks.toList()
        TrackHolder.colors = tracks.indices.map { palette[it % palette.size] }
        startActivity(Intent(this, View3DActivity::class.java))
    }

    private fun export(kind: String) {
        if (tracks.isEmpty()) return
        val body = if (kind == "kml") Export.kml(tracks) else Export.gpx(tracks)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(tracks.first().startTs))
        val name = "route_${stamp}_${tracks.size}trk.$kind"

        val pub = File(android.os.Environment.getExternalStorageDirectory(), "DroneRoute")
        val f = try { pub.mkdirs(); File(pub, name).apply { writeText(body) } } catch (e: Exception) {
            File(getExternalFilesDir("export"), name).apply { parentFile?.mkdirs(); writeText(body) }
        }
        Toast.makeText(this, "Saved ${f.absolutePath}", Toast.LENGTH_LONG).show()
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = if (kind == "kml") "application/vnd.google-earth.kml+xml" else "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share $name"))
        } catch (_: Exception) {  }
    }

    private fun bmp(sz: Int, f: (Canvas, Paint) -> Unit): BitmapDrawable {
        val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        f(Canvas(b), Paint(Paint.ANTI_ALIAS_FLAG))
        return BitmapDrawable(resources, b)
    }
    private fun dot(fill: Int, ring: Int, r: Int) = bmp(dp(r * 2 + 4)) { c, p ->
        val cx = c.width / 2f
        p.color = ring; c.drawCircle(cx, cx, dp(r + 2).toFloat(), p)
        p.color = fill; c.drawCircle(cx, cx, dp(r).toFloat(), p)
    }
    private fun square(fill: Int) = bmp(dp(18)) { c, p ->
        p.color = Color.WHITE; c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), p)
        p.color = fill; val m = dp(3).toFloat(); c.drawRect(m, m, c.width - m, c.height - m, p)
    }
    private fun label(s: String, fill: Int) = bmp(dp(26)) { c, p ->
        val cx = c.width / 2f
        p.color = Color.WHITE; c.drawCircle(cx, cx, cx, p)
        p.color = fill; c.drawCircle(cx, cx, cx - dp(2), p)
        p.color = Color.BLACK; p.textSize = dp(15).toFloat(); p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
        c.drawText(s, cx, cx + dp(5), p)
    }
    private fun arrow() = bmp(dp(34)) { c, p ->
        val w = c.width.toFloat()
        val path = Path().apply { moveTo(w / 2, dp(2).toFloat()); lineTo(w - dp(5), w - dp(4)); lineTo(w / 2, w * .7f); lineTo(dp(5).toFloat(), w - dp(4)); close() }
        p.color = Color.BLACK; p.style = Paint.Style.STROKE; p.strokeWidth = dp(3).toFloat(); c.drawPath(path, p)
        p.color = Color.WHITE; p.style = Paint.Style.FILL; c.drawPath(path, p)
    }

    private fun dur(s: Double): String { val t = s.toLong(); return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() {
        super.onPause(); map.onPause()
        runCatching { map.boundingBox.let { store.lastView = MapDownload.Box(it.latSouth, it.lonWest, it.latNorth, it.lonEast) } }
    }
}
