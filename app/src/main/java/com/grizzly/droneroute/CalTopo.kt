package com.grizzly.droneroute

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class CalTopo(private val acct: String, private val credId: String, private val key: String) {

    data class SendResult(val mapId: String, val title: String, val added: List<String>, val already: List<String>) {
        val url get() = "https://caltopo.com/m/$mapId"
    }

    fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val expires = System.currentTimeMillis() + 120_000
        val payload = body?.toString() ?: ""
        val msg = "$method $path\n$expires\n" + (if (method == "POST") payload else "")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(android.util.Base64.decode(key.trim(), android.util.Base64.DEFAULT), "HmacSHA256")) }
        val sig = android.util.Base64.encodeToString(mac.doFinal(msg.toByteArray()), android.util.Base64.NO_WRAP)
        val form = listOf("id" to credId, "expires" to expires.toString(), "signature" to sig, "json" to payload)
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val c = (URL("https://caltopo.com$path" + if (method == "POST") "" else "?$form").openConnection() as HttpURLConnection)
        c.requestMethod = method; c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "DroneRoute/0.1")
        if (method == "POST") {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) throw IllegalStateException("CalTopo $method $path -> HTTP $code ${text.take(200)}")
        return JSONObject(text.ifBlank { "{}" })
    }

    private fun dayMap(title: String, seed: TP): String {
        val feats = call("GET", "/api/v1/acct/$acct/since/0").getJSONObject("result").optJSONArray("features") ?: JSONArray()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i); val p = f.optJSONObject("properties") ?: continue
            if (p.optString("class") == "CollaborativeMap" && p.optString("accountId") == acct && p.optString("title") == title)
                return f.getString("id")
        }

        val body = JSONObject()
            .put("properties", JSONObject().put("title", title).put("mode", "sar").put("sharing", "URL")
                .put("mapConfig", JSONObject().put("activeLayers", JSONArray().put(JSONArray().put("mbt").put(1)))))
            .put("state", JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(
                JSONObject().put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(seed.lon).put(seed.lat)))
                    .put("properties", JSONObject().put("class", "Marker").put("title", "UAS launch")))))
        return call("POST", "/api/v1/acct/$acct/CollaborativeMap", body).getJSONObject("result").getString("id")
    }

    fun sendDay(day: String, tracks: List<Track>): SendResult {
        val title = "UAS $day"
        val first = tracks.first { it.pts.isNotEmpty() }
        val mapId = dayMap(title, first.home?.let { (la, lo) -> TP(0, la, lo, null, null) } ?: first.pts.first())
        val feats = call("GET", "/api/v1/map/$mapId/since/0").getJSONObject("result")
            .optJSONObject("state")?.optJSONArray("features") ?: JSONArray()
        val existing = mutableSetOf<String>(); var lines = 0
        for (i in 0 until feats.length()) {
            val p = feats.getJSONObject(i).optJSONObject("properties") ?: continue
            if (p.optString("class") == "Shape") lines++
            Regex("""src: (\S+)""").find(p.optString("description"))?.let { existing += it.groupValues[1] }
        }
        val hm = SimpleDateFormat("HH:mm", Locale.US)
        val added = mutableListOf<String>(); val already = mutableListOf<String>()
        for (t in tracks.sortedBy { it.startTs }) {
            if (t.name in existing || t.pts.size < 2) { already += t.name; continue }

            val pts = ArrayList<TP>(); var last = Long.MIN_VALUE / 2
            for (p in t.pts) if (p.ts - last >= 2000) { pts += p; last = p.ts }
            if (pts.last() !== t.pts.last()) pts += t.pts.last()
            val coords = JSONArray(); pts.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
            val desc = "DroneRoute ${if (t.source == "log") "flight log" else "video SRT"}" +
                (t.aircraft?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                " · %.1f km".format(t.distanceM / 1000) + "\nsrc: ${t.name}"
            call("POST", "/api/v1/map/$mapId/Shape", JSONObject()
                .put("properties", JSONObject()
                    .put("title", "UAS ${hm.format(Date(t.startTs))}-${hm.format(Date(t.pts.last().ts))}")
                    .put("description", desc)
                    .put("stroke", COLORS[lines % COLORS.size]).put("stroke-width", 3).put("stroke-opacity", 1).put("pattern", "solid"))
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords)))
            lines++; added += t.name
        }
        return SendResult(mapId, title, added, already)
    }

    companion object {
        val COLORS = listOf("#FF8A00", "#00C2FF", "#FF3B6B", "#7CFF4F", "#B36BFF", "#FFE14F", "#4FFFD2", "#FF6BE1")
        const val ACTIVATE_PAGE = "https://caltopo.com/app/activate/offline?redirect=localhost"

        fun extractCode(s: String): String? =
            Regex("""code=([A-Za-z0-9]{6,12})""").find(s)?.groupValues?.get(1)
                ?: s.trim().takeIf { Regex("^[A-Za-z0-9]{6,12}$").matches(it) }

        fun activate(code: String): Triple<String, String, String> {
            val c = URL("https://caltopo.com/api/v1/activate?code=" + URLEncoder.encode(code, "UTF-8")).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.setRequestProperty("User-Agent", "DroneRoute/0.1")
            val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (c.responseCode !in 200..299) throw IllegalStateException("Activation failed (HTTP ${c.responseCode}). Codes are single-use; get a new one.")
            val o = JSONObject(text)
            return Triple(o.getJSONObject("account").getString("id"), o.getString("code"), o.getString("key"))
        }
    }
}

object Loader {
    fun load(store: Store, f: File): Track =
        if (f.extension.equals("srt", true)) Srt.parse(f) else loadLog(store, f)

    fun loadLog(store: Store, f: File): Track {
        val bytes = f.readBytes()
        val inf = JSONObject(Native.info(bytes))
        if (inf.has("error")) throw IllegalStateException(inf.getString("error"))
        var kc: String? = null
        if (inf.getBoolean("needs_keychains")) {
            kc = store.cachedKeychains(f)
            if (kc == null) {
                if (store.apiKey.isBlank()) throw IllegalStateException(
                    "Log v${inf.getInt("version")} is encrypted. Add your DJI API key in Settings, then open it once with internet.")
                val r = Native.keychains(bytes, store.apiKey)
                if (r.trimStart().startsWith("{") && JSONObject(r).has("error"))
                    throw IllegalStateException("Could not get keys from DJI (needs internet once per log): " + JSONObject(r).getString("error"))
                store.saveKeychains(f, r); kc = r
            }
        }
        val t = LogTrack.fromJson(f.nameWithoutExtension, Native.track(bytes, kc))
        return fixClock(t, f)
    }

    fun fixClock(t: Track, f: File): Track {
        val y2000 = 946_684_800_000L
        if (t.pts.isEmpty() || t.startTs > y2000) return t
        val anchor = Scanner.timeOf(f)
        if (anchor < y2000) return t
        val shift = anchor - t.startTs
        return t.copy(pts = t.pts.map { it.copy(ts = it.ts + shift) })
    }
}
