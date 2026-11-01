package com.grizzly.droneroute

import android.annotation.SuppressLint
import android.app.Activity
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object TrackHolder { var tracks: List<Track> = emptyList(); var colors: List<Int> = emptyList() }

class View3DActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var tiles: TileServer

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val store = Store(this)
        tiles = TileServer(this, store)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/tiles/") { path -> tiles.serve(path) }
            .addPathHandler("/data/") { path -> data(path, store) }
            .build()
        if (0 != (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)) WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) = loader.shouldInterceptRequest(r.url)

                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    if (r.url.scheme == "droneroute") { finish(); return true }
                    return false
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    android.util.Log.i("DroneRoute3D", "${m.messageLevel()} ${m.message()} @${m.sourceId()}:${m.lineNumber()}"); return true
                }
            }
        }

        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); addView(web) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION") v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins
        }
        setContentView(root)
        web.loadUrl("https://appassets.androidplatform.net/assets/view3d.html")
    }

    private fun data(path: String, store: Store): WebResourceResponse? = when (path) {
        "tracks.json" -> json(JSONArray().apply {
            TrackHolder.tracks.forEachIndexed { i, t ->
                put(JSONObject().apply {
                    put("name", t.name)
                    put("color", "#%06X".format(TrackHolder.colors.getOrElse(i) { 0xFF8A00 } and 0xFFFFFF))
                    t.home?.let { put("home", JSONArray(listOf(it.first, it.second))) }
                    put("pts", JSONArray().apply {
                        for (p in t.pts) put(JSONArray().apply {
                            put(p.ts); put(p.lat); put(p.lon)
                            put(p.h ?: JSONObject.NULL); put(p.abs ?: JSONObject.NULL); put(p.yaw ?: JSONObject.NULL)
                            put(p.gp ?: JSONObject.NULL); put(if (p.photo) 1 else 0)
                            put(p.spd ?: JSONObject.NULL); put(p.bat ?: JSONObject.NULL)
                        })
                    })
                })
            }
        }.toString())
        "config.json" -> json(JSONObject().apply {
            put("imgMaxZoom", tiles.imgMaxZoom())
            put("imgCredit", tiles.imgCredit())
            put("demMaxZoom", tiles.demMaxZoom())
            put("demSource", tiles.demSource())
        }.toString())
        else -> null
    }

    private fun json(s: String) = WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(s.toByteArray()))

    override fun onDestroy() { tiles.close(); web.destroy(); super.onDestroy() }
}

class TileServer(private val act: Activity, private val store: Store) {
    private val imgDbs by lazy { store.mbtiles.map(::File).filter { it.canRead() }.mapNotNull(::open) }
    private val demDbs by lazy { store.demTiles.map(::File).filter { it.canRead() }.mapNotNull(::open) }
    private val demCache = File(act.filesDir, "dem")
    private val imgCache = File(act.cacheDir, "img")

    private fun open(f: File) = try {
        SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    } catch (e: Exception) { null }

    private fun offlineImg() = store.mapMode == "offline" && imgDbs.isNotEmpty()
    fun imgMaxZoom() = if (offlineImg()) maxZoom(imgDbs) ?: 16 else if (store.mapMode == "osm") 19 else 16
    fun imgCredit() = if (offlineImg()) "Offline map" else if (store.mapMode == "osm") "© OpenStreetMap contributors" else "USGS"
    fun demMaxZoom() = maxZoom(demDbs) ?: 12
    fun demSource() = if (demDbs.isNotEmpty()) "offline" else "online"

    private fun maxZoom(dbs: List<SQLiteDatabase>) = dbs.mapNotNull { db ->
        try { db.rawQuery("select value from metadata where name='maxzoom'", null).use { if (it.moveToFirst()) it.getString(0).toIntOrNull() else null } }
        catch (e: Exception) { null }
    }.maxOrNull()

    fun serve(path: String): WebResourceResponse {
        val p = path.split('/')
        if (p.size != 4) return miss()
        val (kind, zs, xs, ys) = p
        val z = zs.toIntOrNull() ?: return miss(); val x = xs.toIntOrNull() ?: return miss(); val y = ys.toIntOrNull() ?: return miss()
        val bytes = when (kind) {
            "img" -> if (offlineImg()) fromDb(imgDbs, z, x, y) else online(imgCache, store.mapMode, z, x, y, imgUrl(z, x, y))
            "dem" -> fromDb(demDbs, z, x, y) ?: online(demCache, "terrarium", z, x, y,
                "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$x/$y.png")
            else -> null
        } ?: return miss()
        val mime = if (bytes.size > 3 && bytes[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"
        return WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "max-age=86400"), ByteArrayInputStream(bytes))
    }

    private fun imgUrl(z: Int, x: Int, y: Int) = if (store.mapMode == "osm") "https://tile.openstreetmap.org/$z/$x/$y.png"
        else "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/$z/$y/$x"

    private fun fromDb(dbs: List<SQLiteDatabase>, z: Int, x: Int, y: Int): ByteArray? {
        val row = (1 shl z) - 1 - y
        for (db in dbs) try {
            db.rawQuery("select tile_data from tiles where zoom_level=? and tile_column=? and tile_row=?",
                arrayOf(z.toString(), x.toString(), row.toString())).use { c -> if (c.moveToFirst()) return c.getBlob(0) }
        } catch (_: Exception) {}
        return null
    }

    private fun online(dir: File, set: String, z: Int, x: Int, y: Int, url: String): ByteArray? {
        val f = File(dir, "$set/$z/$x/$y")
        if (f.exists()) return f.readBytes()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "DroneRoute/0.1 (${act.packageName})")
            if (c.responseCode != 200) return null
            c.inputStream.use { it.readBytes() }.also { f.parentFile?.mkdirs(); f.writeBytes(it) }
        } catch (e: Exception) { null }
    }

    private fun miss() = WebResourceResponse("text/plain", null, 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    fun close() { (imgDbs + demDbs).forEach { runCatching { it.close() } } }
}
