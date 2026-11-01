package com.grizzly.droneroute

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

data class TP(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val h: Double?,
    val abs: Double?,
    var spd: Double? = null,
    val yaw: Double? = null,
    val gp: Double? = null,
    val gy: Double? = null,
    val bat: Int? = null,
    val photo: Boolean = false,
)

data class Track(
    val name: String,
    val source: String,
    val pts: List<TP>,
    val home: Pair<Double, Double>? = null,
    val aircraft: String? = null,
) {
    val durationS get() = if (pts.size < 2) 0.0 else (pts.last().ts - pts.first().ts) / 1000.0
    val distanceM: Double get() = pts.zipWithNext { a, b -> dist(a.lat, a.lon, b.lat, b.lon) }.sum()
    val maxH get() = pts.mapNotNull { it.h }.maxOrNull()
    val maxRangeM: Double? get() = home?.let { (hl, hn) -> pts.maxOfOrNull { dist(hl, hn, it.lat, it.lon) } }
    val startTs get() = pts.firstOrNull()?.ts ?: 0L
}

fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

object LogTrack {
    fun fromJson(name: String, json: String): Track {
        val o = JSONObject(json)
        if (o.has("error")) throw IllegalStateException(o.getString("error"))
        val info = o.getJSONObject("info")
        val home = o.optJSONArray("home")?.let { it.getDouble(0) to it.getDouble(1) }
        val arr = o.getJSONArray("points")
        val out = ArrayList<TP>(arr.length())
        var lastTs = Long.MIN_VALUE / 2
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val ts = p.getLong("ts")
            val ph = p.getBoolean("ph")

            if (!ph && ts - lastTs < 500) continue
            lastTs = ts
            out += TP(
                ts = ts, lat = p.getDouble("lat"), lon = p.getDouble("lon"),
                h = p.getDouble("h"), abs = p.getDouble("alt"), spd = p.getDouble("spd"),
                yaw = p.getDouble("yaw"), gp = p.getDouble("gp"), gy = p.getDouble("gy"),
                bat = p.getInt("bat"), photo = ph,
            )
        }
        return Track(name, "log", out, home, info.optString("aircraft"))
    }
}

object Srt {
    private const val DEG = 180.0 / PI
    private val reTimecode = Regex("""^(\d+):(\d+):(\d+)[,.](\d+)\s*-->""")
    private val reLocalTs = Regex("""(\d{4})[-.](\d{2})[-.](\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:[.,](\d{1,3}))?""")
    private val reUtc = Regex("""UTC:(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)""")
    private val reLat = Regex("""\[lat\w*\s*:\s*(-?\d+\.\d+)""")
    private val reLon = Regex("""\[lon\w*\s*:\s*(-?\d+\.\d+)""")
    private val reRel = Regex("""rel_alt\s*:\s*(-?\d+(?:\.\d+)?)""")
    private val reAbs = Regex("""abs_alt\s*:\s*(-?\d+(?:\.\d+)?)""")
    private val reAltB = Regex("""\[altitude\s*:\s*(-?\d+(?:\.\d+)?)""")
    private val reGbYaw = Regex("""gb_yaw\s*:\s*(-?\d+(?:\.\d+)?)""")
    private val reGbPitch = Regex("""gb_pitch\s*:\s*(-?\d+(?:\.\d+)?)""")
    private val reGps = Regex("""GPS\s*\(\s*(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)\s*,\s*(-?\d+(?:\.\d+)?)""")
    private val reBaro = Regex("""BAROMETER\s*:\s*(-?\d+(?:\.\d+)?)""")
    private val reHome = Regex("""HOME\s*\(\s*(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""")
    private val reVLat = Regex("""vLat:\s*(-?\d+\.\d+)""")
    private val reVLon = Regex("""vLon:\s*(-?\d+\.\d+)""")
    private val reVAgl = Regex("""vAGL:\s*(-?\d+)ft""")
    private val reVMsl = Regex("""vMSL:\s*(-?\d+)ft""")
    private val reHSpd = Regex("""hSpd:\s*(-?\d+)mph""")
    private val reVHdg = Regex("""vHdg:\s*(\d+)""")
    private val reCPit = Regex("""cPit:\s*([+-]?\d+)""")

    fun clipKey(f: File): String = f.nameWithoutExtension.replace(Regex("_[VTSWZ]$", RegexOption.IGNORE_CASE), "")

    fun parse(file: File, zone: ZoneId = ZoneId.systemDefault(), minStepMs: Long = 1000): Track =
        file.bufferedReader().use { parse(file.nameWithoutExtension, it.lineSequence(), zone, minStepMs) }

    fun parse(name: String, lines: Sequence<String>, zone: ZoneId, minStepMs: Long = 1000): Track {
        val out = ArrayList<TP>()
        var home: Pair<Double, Double>? = null
        var baseTs: Long? = null
        var lastTs = Long.MIN_VALUE / 2
        val block = StringBuilder()
        var offsetMs = 0L

        fun d(r: Regex, s: String) = r.find(s)?.groupValues?.get(1)?.toDoubleOrNull()

        fun flush() {
            val s = block.toString()
            block.setLength(0)
            if (s.isBlank()) return
            var lat: Double? = null; var lon: Double? = null
            var h: Double? = null; var abs: Double? = null
            var spd: Double? = null; var yaw: Double? = null; var gp: Double? = null; val gy: Double?
            var ts: Long? = null

            reUtc.find(s)?.let { ts = Instant.parse(it.groupValues[1]).toEpochMilli() }
            if (ts == null) reLocalTs.find(s)?.let { m ->
                val g = m.groupValues
                val ms = g[7].padEnd(3, '0').ifEmpty { "000" }.toInt()
                ts = LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), ms * 1_000_000)
                    .atZone(zone).toInstant().toEpochMilli()
            }
            reHome.find(s)?.let {
                var hl = it.groupValues[2].toDouble(); var hn = it.groupValues[1].toDouble()
                var n = 0
                while ((abs(hn) > 180 || abs(hl) > 90) && n++ < 3) { hl /= DEG; hn /= DEG }
                if (abs(hn) <= 180 && abs(hl) <= 90 && (abs(hl) > 1e-6 || abs(hn) > 1e-6)) home = hl to hn
            }

            val gps = reGps.find(s)
            if (gps != null) {
                val a = gps.groupValues[1].toDouble(); val b = gps.groupValues[2].toDouble()
                val latFirst = when {
                    abs(a) > 90 -> false
                    abs(b) > 90 -> true
                    else -> s.contains("BAROMETER")
                }
                if (latFirst) { lat = a; lon = b } else { lon = a; lat = b }
                h = d(reBaro, s) ?: gps.groupValues[3].toDouble()
                if (ts != null && baseTs == null) baseTs = ts!! - offsetMs
                ts = baseTs?.plus(offsetMs) ?: ts
            } else if (s.contains("vLat:")) {
                lat = d(reVLat, s); lon = d(reVLon, s)
                h = d(reVAgl, s)?.times(0.3048); abs = d(reVMsl, s)?.times(0.3048)
                spd = d(reHSpd, s)?.times(0.44704); yaw = d(reVHdg, s); gp = d(reCPit, s)
            } else {
                lat = d(reLat, s); lon = d(reLon, s)
                h = d(reRel, s); abs = d(reAbs, s) ?: d(reAltB, s)
                gp = d(reGbPitch, s)
            }
            gy = d(reGbYaw, s)
            var la = lat ?: return; var lo = lon ?: return
            if (abs(la) < 1e-6 && abs(lo) < 1e-6) return

            var n = 0
            while ((abs(lo) > 180 || abs(la) > 90) && n++ < 3) { la /= DEG; lo /= DEG }
            if (abs(lo) > 180 || abs(la) > 90) return
            val t = ts ?: return
            if (t - lastTs < minStepMs) return
            out.lastOrNull()?.let { prev ->
                val dt = max((t - prev.ts) / 1000.0, 0.5)
                if (dist(prev.lat, prev.lon, la, lo) / dt > 60) return
            }
            lastTs = t
            out += TP(t, la, lo, h, abs, spd, yaw, gp, gy)
        }

        for (line in lines) {
            val tc = reTimecode.find(line)
            if (tc != null) {
                flush()
                val g = tc.groupValues
                offsetMs = ((g[1].toLong() * 60 + g[2].toLong()) * 60 + g[3].toLong()) * 1000 + g[4].toLong()
                continue
            }
            block.append(line).append('\n')
        }
        flush()

        for (i in 1 until out.size) if (out[i].spd == null) {
            val a = out[i - 1]; val b = out[i]
            val dt = (b.ts - a.ts) / 1000.0
            if (dt > 0) b.spd = dist(a.lat, a.lon, b.lat, b.lon) / dt
        }
        if (out.size > 1 && out[0].spd == null) out[0].spd = out[1].spd
        return Track(name, "srt", out, home)
    }
}

object Export {
    private val iso = DateTimeFormatter.ISO_INSTANT
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun kml(tracks: List<Track>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document>""").append('\n')
        append("<Style id=\"r\"><LineStyle><color>ff0080ff</color><width>3</width></LineStyle></Style>\n")
        for (t in tracks) {
            val absOk = t.pts.all { it.abs != null }
            append("<Folder><name>").append(esc(t.name)).append("</name>\n")
            t.home?.let { (la, lo) -> append("<Placemark><name>Home</name><Point><coordinates>$lo,$la</coordinates></Point></Placemark>\n") }
            append("<Placemark><name>").append(esc(t.name)).append("</name><styleUrl>#r</styleUrl><gx:Track>")
            append(if (absOk) "<altitudeMode>absolute</altitudeMode>" else "<altitudeMode>clampToGround</altitudeMode>")
            for (p in t.pts) append("<when>").append(iso.format(Instant.ofEpochMilli(p.ts))).append("</when>")
            for (p in t.pts) append("<gx:coord>").append(p.lon).append(' ').append(p.lat).append(' ').append(p.abs ?: 0.0).append("</gx:coord>")
            append("</gx:Track></Placemark>\n")
            for (p in t.pts.filter { it.photo }) {
                append("<Placemark><name>Photo ").append(iso.format(Instant.ofEpochMilli(p.ts))).append("</name><Point><coordinates>")
                    .append(p.lon).append(',').append(p.lat).append("</coordinates></Point></Placemark>\n")
            }
            append("</Folder>\n")
        }
        append("</Document></kml>\n")
    }

    fun gpx(tracks: List<Track>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="DroneRoute" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        for (t in tracks) {
            t.home?.let { (la, lo) -> append("<wpt lat=\"$la\" lon=\"$lo\"><name>Home ${esc(t.name)}</name></wpt>\n") }
            append("<trk><name>").append(esc(t.name)).append("</name><trkseg>\n")
            for (p in t.pts) {
                append("<trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">")
                p.abs?.let { append("<ele>").append(it).append("</ele>") }
                append("<time>").append(iso.format(Instant.ofEpochMilli(p.ts))).append("</time></trkpt>\n")
            }
            append("</trkseg></trk>\n")
        }
        append("</gpx>\n")
    }
}
