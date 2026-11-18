package hr.leo.corsalight

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Lazni Arduino Uno s optibootom (STK500v1) za test snimaca. Odgovori stizu u komadima, kao preko
 * USB-a, a prije bootloadera stari kod salje svoj tekst.
 */
class FakeUno(
    private val strayAfterSync: ByteArray = ByteArray(0),
    /** Redni brojevi odgovora (od 0) koji se "izgube" na USB-u, kao na headunitu. */
    private val lose: Set<Int> = emptySet(),
) : Firmware.Wire {
    private var replies = 0
    /** Optiboot se resetira ako 1 s ne primi nista (watchdog). */
    private var lastRx = 0L
    val flash = ByteArray(32768) { 0xFF.toByte() }
    private val rx = LinkedBlockingQueue<Int>()
    private val cmd = ArrayList<Int>()
    private var inBoot = false
    private var addr = 0
    private var syncs = 0
    private var rate = 0

    init {
        "OK CorsaLight 2\r\n".forEach { rx.add(it.code) }
    }

    override fun setBaud(baud: Int) { rate = baud }

    override fun setLines(on: Boolean) {
        if (on) {  // reset: bootloader krece, stari kod jos posalje komadic teksta
            inBoot = rate == 115200
            "HEL".forEach { rx.add(it.code) }
        }
    }

    override fun read(timeoutMs: Long): Int? = rx.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun reply(vararg b: Int) = b.forEach { rx.add(it and 0xFF) }

    override fun write(b: ByteArray) {
        val now = System.currentTimeMillis()
        if (inBoot && lastRx != 0L && now - lastRx > 1000) inBoot = false  // watchdog: natrag u stari kod
        lastRx = now
        if (!inBoot) return
        for (x in b) {
            cmd.add(x.toInt() and 0xFF)
            if (cmd.last() == 0x20 && complete()) {
                handle()
                cmd.clear()
            }
        }
    }

    /** Je li naredba u cmd cijela (EOP moze biti i podatak unutar stranice). */
    private fun complete(): Boolean = when (cmd[0]) {
        0x64 -> cmd.size >= 5 && cmd.size == 5 + ((cmd[1] shl 8) or cmd[2])
        0x55 -> cmd.size == 4
        0x74 -> cmd.size == 5
        else -> cmd.size == 2
    }

    private fun handle() {
        val n = replies++
        if (n in lose) {  // odgovor se izgubi: posalji ga u prazno
            val keep = ArrayList(rx)
            handleInto()
            rx.clear(); rx.addAll(keep)
            return
        }
        handleInto()
    }

    private fun handleInto() {
        when (cmd[0]) {
            0x30 -> {
                reply(0x14, 0x10)
                if (syncs++ == 0) strayAfterSync.forEach { reply(it.toInt()) }
            }
            0x75 -> reply(0x14, 0x1E, 0x95, 0x0F, 0x10)
            0x50, 0x51 -> reply(0x14, 0x10)
            0x55 -> { addr = (cmd[1] or (cmd[2] shl 8)) * 2; reply(0x14, 0x10) }
            0x64 -> {
                val len = (cmd[1] shl 8) or cmd[2]
                for (i in 0 until len) flash[addr + i] = cmd[4 + i].toByte()
                reply(0x14, 0x10)
            }
            0x74 -> {
                val len = (cmd[1] shl 8) or cmd[2]
                reply(0x14)
                for (i in 0 until len) reply(flash[addr + i].toInt())
                reply(0x10)
            }
            else -> reply(0x15)  // NOSYNC
        }
    }
}

class FirmwareTest {
    private fun image(size: Int) = ByteArray(32256) { if (it < size) (it * 7 + 3).toByte() else 0xFF.toByte() }

    @Test
    fun flashesAndVerifiesOnUno() {
        val uno = FakeUno()
        val img = image(20070)
        assertTrue(Firmware.flash(uno, img, 20070) {})
        assertArrayEquals(img.copyOf(20070), uno.flash.copyOf(20070))
    }

    @Test
    fun survivesStrayBytesAfterSync() {
        // kasni / dupli odgovor na sync: stari snimac je tu javljao "Bootloader out of sync"
        val uno = FakeUno(strayAfterSync = byteArrayOf(0x14, 0x10, 0x14))
        val img = image(4096)
        assertTrue(Firmware.flash(uno, img, 4096) {})
        assertArrayEquals(img.copyOf(4096), uno.flash.copyOf(4096))
    }

    @Test
    fun recoversWhenRepliesGetLost() {
        // kao 3.0.2 na headunitu: sync prode, a odgovor na potpis (i par kasnijih) se izgubi
        val uno = FakeUno(lose = setOf(2, 3, 40, 41, 200))
        val img = image(8192)
        assertTrue(Firmware.flash(uno, img, 8192) {})
        assertArrayEquals(img.copyOf(8192), uno.flash.copyOf(8192))
    }
}
