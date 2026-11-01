package com.grizzly.droneroute

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var btnMap: Button
    private var droneStatus: TextView? = null
    private var items: List<Item> = emptyList()
    private val checked = mutableSetOf<String>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val hm = SimpleDateFormat("HH:mm", Locale.US)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); fitsSystemWindows = true }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(4)) }
        bar.addView(TextView(this).apply {
            text = "Drone Route  " + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault(""); textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn("Scan") { scan() })
        bar.addView(btn("Open file") { pick() })
        bar.addView(btn("Send to CalTopo") { sendToCalTopo() })
        bar.addView(btn("Settings") { settings() })
        root.addView(bar)

        status = TextView(this).apply { setTextColor(DIM); setPadding(dp(12), 0, dp(12), dp(4)) }
        root.addView(status)

        if (DroneSyncImpl.available) {
            val drone = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), dp(4)); gravity = Gravity.CENTER_VERTICAL }
            droneStatus = TextView(this).apply { setTextColor(DIM); text = "Drone: " + DroneSyncImpl.status() }
            drone.addView(droneStatus, LinearLayout.LayoutParams(0, -2, 1f))
            drone.addView(btn("Sync SRTs") { syncDrone(SyncKind.SRT) })
            drone.addView(btn("Sync videos") { syncDrone(SyncKind.VIDEO) })
            root.addView(drone)
        }

        list = ListView(this).apply { dividerHeight = 1 }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        btnMap = btn("Map selected") { open(items.filter { it.file.path in checked }.map { it.file }) }
        btnMap.isEnabled = false
        root.addView(btnMap, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(4), dp(8), dp(8)) })
        setContentView(root)

        if (!hasStorage()) askStorage() else scan()
        handleShare(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleShare(i) }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val code = CalTopo.extractCode(i.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        if (code == null) { toast("That link has no CalTopo code in it"); return }
        activateCalTopo(code)
    }

    private fun activateCalTopo(code: String) {
        status.text = "Signing in to CalTopo…"
        thread {
            val r = runCatching { CalTopo.activate(code) }
            runOnUiThread {
                r.onSuccess { (acct, cred, key) ->
                    store.ctAcct = acct; store.ctCred = cred; store.ctKey = key
                    status.text = "CalTopo: signed in (account $acct)"
                    AlertDialog.Builder(this).setTitle("CalTopo connected")
                        .setMessage("This device can now send tracks to your personal CalTopo account ($acct), one map per day.")
                        .setPositiveButton("OK", null).show()
                }.onFailure { status.text = "CalTopo sign-in failed: ${it.message}" }
            }
        }
    }

    private fun calTopoSignIn() {
        AlertDialog.Builder(this).setTitle("Sign in to CalTopo")
            .setMessage("1. Your browser opens CalTopo (sign in there if asked).\n" +
                "2. Name the device (e.g. M3T-controller), tick the box, tap Sync Account.\n" +
                "3. You'll land on a 'lost' page. Share it to Drone Route (⋮ → Share), or type the code=XXXXXXXX from its address here.")
            .setPositiveButton("Open CalTopo") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CalTopo.ACTIVATE_PAGE)))
            }
            .setNeutralButton("Enter code") { _, _ ->
                val e = EditText(this).apply { hint = "8-character code or the page address" }
                AlertDialog.Builder(this).setTitle("CalTopo code").setView(e)
                    .setPositiveButton("Sign in") { _, _ -> CalTopo.extractCode(e.text.toString())?.let(::activateCalTopo) ?: toast("No code found") }
                    .setNegativeButton("Cancel", null).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun sendToCalTopo() {
        val ct = store.calTopo ?: run { calTopoSignIn(); return }
        if (items.isEmpty()) { toast("No flights found. Tap Scan."); return }
        val ticked = items.filter { it.file.path in checked }
        if (ticked.isNotEmpty()) { confirmSend(ct, ticked); return }
        val today = dayFmt.format(Date())
        val labels = items.map { "${fmt.format(Date(it.time))}  ${if (it.kind == "log") "LOG" else "VIDEO"}${if (store.isSent(it.file)) "  ✓ sent" else ""}\n${it.file.name}" }
        val sel = BooleanArray(items.size) { dayFmt.format(Date(items[it].time)) == today && !store.isSent(items[it].file) }
        AlertDialog.Builder(this)
            .setTitle("Pick tracks to send")
            .setMultiChoiceItems(labels.toTypedArray(), sel) { _, w, c -> sel[w] = c }
            .setPositiveButton("Next") { _, _ ->
                val chosen = items.filterIndexed { i, _ -> sel[i] }
                if (chosen.isEmpty()) toast("Nothing selected") else confirmSend(ct, chosen)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmSend(ct: CalTopo, chosen: List<Item>) {
        val byDay = chosen.groupBy { dayFmt.format(Date(it.time)) }.toSortedMap()
        val msg = byDay.entries.joinToString("\n\n") { (d, g) ->
            "${g.size} track${if (g.size > 1) "s" else ""} → map \"UAS $d\"\n" + g.joinToString("\n") { "  • " + it.file.name + if (store.isSent(it.file)) " (already sent)" else "" }
        }
        AlertDialog.Builder(this).setTitle("Send ${chosen.size} to CalTopo?").setMessage(msg)
            .setPositiveButton("Send") { _, _ -> doSend(ct, chosen) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doSend(ct: CalTopo, chosen: List<Item>) {
        val prog = TextView(this).apply { setPadding(dp(20), dp(16), dp(20), dp(8)) ; text = "Preparing ${chosen.size} flights…" }
        val dlg = AlertDialog.Builder(this).setTitle("Sending to CalTopo").setView(prog).setCancelable(false).show()
        thread {
            val errors = mutableListOf<String>()
            val tracks = mutableListOf<Pair<Item, Track>>()
            chosen.forEachIndexed { n, it ->
                runOnUiThread { prog.text = "Reading ${n + 1}/${chosen.size}: ${it.file.name}" }
                runCatching { Loader.load(store, it.file) }
                    .onSuccess { t -> if (t.pts.size >= 2) tracks += it to t else errors += "${it.file.name}: no GPS track" }
                    .onFailure { e -> errors += "${it.file.name}: ${e.message}" }
            }
            val results = mutableListOf<CalTopo.SendResult>()
            tracks.groupBy { dayFmt.format(Date(it.second.startTs)) }.toSortedMap().forEach { (day, g) ->
                runOnUiThread { prog.text = "Uploading ${g.size} tracks to UAS $day…" }
                runCatching { ct.sendDay(day, g.map { it.second }) }
                    .onSuccess { r -> results += r; store.markSent(g.map { it.first.file }) }
                    .onFailure { e -> errors += "UAS $day: ${e.message}" }
            }
            runOnUiThread {
                dlg.dismiss()
                list.adapter = Adapter()
                showSendResult(results, errors)
            }
        }
    }

    private fun showSendResult(results: List<CalTopo.SendResult>, errors: List<String>) {
        val msg = StringBuilder()
        results.forEach { r ->
            msg.append("${r.title}  ${r.url}\n")
            r.added.forEach { msg.append("  + added: $it\n") }
            r.already.forEach { msg.append("  = already there: $it\n") }
            msg.append("\n")
        }
        if (errors.isNotEmpty()) msg.append("Problems:\n").append(errors.joinToString("\n"))
        val b = AlertDialog.Builder(this).setTitle(if (results.isEmpty()) "Nothing sent" else "Sent to CalTopo").setMessage(msg.toString().trim())
            .setPositiveButton("OK", null)
        if (results.isNotEmpty()) {
            val links = results.joinToString("\n") { "${it.title}: ${it.url}" }
            b.setNeutralButton("Share link") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Drone tracks\n$links") }, "Share map link"))
            }
        }
        b.show()
    }

    private fun syncDrone(kind: SyncKind) {
        droneStatus?.text = "Drone: " + DroneSyncImpl.status()
        val go = {
            val prog = TextView(this).apply { setPadding(dp(20), dp(16), dp(20), dp(8)); text = "Connecting to the drone…" }
            val dlg = AlertDialog.Builder(this).setTitle("Sync ${kind.label} from drone").setView(prog).setCancelable(false).show()
            thread {
                val r = DroneSyncImpl.sync(kind) { m -> runOnUiThread { prog.text = m } }
                runOnUiThread {
                    dlg.dismiss()
                    val msg = buildString {
                        append("${r.copied.size} copied, ${r.skipped} already on the controller.")
                        if (r.copied.isNotEmpty()) append("\n\n" + r.copied.joinToString("\n"))
                        if (r.errors.isNotEmpty()) append("\n\nProblems:\n" + r.errors.joinToString("\n"))
                        append("\n\nSaved in ${syncDir().path}")
                    }
                    AlertDialog.Builder(this).setTitle("Sync ${kind.label}").setMessage(msg).setPositiveButton("OK", null).show()
                    droneStatus?.text = "Drone: " + DroneSyncImpl.status()
                    if (r.copied.isNotEmpty()) scan()
                }
            }
        }
        val note = "Close Pilot 2 first (only one app can talk to the drone). Drone powered on."
        if (kind == SyncKind.VIDEO)
            AlertDialog.Builder(this).setTitle("Sync videos?")
                .setMessage("$note\n\nVideos are large; over the radio link this can take a long time and fills controller storage.")
                .setPositiveButton("Sync videos") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
        else
            AlertDialog.Builder(this).setTitle("Sync SRTs?").setMessage(note)
                .setPositiveButton("Sync") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
    }

    private fun downloadMaps() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        fun label(t: String) = box.addView(TextView(this).apply { text = t; setPadding(0, dp(10), 0, 0); typeface = Typeface.DEFAULT_BOLD })
        val last = store.lastView
        label("Area")
        val rg = RadioGroup(this)
        val rLast = RadioButton(this).apply { id = 1; text = if (last != null) "Last area shown on the map" else "Last area shown on the map (open a flight's map first)"; isEnabled = last != null }
        val rPoint = RadioButton(this).apply { id = 2; text = "Around a point" }
        rg.addView(rLast); rg.addView(rPoint); rg.check(if (last != null) 1 else 2)
        box.addView(rg)
        val cLat = last?.let { (it.s + it.n) / 2 } ?: 38.85; val cLon = last?.let { (it.w + it.e) / 2 } ?: -106.13
        val pt = EditText(this).apply { setText("%.5f, %.5f".format(cLat, cLon)); hint = "lat, lon"; setSingleLine() }
        val rad = EditText(this).apply { setText("5"); hint = "radius km"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val prow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prow.addView(pt, LinearLayout.LayoutParams(0, -2, 2f)); prow.addView(TextView(this).apply { text = "  radius km " }); prow.addView(rad, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(prow)
        label("Layers")
        val checks = MapDownload.LAYERS.map { l -> CheckBox(this).apply { text = l.label; isChecked = l.id == "usgs_imagery_topo" || l.terrain } }
        checks.forEach { box.addView(it) }
        box.addView(TextView(this).apply { text = "OpenStreetMap: view-only (cached as you look). OSM's servers don't allow bulk downloads."; setTextColor(DIM); textSize = 12f })
        label("Map detail")
        val zooms = listOf(14 to "Normal (z14)", 15 to "High (z15)", 16 to "Max (z16, 4× more)")
        val zg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        zooms.forEach { (z, t) -> zg.addView(RadioButton(this).apply { id = 100 + z; text = t }) }
        zg.check(115); box.addView(zg)
        val est = TextView(this).apply { setPadding(0, dp(10), 0, 0) }
        box.addView(est)

        fun area(): MapDownload.Box? = if (rg.checkedRadioButtonId == 1) last else {
            val p = pt.text.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            val r = rad.text.toString().toDoubleOrNull()
            if (p.size == 2 && r != null && r > 0 && r <= 100) MapDownload.Box.around(p[0], p[1], r) else null
        }
        fun jobs(): List<MapDownload.Job> {
            val a = area() ?: return emptyList(); val z = zg.checkedRadioButtonId - 100
            return MapDownload.LAYERS.filterIndexed { i, _ -> checks[i].isChecked }.map { MapDownload.Job(it, a, min(z, it.maxZoom)) }
        }
        fun update() {
            val j = jobs()
            est.text = if (area() == null) "Enter a valid point and radius (≤ 100 km)." else if (j.isEmpty()) "Pick at least one layer." else
                "About ${j.sumOf { it.total }} tiles, ~${j.sumOf { it.total * it.layer.avgKb } / 1024} MB. Use wifi."
        }
        val w = object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) = update()
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        }
        pt.addTextChangedListener(w); rad.addTextChangedListener(w)
        rg.setOnCheckedChangeListener { _, _ -> update() }; zg.setOnCheckedChangeListener { _, _ -> update() }
        checks.forEach { it.setOnCheckedChangeListener { _, _ -> update() } }
        update()
        AlertDialog.Builder(this).setTitle("Download maps for offline").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Download") { _, _ -> jobs().takeIf { it.isNotEmpty() }?.let(::runDownloads) ?: toast("Nothing to download") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun runDownloads(jobs: List<MapDownload.Job>) {
        val prog = TextView(this).apply { setPadding(dp(20), dp(16), dp(20), dp(8)) }
        val dlg = AlertDialog.Builder(this).setTitle("Downloading maps").setView(prog).setCancelable(false)
            .setNegativeButton("Stop") { _, _ -> jobs.forEach { it.cancel.set(true) } }.show()
        thread {
            var last = 0L
            for ((i, j) in jobs.withIndex()) {
                if (jobs.any { it.cancel.get() }) break
                val f = MapDownload.run(j) { jb ->
                    val now = System.currentTimeMillis()
                    if (now - last > 300) { last = now
                        runOnUiThread { prog.text = "Layer ${i + 1}/${jobs.size}: ${jb.layer.label}\n" +
                            "${jb.done.get() + jb.skipped.get() + jb.failed.get()} / ${jb.total} tiles  ·  ${jb.bytes.get() / 1048576} MB" +
                            (if (jb.failed.get() > 0) "  ·  ${jb.failed.get()} missing" else "") }
                    }
                }

                if (j.layer.terrain) store.demTiles = store.demTiles + f.absolutePath
                else store.mbtiles = listOf(f.absolutePath) + store.mbtiles
            }
            runOnUiThread {
                dlg.dismiss()
                val got = jobs.sumOf { it.done.get() }; val had = jobs.sumOf { it.skipped.get() }; val miss = jobs.sumOf { it.failed.get() }
                AlertDialog.Builder(this).setTitle(if (jobs.any { it.cancel.get() }) "Download stopped" else "Maps downloaded")
                    .setMessage("$got new tiles, $had already had${if (miss > 0) ", $miss not available" else ""}.\n" +
                        "Saved in ${MapDownload.dir().path}. Base map: Offline uses them.")
                    .setPositiveButton("OK", null).show()
                store.mapMode = "offline"
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        if (items.isEmpty() && hasStorage()) scan()
    }

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == 0

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            status.text = "Needs 'All files access' to read DJI flight records and SD-card maps."
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) { if (hasStorage()) scan() }

    private fun scan() {
        status.text = "Scanning storage…"
        thread {
            val roots = store.roots()
            val mb = mutableListOf<File>()
            val found = Scanner.scan(roots, mbtiles = mb)
            val (dem, img) = mb.partition { isTerrainMbtiles(it) }
            store.mbtiles = (store.mbtiles + img.map { it.absolutePath }).filterNot { p -> dem.any { it.absolutePath == p } }
            store.demTiles = store.demTiles + dem.map { it.absolutePath }
            runOnUiThread {
                items = found
                checked.retainAll(found.map { it.file.path }.toSet())
                list.adapter = Adapter()
                val logs = found.count { it.kind == "log" }; val clips = found.size - logs
                status.text = "$logs flight logs · $clips video clips · ${store.mbtiles.size} offline map files · ${store.demTiles.size} terrain packs\n" +
                    "Searched: " + roots.joinToString { it.path }
                updateBtn()
            }
        }
    }

    private fun updateBtn() {
        btnMap.isEnabled = checked.isNotEmpty()
        btnMap.text = if (checked.isEmpty()) "Tap a flight to map it · tick boxes to map several together" else "Map ${checked.size} selected"
    }

    private inner class Adapter : BaseAdapter() {

        val rows: List<Any> = buildList {
            val dayHdr = SimpleDateFormat("EEE yyyy-MM-dd", Locale.US)
            var last: String? = null
            for (it in items.sortedByDescending { i -> i.time }) {
                val d = dayHdr.format(Date(it.time))
                if (d != last) { add(d); last = d }
                add(it)
            }
        }
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun isEnabled(p: Int) = rows[p] is Item
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is Item) 0 else 1

        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val r = rows[p]
            if (r is String) return (cv as? TextView ?: TextView(this@MainActivity).apply {
                setTextColor(ACCENT); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(12), dp(12), dp(12), dp(4)); textSize = 14f
            }).apply { text = r }
            val it = r as Item
            val row = (cv as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(CheckBox(context).apply { isFocusable = false; setPadding(dp(4), dp(4), dp(12), dp(4)) })
                addView(TextView(context).apply { setTextColor(Color.WHITE); textSize = 15f }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            (row.getChildAt(0) as CheckBox).apply {
                setOnCheckedChangeListener(null)
                isChecked = it.file.path in checked

                setOnCheckedChangeListener { _, c -> if (c) checked.add(it.file.path) else checked.remove(it.file.path); updateBtn() }
            }
            val kind = if (it.kind == "log") "LOG" else "VIDEO ${it.lenses}"
            (row.getChildAt(1) as TextView).text = "${hm.format(Date(it.time))}   $kind   · ${it.folder}\n${it.file.name}"
            row.setOnClickListener { _ -> open(listOf(it.file)) }
            row.setOnLongClickListener { _ ->
                if (!checked.add(it.file.path)) checked.remove(it.file.path)
                notifyDataSetChanged(); updateBtn(); true
            }
            return row
        }
    }

    private fun open(files: List<File>) {
        if (files.isEmpty()) return
        startActivity(Intent(this, MapActivity::class.java).putStringArrayListExtra("files", ArrayList(files.map { it.absolutePath })))
    }

    private fun pick() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, 7)
    }

    @Deprecated("platform API")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        if (rc != 7 || res != RESULT_OK || data == null) return
        val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        thread {

            val dir = File(cacheDir, "picked").apply { mkdirs() }
            val files = uris.mapNotNull { u ->
                val name = contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("_display_name")) else null
                } ?: "file_${System.currentTimeMillis()}"
                File(dir, name).also { f -> contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { i.copyTo(it) } } }
            }
            runOnUiThread { open(files) }
        }
    }

    private fun settings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        fun label(s: String) = box.addView(TextView(this).apply { text = s; setPadding(0, dp(10), 0, 0) })
        label("DJI API key: the SDK key of an 'Open API' app at developer.dji.com (decrypts flight logs; internet once per log)")

        fun secret(v: String, hintText: String) = EditText(this).apply { setText(v); setSingleLine(); hint = hintText }
        val key = secret(store.apiKey, "SDK key")
        box.addView(key)
        label("Base map")
        val modes = listOf("offline" to "Offline MBTiles", "usgs" to "USGS Imagery Topo (online, cached)", "osm" to "OpenStreetMap (online, cached)")
        val rg = RadioGroup(this)
        modes.forEachIndexed { i, (k, v) -> rg.addView(RadioButton(this).apply { id = 100 + i; text = v; isChecked = store.mapMode == k }) }
        box.addView(rg)
        box.addView(btn("Download maps for offline…") { downloadMaps() })
        label("Offline map files (.mbtiles), one path per line. Scan adds any it finds.")
        val mb = EditText(this).apply { setText(store.mbtiles.joinToString("\n")); minLines = 2; textSize = 12f }
        box.addView(mb)
        label("CalTopo (this device's own credential; tracks go to that account's personal maps, one map per day)")
        val ctAcct = EditText(this).apply { setText(store.ctAcct); setSingleLine(); hint = "Account ID (6 chars)" }
        val ctCred = EditText(this).apply { setText(store.ctCred); setSingleLine(); hint = "Credential ID (12 chars)" }
        val ctKey = secret(store.ctKey, "Credential key (44 chars)")
        box.addView(ctAcct); box.addView(ctCred); box.addView(ctKey)
        box.addView(btn("Sign in to CalTopo (fills these in)") { calTopoSignIn() })
        label("Keys are stored only on this device (excluded from backups), never in the app file. Clear a field and Save to remove it.")
        label("Extra folders to scan, one per line")
        val ex = EditText(this).apply { setText(store.extraDirs.joinToString("\n")); minLines = 1; textSize = 12f }
        box.addView(ex)
        AlertDialog.Builder(this).setTitle("Settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                store.apiKey = key.text.toString()
                store.ctAcct = ctAcct.text.toString().trim(); store.ctCred = ctCred.text.toString().trim(); store.ctKey = ctKey.text.toString().trim()
                store.mapMode = modes[(rg.checkedRadioButtonId - 100).coerceIn(0, 2)].first
                store.mbtiles = mb.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                store.extraDirs = ex.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                scan()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        val BG = Color.parseColor("#15181c")
        val DIM = Color.parseColor("#9aa3ad")
        val ACCENT = Color.parseColor("#ffb020")
    }
}
