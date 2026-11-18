package hr.leo.corsalight

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Lazni Arduino Uno: optiboot 4.4 iz ArduinoCore-avr (bootloaders/optiboot/optiboot.c), bajt po bajt.
 * - naredba se cita do kraja (PROG_PAGE ceka svih 'length' bajtova), pa mora doci 0x20 (verifySpace),
 *   inace se optiboot resetira u stari kod
 * - watchdog: 1 s bez primljenog bajta -> stari kod
 * - reset (DTR) na 115200 -> bootloader
 *
 * USB na headunitu: dropSecondPacket = drugi paket od 64 bajta jednog dugog zapisa se izgubi
 * (16U2 ga zadrzi NAK-om dok prazni medjuspremnik), loseReplies = odgovori koji se izgube.
 */
class FakeUno(
    private val strayAfterSync: ByteArray = ByteArray(0),
    private val loseReplies: Set<Int> = emptySet(),
    private val dropSecondPacket: Boolean = false,
) : Firmware.Wire {
    val flash = ByteArray(32768) { 0xFF.toByte() }
    private val rx = LinkedBlockingQueue<Int>()
    private var rate = 0
    private var inBoot = false
    private var lastRx = 0L
    private var replies = 0
    private var syncs = 0

    // stanje optibootove petlje
    private val cmd = ArrayList<Int>()
    private var need = -1  // koliko jos bajtova treba naredbi (-1 = ceka naredbu)
    private var address = 0

    init {
        "OK CorsaLight 2\r\n".forEach { rx.add(it.code) }
    }

    override fun setBaud(baud: Int) { rate = baud }

    override fun setLines(on: Boolean) {
        if (on) {
            inBoot = rate == 115200
            cmd.clear(); need = -1
            lastRx = System.currentTimeMillis()
            "HEL".forEach { rx.add(it.code) }
        }
    }

    override fun read(timeoutMs: Long): Int? = rx.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun write(b: ByteArray) {
        val data = if (dropSecondPacket && b.size > 64) b.copyOfRange(0, 64) + b.copyOfRange(minOf(128, b.size), b.size) else b
        for (x in data) receive(x.toInt() and 0xFF)
    }

    private fun appStart() {
        inBoot = false
        cmd.clear(); need = -1
    }

    private fun receive(c: Int) {
        val now = System.currentTimeMillis()
        if (inBoot && now - lastRx > 1000) appStart()  // watchdog
        lastRx = now
        if (!inBoot) return
        cmd.add(c)
        if (need < 0) {
            need = when (c) {
                0x55 -> 3          // adresa lo, hi, EOP
                0x64, 0x74 -> 4    // len hi, len lo, memtype, (podaci), EOP
                0x41 -> 2
                0x42 -> 21
                0x45 -> 6
                0x56 -> 5
                else -> 1          // EOP
            }
            return
        }
        // PROG_PAGE: kad stigne duljina, treba i podatke
        if (cmd[0] == 0x64 && cmd.size == 3) need += cmd[2]
        if (--need > 0) return
        val eop = cmd.last()
        val full = ArrayList(cmd)
        cmd.clear(); need = -1
        if (eop != 0x20) { appStart(); return }  // verifySpace: krivi bajt -> reset u stari kod
        execute(full)
    }

    private fun reply(vararg b: Int) {
        val n = replies++
        if (n in loseReplies) return
        b.forEach { rx.add(it and 0xFF) }
    }

    private fun execute(c: List<Int>) {
        when (c[0]) {
            0x55 -> { address = (c[1] or (c[2] shl 8)) * 2; reply(0x14, 0x10) }
            0x64 -> {
                val len = c[2]
                for (i in 0 until len) flash[address + i] = c[4 + i].toByte()
                reply(0x14, 0x10)
            }
            0x74 -> {
                val len = c[2]
                val out = IntArray(len + 2)
                out[0] = 0x14
                for (i in 0 until len) out[1 + i] = flash[address + i].toInt()
                out[len + 1] = 0x10
                reply(*out)
            }
            0x75 -> reply(0x14, 0x1E, 0x95, 0x0F, 0x10)
            0x30 -> {
                reply(0x14, 0x10)
                if (syncs++ == 0) strayAfterSync.forEach { rx.add(it.toInt() and 0xFF) }
            }
            else -> reply(0x14, 0x10)
        }
    }
}

class FirmwareTest {
    private fun image(size: Int) = ByteArray(32256) { if (it < size) (it * 7 + 3).toByte() else 0xFF.toByte() }

    private fun flashAndCheck(uno: FakeUno, size: Int) {
        val img = image(size)
        assertTrue(Firmware.flash(uno, img, size) {})
        assertArrayEquals(img.copyOf(size), uno.flash.copyOf(size))
    }

    @Test
    fun flashesAndVerifiesOnUno() = flashAndCheck(FakeUno(), 20070)

    @Test
    fun survivesStrayBytesAfterSync() = flashAndCheck(FakeUno(strayAfterSync = byteArrayOf(0x14, 0x10, 0x14)), 4096)

    @Test
    fun recoversWhenRepliesGetLost() = flashAndCheck(FakeUno(loseReplies = setOf(2, 3, 40, 41, 200)), 8192)

    @Test
    fun headUnitDropsSecondUsbPacketOfLongWrites() {
        // 3.0.3 na headunitu: sync i potpis prolaze, stranica 0 pada na svakom pokusaju
        flashAndCheck(FakeUno(dropSecondPacket = true), 4096)
    }
}
