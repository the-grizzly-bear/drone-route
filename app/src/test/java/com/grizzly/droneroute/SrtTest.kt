package com.grizzly.droneroute

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class SrtTest {
    @Test fun parsesArchive() {
        val root = File(System.getenv("DRONE_DATA") ?: "/mnt/sort/drone")
        if (!root.exists()) return
        val files = root.walkTopDown().filter { it.extension.equals("srt", true) }.toList()
        var bad = 0
        for (f in files) {
            val t = Srt.parse(f, ZoneId.of("America/Denver"))
            if (t.pts.isEmpty()) { bad++; println("EMPTY ${f.path}"); continue }
            val p = t.pts.first()
            println("%-70s pts=%5d dur=%5.0fs dist=%6.0fm maxH=%s start=%.5f,%.5f home=%s".format(
                f.path.removePrefix(root.path), t.pts.size, t.durationS, t.distanceM,
                t.maxH?.let { "%.0f".format(it) }, p.lat, p.lon, t.home))
        }
        println("files=${files.size} empty=$bad")
        assertTrue(bad == 0)
    }
}
