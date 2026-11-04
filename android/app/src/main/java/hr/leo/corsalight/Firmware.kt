package hr.leo.corsalight

import android.content.Context
import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * Arduino kod koji aplikacija nosi u sebi (assets/firmware, puni ga arduino/build_firmware.ps1)
 * i snimac koji ga preko USB-a snimi na Uno/Nano - isto sto radi avrdude (STK500v1 / optiboot).
 */
object Firmware {
    private const val PAGE = 128          // ATmega328P: 128 bajtova po stranici
    private const val MAX_APP = 32256     // flash bez bootloadera

    /** Verzija koju aplikacija nosi (FW_VERSION iz CorsaLight.ino). */
    fun bundledVersion(ctx: Context): Int = try {
        ctx.assets.open("firmware/version.txt").bufferedReader().readText().trim().toInt()
    } catch (_: Exception) {
        0
    }

    /** Intel HEX -> slika flasha (nepopunjeno = 0xFF) i duljina. */
    fun loadImage(ctx: Context): Pair<ByteArray, Int> {
        val img = ByteArray(MAX_APP) { 0xFF.toByte() }
        var top = 0
        var base = 0
        ctx.assets.open("firmware/corsalight.hex").bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            if (!line.startsWith(":") || line.length < 11) return@forEachLine
            val len = line.substring(1, 3).toInt(16)
            val addr = line.substring(3, 7).toInt(16)
            val type = line.substring(7, 9).toInt(16)
            when (type) {
                0 -> for (i in 0 until len) {
                    val a = base + addr + i
                    require(a < MAX_APP) { "HEX je veci od flasha ($a)" }
                    img[a] = line.substring(9 + i * 2, 11 + i * 2).toInt(16).toByte()
                    if (a + 1 > top) top = a + 1
                }
                2 -> base = line.substring(9, 13).toInt(16) * 16
                4 -> base = line.substring(9, 13).toInt(16) shl 16
            }
        }
        return img to top
    }

    class FlashException(msg: String) : Exception(msg)

    /**
     * Snimi sliku na Arduino. Port mora biti otvoren, a nitko drugi ne smije citati s njega.
     * progress dobiva poruke za dijagnostiku. Vraca true ako je provjera prosla.
     */
    fun flash(port: UsbSerialPort, image: ByteArray, size: Int, progress: (String) -> Unit): Boolean {
        val stk = Stk(port)
        for (baud in intArrayOf(115200, 57600)) {  // Uno / novi Nano: 115200, stari Nano: 57600
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            resetIntoBootloader(port)
            if (stk.sync()) {
                progress("Bootloader found ($baud baud)")
                val sig = stk.signature()
                if (!sig.contentEquals(byteArrayOf(0x1E, 0x95.toByte(), 0x0F))) {
                    throw FlashException("Wrong chip signature: " + sig.joinToString(" ") { "%02X".format(it) })
                }
                stk.cmd(byteArrayOf(0x50))  // ENTER_PROGMODE
                val pages = (size + PAGE - 1) / PAGE
                for (p in 0 until pages) {
                    stk.loadAddress(p * PAGE)
                    stk.programPage(image, p * PAGE, PAGE)
                    if (p % 32 == 0) progress("Writing ${p * 100 / pages} %")
                }
                progress("Verifying…")
                for (p in 0 until pages) {
                    stk.loadAddress(p * PAGE)
                    val back = stk.readPage(PAGE)
                    for (i in 0 until PAGE) {
                        if (back[i] != image[p * PAGE + i]) throw FlashException("Verify failed at ${p * PAGE + i}")
                    }
                }
                stk.cmd(byteArrayOf(0x51))  // LEAVE_PROGMODE: Arduino krece s novim kodom
                progress("Done: $size bytes written and verified")
                return true
            }
        }
        throw FlashException("No answer from the Arduino bootloader")
    }

    /** Kao avrdude: DTR/RTS dolje, pa gore -> 16U2 / CH340 resetira ATmega u bootloader. */
    private fun resetIntoBootloader(port: UsbSerialPort) {
        try {
            port.dtr = false
            port.rts = false
            Thread.sleep(250)
            port.dtr = true
            port.rts = true
            Thread.sleep(50)
        } catch (_: Exception) {
        }
        drain(port)
    }

    private fun drain(port: UsbSerialPort) {
        val b = ByteArray(64)
        try {
            while (port.read(b, 20) > 0) { /* odbaci stari sadrzaj */ }
        } catch (_: Exception) {
        }
    }

    /** STK500v1, samo naredbe koje optiboot zna. */
    private class Stk(private val port: UsbSerialPort) {
        private val EOP = 0x20.toByte()
        private val INSYNC = 0x14.toByte()
        private val OK = 0x10.toByte()

        fun sync(): Boolean {
            repeat(10) {
                try {
                    port.write(byteArrayOf(0x30, EOP), 200)
                    val r = read(2, 250)
                    if (r[0] == INSYNC && r[1] == OK) {
                        drain()
                        return true
                    }
                } catch (_: Exception) {
                }
            }
            return false
        }

        fun cmd(body: ByteArray, answer: Int = 0): ByteArray {
            port.write(body + EOP, 500)
            val r = read(answer + 2, 1000)
            if (r[0] != INSYNC || r[answer + 1] != OK) throw FlashException("Bootloader out of sync")
            return r.copyOfRange(1, answer + 1)
        }

        fun signature() = cmd(byteArrayOf(0x75), 3)

        fun loadAddress(byteAddr: Int) {
            val w = byteAddr / 2  // optiboot trazi adresu u rijecima
            cmd(byteArrayOf(0x55, (w and 0xFF).toByte(), (w shr 8).toByte()))
        }

        fun programPage(img: ByteArray, from: Int, len: Int) {
            val body = byteArrayOf(0x64, (len shr 8).toByte(), (len and 0xFF).toByte(), 'F'.code.toByte()) +
                img.copyOfRange(from, from + len)
            cmd(body)
        }

        fun readPage(len: Int) = cmd(byteArrayOf(0x74, (len shr 8).toByte(), (len and 0xFF).toByte(), 'F'.code.toByte()), len)

        private fun read(n: Int, timeoutMs: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            val buf = ByteArray(256)
            val end = System.currentTimeMillis() + timeoutMs
            while (got < n && System.currentTimeMillis() < end) {
                val c = port.read(buf, 50)
                for (i in 0 until c) if (got < n) out[got++] = buf[i]
            }
            if (got < n) throw FlashException("Timeout ($got/$n bytes)")
            return out
        }

        private fun drain() {
            val b = ByteArray(64)
            try {
                while (port.read(b, 20) > 0) { }
            } catch (_: Exception) {
            }
        }
    }
}
