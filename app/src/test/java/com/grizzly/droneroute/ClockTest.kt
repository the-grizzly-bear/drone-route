package com.grizzly.droneroute

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ClockTest {
    @Test fun clocklessLogUsesFileNameTime() {

        val t = Track("x", "log", listOf(TP(5_000, 38.6, -106.0, 10.0, 2800.0), TP(15_000, 38.6, -106.0, 12.0, 2802.0)))
        val f = File("DJIFlightRecord_2026-10-25_[14-03-07].txt")
        val fixed = Loader.fixClock(t, f)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        assertEquals("2026-10-25 14:03:07", fmt.format(Date(fixed.startTs)))
        assertEquals(10_000L, fixed.pts[1].ts - fixed.pts[0].ts)

        val good = Track("y", "log", listOf(TP(1_793_000_000_000, 38.6, -106.0, 1.0, 1.0)))
        assertEquals(good, Loader.fixClock(good, f))
    }
}
