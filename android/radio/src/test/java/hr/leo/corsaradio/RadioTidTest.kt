package hr.leo.corsaradio

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioTidTest {
    @Test
    fun titleIsStationNameWhenRdsHasOne() {
        assertEquals("HRT-HR2", RadioTid.title("HRT-HR2", 9850, false))
    }

    @Test
    fun titleFallsBackToFrequencyWithoutRds() {
        assertEquals("FM 98.50", RadioTid.title("", 9850, false))
        assertEquals("FM 107.90", RadioTid.title("  ", 10790, false))
        assertEquals("AM 864", RadioTid.title("", 864, true))
    }

    @Test
    fun shortMessages() {
        assertEquals("AF 100.20", RadioTid.af(10020))
        assertEquals("FM2", RadioTid.band(Tuner.FM2))
        assertEquals("AM", RadioTid.band(Tuner.AM))
    }
}
