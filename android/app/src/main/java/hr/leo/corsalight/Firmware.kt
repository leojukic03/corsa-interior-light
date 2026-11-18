package hr.leo.corsalight

import android.content.Context

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
     * Veza prema Arduinu za vrijeme snimanja (LinkService.wireFor). Knjiznica usb-serial gubi podatke
     * kad citanje s kratkim timeoutom istekne bas dok paket stize, pa se odgovor bootloadera prepolovi
     * i sve iza toga je "out of sync". Zato Wire cita s dugim timeoutom i cuva visak bajtova.
     */
    interface Wire {
        fun setBaud(baud: Int)
        /** DTR i RTS: false pa true resetira ATmegu u bootloader. */
        fun setLines(on: Boolean)
        fun write(b: ByteArray)
        /** Sljedeci primljeni bajt (0-255) ili null ako nista ne stigne za timeoutMs. */
        fun read(timeoutMs: Long): Int?
    }

    /**
     * Snimi sliku na Arduino. Vraca true ako je provjera prosla.
     * progress dobiva poruke za dijagnostiku.
     */
    fun flash(wire: Wire, image: ByteArray, size: Int, progress: (String) -> Unit): Boolean {
        val stk = Stk(wire)
        val seen = StringBuilder()
        for (baud in intArrayOf(115200, 57600)) {  // Uno / novi Nano: 115200, stari Nano: 57600
            wire.setBaud(baud)
            resetIntoBootloader(wire)
            if (stk.sync()) {
                progress("Bootloader found ($baud baud)")
                val sig = stk.signature()
                if (!sig.contentEquals(byteArrayOf(0x1E, 0x95.toByte(), 0x0F))) {
                    throw FlashException("Wrong chip signature: " + hex(sig))
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
            seen.append(" $baud: ").append(stk.heard())
        }
        throw FlashException("No answer from the Arduino bootloader.$seen")
    }

    /** Kao avrdude: DTR/RTS dolje, pa gore -> 16U2 / CH340 resetira ATmega u bootloader. */
    private fun resetIntoBootloader(wire: Wire) {
        try {
            wire.setLines(false)
            Thread.sleep(250)
            wire.setLines(true)
            Thread.sleep(50)
        } catch (_: Exception) {
        }
        drain(wire)
    }

    /** Odbaci sve sto je stiglo dok ne bude 'quietMs' tisine (stari kod, zakasnjeli odgovori). */
    private fun drain(wire: Wire, quietMs: Long = 60) {
        var n = 0
        while (n < 4096 && wire.read(quietMs) != null) n++
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    /** STK500v1, samo naredbe koje optiboot zna. */
    private class Stk(private val wire: Wire) {
        private val EOP = 0x20.toByte()
        private val INSYNC = 0x14
        private val NOSYNC = 0x15
        private val OK = 0x10

        /**
         * GET_SYNC dok bootloader ne odgovori, pa jos jednom na cisto: nakon prvog odgovora mogu
         * stici zakasnjeli odgovori na ranije pokusaje, a oni ne smiju zavrsiti u sljedecoj naredbi.
         */
        private val heardBytes = ArrayList<Int>()
        private var heardCount = 0

        /** Sto je stiglo dok se cekao sync: broj bajtova i prvih 16 (za log). */
        fun heard(): String {
            val s = "$heardCount B" + if (heardBytes.isEmpty()) "" else " [" + heardBytes.joinToString(" ") { "%02X".format(it) } + "]"
            heardBytes.clear()
            heardCount = 0
            return s
        }

        fun sync(): Boolean {
            repeat(10) {
                wire.write(byteArrayOf(0x30, EOP))
                if (expectSync(300)) {
                    drain(wire, 100)
                    wire.write(byteArrayOf(0x30, EOP))
                    if (expectSync(500)) return true
                }
            }
            return false
        }

        private fun expectSync(timeoutMs: Long): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            var prev = -1
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return false
                val b = wire.read(left) ?: return false
                heardCount++
                if (heardBytes.size < 16) heardBytes.add(b)
                if (prev == INSYNC && b == OK) return true
                prev = b
            }
        }

        fun cmd(body: ByteArray, answer: Int = 0): ByteArray {
            wire.write(body + EOP)
            // zalutali bajtovi prije INSYNC se preskacu (najvise 16), NOSYNC znaci da je naredba odbijena
            var skipped = 0
            while (true) {
                val b = next(1000)
                if (b == INSYNC) break
                if (b == NOSYNC) throw FlashException("Bootloader said NOSYNC to %02X".format(body[0]))
                if (++skipped > 16) throw FlashException("Bootloader out of sync (no INSYNC for %02X)".format(body[0]))
            }
            val out = ByteArray(answer) { next(1000).toByte() }
            val last = next(1000)
            if (last != OK) {
                throw FlashException("Bootloader out of sync: %02X answered [%s] then %02X".format(body[0], hex(out), last))
            }
            return out
        }

        private fun next(timeoutMs: Long): Int =
            wire.read(timeoutMs) ?: throw FlashException("Timeout waiting for the bootloader")

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
    }
}
