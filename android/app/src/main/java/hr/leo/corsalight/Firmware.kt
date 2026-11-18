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
     * Wire preko pravog USB porta. Svako citanje porta traje barem 300 ms: knjiznica gubi podatke kad
     * bulkTransfer istekne bas dok paket stize (CommonUsbSerialPort.read). Visak bajtova iz jednog
     * paketa se cuva za sljedece citanje.
     */
    class PortWire(
        private val readPort: (ByteArray, Int) -> Int,
        private val writePort: (ByteArray) -> Unit,
        private val baud: (Int) -> Unit,
        private val lines: (Boolean) -> Unit,
    ) : Wire {
        private val pending = ArrayDeque<Int>()
        private val buf = ByteArray(256)

        override fun setBaud(baud: Int) = this.baud(baud)
        override fun setLines(on: Boolean) = lines(on)
        override fun write(b: ByteArray) = writePort(b)

        /**
         * Port na headunitu zna vratiti 0 bajtova odmah, bez cekanja (vidi trag iz 3.0.4:
         * "TX 50 20 | RX -" nakon 1 ms). Prazno citanje zato NIJE istek: cita se dalje do roka.
         */
        override fun read(timeoutMs: Long): Int? {
            pending.removeFirstOrNull()?.let { return it }
            val end = System.currentTimeMillis() + timeoutMs
            do {
                val left = (end - System.currentTimeMillis()).coerceAtLeast(1)
                val n = readPort(buf, maxOf(left, 300L).toInt())
                for (i in 0 until n) pending.addLast(buf[i].toInt() and 0xFF)
                pending.removeFirstOrNull()?.let { return it }
            } while (System.currentTimeMillis() < end)
            return null
        }
    }

    /**
     * Snimi sliku na Arduino. Vraca true ako je provjera prosla. progress dobiva poruke za log.
     *
     * Zasto ovako (optiboot 4.4 + Uno 16U2, provjereno u izvornom kodu oba):
     *  - 16U2 prima USB pakete od 64 B i bajt po bajt ih prebacuje u medjuspremnik od 128 B; dok to radi,
     *    sljedeci paket ceka (NAK). USB na headunitu takav zadrzani paket zna izgubiti, pa je stranica od
     *    133 B uvijek padala, a kratke naredbe su prolazile. Zato se salje u komadima do 64 B, s pauzom
     *    dok UART ne isprazni medjuspremnik -> 16U2 nikad ne mora cekati.
     *  - optiboot nema oporavak: kad mu fali bajt, kao podatke pojede sve sto slijedi (i sync), a krivi bajt
     *    na mjestu 0x20 ga resetira u stari kod. Zato nakon greske nema "resynca" u hodu, nego pravi reset
     *    u bootloader i nastavak od iste stranice.
     */
    fun flash(wire: Wire, image: ByteArray, size: Int, progress: (String) -> Unit): Boolean {
        val stk = Stk(wire)
        val pages = (size + PAGE - 1) / PAGE
        try {
            val baud = connect(stk, wire, progress)
            var recoveries = 0
            fun <T> step(what: String, block: () -> T): T {
                while (true) {
                    try {
                        return block()
                    } catch (e: FlashException) {
                        progress("Retry $what (${e.message})")
                        // pravi reset; i on moze pasti, pa i to broji
                        while (true) {
                            if (++recoveries > 12) throw FlashException("Gave up at $what after $recoveries resets: ${e.message}")
                            try {
                                if (stk.start(baud)) break
                            } catch (_: FlashException) {
                            }
                        }
                    }
                }
            }
            val sig = step("signature") { stk.signature() }
            if (!sig.contentEquals(byteArrayOf(0x1E, 0x95.toByte(), 0x0F))) {
                throw FlashException("Wrong chip signature: " + hex(sig))
            }
            for (p in 0 until pages) {
                step("page $p") {
                    stk.loadAddress(p * PAGE)
                    stk.programPage(image, p * PAGE, PAGE)
                }
                if (p % 32 == 0) progress("Writing ${p * 100 / pages} %")
            }
            progress("Verifying…")
            for (p in 0 until pages) {
                val back = step("verify $p") {
                    stk.loadAddress(p * PAGE)
                    stk.readPage(PAGE)
                }
                for (i in 0 until PAGE) {
                    if (back[i] != image[p * PAGE + i]) throw FlashException("Verify failed at ${p * PAGE + i}")
                }
            }
            step("leave") { stk.cmd(byteArrayOf(0x51)) }  // LEAVE_PROGMODE: Arduino krece s novim kodom
            progress("Done: $size bytes written and verified" + if (recoveries > 0) " ($recoveries resets)" else "")
            return true
        } catch (e: FlashException) {
            stk.dumpTrace(progress)
            throw e
        }
    }

    /** Pronadji bootloader: 115200 (Uno, novi Nano), pa 57600 (stari Nano). Vraca brzinu. */
    private fun connect(stk: Stk, wire: Wire, progress: (String) -> Unit): Int {
        val seen = StringBuilder()
        for (baud in intArrayOf(115200, 57600)) {
            // sync je prosao, a ENTER_PROGMODE ne: isti baud jos par puta
            for (attempt in 0 until 3) {
                try {
                    if (stk.start(baud)) {
                        progress("Bootloader found ($baud baud)")
                        return baud
                    }
                    break  // nema synca na ovoj brzini: sljedeca brzina
                } catch (_: FlashException) {
                }
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
        private val TIMEOUT = 400L  // ms po bajtu odgovora, ispod optibootovog watchdoga (1 s)
        private val CHUNK = 64      // jedan USB paket 16U2

        private var baud = 115200
        private val t0 = System.currentTimeMillis()
        private val trace = ArrayDeque<String>()

        private fun note(s: String) {
            trace.addLast("+${System.currentTimeMillis() - t0} $s")
            if (trace.size > 40) trace.removeFirst()
        }

        /** Zadnjih 40 poslanih/primljenih komada, za log kad nesto pukne. */
        fun dumpTrace(progress: (String) -> Unit) {
            if (trace.isEmpty()) return
            progress("Trace (ms, TX/RX):")
            trace.chunked(4).forEach { progress("  " + it.joinToString("  |  ")) }
        }

        /** Salji u komadima do 64 B; izmedju komada pricekaj da UART 16U2 isprazni prethodni. */
        private fun send(b: ByteArray) {
            var o = 0
            while (o < b.size) {
                val n = minOf(CHUNK, b.size - o)
                wire.write(b.copyOfRange(o, o + n))
                note("TX " + (if (n <= 6) hexOf(b, o, n) else "${n}B " + hexOf(b, o, 4) + "…"))
                o += n
                if (o < b.size) Thread.sleep(n * 10_000L / baud + 2)
            }
        }

        private fun hexOf(b: ByteArray, from: Int, n: Int) = (from until from + n).joinToString(" ") { "%02X".format(b[it]) }

        private fun read(timeoutMs: Long): Int? {
            val b = wire.read(timeoutMs)
            note(if (b == null) "RX -" else "RX %02X".format(b))
            return b
        }

        private val heardBytes = ArrayList<Int>()
        private var heardCount = 0

        /** Sto je stiglo dok se cekao sync: broj bajtova i prvih 16 (za log). */
        fun heard(): String {
            val s = "$heardCount B" + if (heardBytes.isEmpty()) "" else " [" + heardBytes.joinToString(" ") { "%02X".format(it) } + "]"
            heardBytes.clear()
            heardCount = 0
            return s
        }

        /**
         * Reset u bootloader na zadanoj brzini, sync i ulaz u programiranje. False = nema synca;
         * FlashException = sync je prosao, ali ENTER_PROGMODE nije. Nakon greske cisto stanje daje samo
         * ovaj pravi reset (optiboot nema oporavak u hodu).
         */
        fun start(baud: Int): Boolean {
            this.baud = baud
            wire.setBaud(baud)
            note("reset @$baud")
            resetIntoBootloader(wire)
            if (!sync()) return false
            cmd(byteArrayOf(0x50))  // ENTER_PROGMODE
            return true
        }

        /**
         * GET_SYNC dok bootloader ne odgovori, pa jos jednom na cisto: zakasnjeli odgovori na ranije
         * pokusaje ne smiju zavrsiti u sljedecoj naredbi.
         */
        fun sync(): Boolean {
            repeat(8) {
                send(byteArrayOf(0x30, EOP))
                if (expectSync(300)) {
                    drain(wire, 100)
                    send(byteArrayOf(0x30, EOP))
                    if (expectSync(TIMEOUT)) return true
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
                val b = read(left) ?: return false
                heardCount++
                if (heardBytes.size < 16) heardBytes.add(b)
                if (prev == INSYNC && b == OK) return true
                prev = b
            }
        }

        fun cmd(body: ByteArray, answer: Int = 0): ByteArray {
            send(body + EOP)
            // zalutali bajtovi prije INSYNC se preskacu (najvise 16), NOSYNC znaci da je naredba odbijena
            var skipped = 0
            while (true) {
                val b = next()
                if (b == INSYNC) break
                if (b == NOSYNC) throw FlashException("Bootloader said NOSYNC to %02X".format(body[0]))
                if (++skipped > 16) throw FlashException("Bootloader out of sync (no INSYNC for %02X)".format(body[0]))
            }
            val out = ByteArray(answer) { next().toByte() }
            val last = next()
            if (last != OK) {
                throw FlashException("Bootloader out of sync: %02X answered [%s] then %02X".format(body[0], hex(out), last))
            }
            return out
        }

        private fun next(): Int = read(TIMEOUT) ?: throw FlashException("Timeout waiting for the bootloader")

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
