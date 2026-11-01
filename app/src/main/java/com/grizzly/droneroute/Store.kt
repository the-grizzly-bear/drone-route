package com.grizzly.droneroute

import android.content.Context
import android.os.Environment
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern

class Store(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("droneroute", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.lines().firstOrNull { it.contains("api_key") }.orEmpty().ifBlank { v }
            .substringAfter('=', v).trim().trim('\'', '"', ' ')).apply()

    var ctAcct: String get() = sp.getString("ctAcct", "") ?: ""; set(v) = sp.edit().putString("ctAcct", v).apply()
    var ctCred: String get() = sp.getString("ctCred", "") ?: ""; set(v) = sp.edit().putString("ctCred", v).apply()
    var ctKey: String get() = sp.getString("ctKey", "") ?: ""; set(v) = sp.edit().putString("ctKey", v).apply()
    val calTopo: CalTopo? get() = if (ctAcct.isNotBlank() && ctCred.isNotBlank() && ctKey.isNotBlank()) CalTopo(ctAcct, ctCred, ctKey) else null

    fun sentKey(f: File) = "${f.name}|${f.length()}"
    fun isSent(f: File) = sp.getStringSet("sent", emptySet())!!.contains(sentKey(f))
    fun markSent(files: Collection<File>) = sp.edit().putStringSet("sent", sp.getStringSet("sent", emptySet())!! + files.map(::sentKey)).apply()

    var mapMode: String
        get() = sp.getString("mapMode", "offline") ?: "offline"
        set(v) = sp.edit().putString("mapMode", v).apply()

    var mbtiles: List<String>
        get() = sp.getString("mbtiles", "")!!.lines().filter { it.isNotBlank() }
        set(v) = sp.edit().putString("mbtiles", v.distinct().joinToString("\n")).apply()

    var demTiles: List<String>
        get() = sp.getString("demTiles", "")!!.lines().filter { it.isNotBlank() }
        set(v) = sp.edit().putString("demTiles", v.distinct().joinToString("\n")).apply()

    var lastView: MapDownload.Box?
        get() = sp.getString("lastView", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }
            ?.let { MapDownload.Box(it[0], it[1], it[2], it[3]) }
        set(v) = sp.edit().putString("lastView", v?.let { "${it.s},${it.w},${it.n},${it.e}" }).apply()

    var extraDirs: List<String>
        get() = sp.getString("extraDirs", "")!!.lines().filter { it.isNotBlank() }
        set(v) = sp.edit().putString("extraDirs", v.distinct().joinToString("\n")).apply()

    private fun kcFile(log: File): File {
        val id = MessageDigest.getInstance("SHA-1").digest("${log.name}|${log.length()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(ctx.filesDir, "keychains/$id.json")
    }
    fun cachedKeychains(log: File): String? = kcFile(log).takeIf { it.exists() }?.readText()
    fun saveKeychains(log: File, json: String) = kcFile(log).apply { parentFile?.mkdirs(); writeText(json) }

    fun roots(): List<File> {
        val r = mutableListOf(Environment.getExternalStorageDirectory())
        ctx.getExternalFilesDirs(null).filterNotNull().forEach { d ->
            val p = d.absolutePath.substringBefore("/Android/data")
            r += File(p)
        }
        File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "self" && it.name != "emulated" }?.let { r += it }
        extraDirs.forEach { r += File(it) }
        return r.distinctBy { it.absolutePath }.filter { it.exists() }
    }
}

fun isTerrainMbtiles(f: File): Boolean = try {
    android.database.sqlite.SQLiteDatabase.openDatabase(f.absolutePath, null,
        android.database.sqlite.SQLiteDatabase.OPEN_READONLY or android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
        db.rawQuery("select value from metadata where name='encoding'", null).use { c -> c.moveToFirst() && c.getString(0).equals("terrarium", true) }
    }
} catch (e: Exception) { f.name.contains("terrain", true) }

data class Item(val file: File, val kind: String, val title: String, val folder: String, val time: Long, val lenses: String)

object Scanner {
    private val logName = Pattern.compile("""DJIFlightRecord_(\d{4})-(\d{2})-(\d{2})_\[(\d{2})-(\d{2})-(\d{2})]""")
    private val clipName = Pattern.compile("""DJI_(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})""")
    private val skip = setOf("Android/obb", ".thumbnails", "LOST.DIR", "DCIM/.thumbnails")

    fun scan(roots: List<File>, found: MutableList<File> = mutableListOf(), mbtiles: MutableList<File> = mutableListOf()): List<Item> {
        fun walk(d: File, depth: Int) {
            if (depth > 9) return
            val kids = d.listFiles() ?: return
            for (f in kids) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (skip.any { f.absolutePath.endsWith(it) }) continue
                    walk(f, depth + 1)
                } else {
                    val n = f.name.lowercase()
                    when {
                        n.endsWith(".srt") -> found += f
                        n.startsWith("djiflightrecord") && n.endsWith(".txt") -> found += f
                        n.endsWith(".mbtiles") -> mbtiles += f
                    }
                }
            }
        }
        roots.forEach { walk(it, 0) }
        return group(found.distinctBy { it.canonicalPath })
    }

    fun group(files: List<File>): List<Item> {
        val out = mutableListOf<Item>()
        files.filter { it.name.lowercase().endsWith(".txt") }.forEach { f ->
            out += Item(f, "log", f.nameWithoutExtension.removePrefix("DJIFlightRecord_"), folderLabel(f), timeOf(f), "")
        }

        files.filter { it.extension.equals("srt", true) }
            .groupBy { it.parent + "/" + Srt.clipKey(it) }
            .forEach { (_, g) ->
                val pref = listOf("_V", "_W", "_Z", "_T", "_S")
                val best = pref.firstNotNullOfOrNull { s -> g.find { it.nameWithoutExtension.endsWith(s, true) } } ?: g.first()
                val lenses = g.map { it.nameWithoutExtension.substringAfterLast('_') }.filter { it.length == 1 }.sorted().joinToString("")
                out += Item(best, "srt", Srt.clipKey(best), folderLabel(best), timeOf(best), lenses)
            }
        return out.sortedByDescending { it.time }
    }

    private fun folderLabel(f: File): String {

        val generic = Regex("(?i)^(flightrecord|flight record|video|videos|dcim|100media|dji_\\d+.*|mavic\\d*|matrice\\d*|\\d+\\w{3})$")
        var p = f.parentFile
        while (p != null && generic.matches(p.name)) p = p.parentFile
        return p?.name ?: f.parent ?: ""
    }

    fun timeOf(f: File): Long {
        logName.matcher(f.name).takeIf { it.find() }?.let { m ->
            return java.time.LocalDateTime.of(m.group(1).toInt(), m.group(2).toInt(), m.group(3).toInt(),
                m.group(4).toInt(), m.group(5).toInt(), m.group(6).toInt())
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        clipName.matcher(f.name).takeIf { it.find() }?.let { m ->
            return java.time.LocalDateTime.of(m.group(1).toInt(), m.group(2).toInt(), m.group(3).toInt(),
                m.group(4).toInt(), m.group(5).toInt(), m.group(6).toInt())
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return f.lastModified()
    }
}
