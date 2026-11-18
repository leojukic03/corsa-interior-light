package hr.leo.corsalight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidMessageTest {
    @Test
    fun cleansAndShortensToTenCharacters() {
        assertEquals("LAF 100.20", TidMessage.command("AF 100.20"))
        assertEquals("LCAKOVEC FM", TidMessage.command("Čakovec FM radio"))
    }

    @Test
    fun blankOrMissingTextSendsNothing() {
        assertNull(TidMessage.command("  "))
        assertNull(TidMessage.command(null))
    }
}
