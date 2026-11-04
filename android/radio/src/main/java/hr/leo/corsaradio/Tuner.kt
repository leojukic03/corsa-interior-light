package hr.leo.corsaradio

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log

/*
 * Tuner headunita TS18 (TopWay). Originalna radio aplikacija prica s MCU-om preko
 * android.tw.john.TWUtil iz framework.jar (JNI -> /dev/tw). Javne metode te klase su
 * na whitelisti, /dev/tw je 0666, a SELinux dopusta untrusted_app, pa ide i iz obicne
 * aplikacije. Kodovi naredbi i dogadaja su iz dekompilirane com.tw.radio (radio/NALAZI.md).
 */

/** Jedno od 18 spremljenih mjesta. freq 0 = prazno. */
class Preset(var freq: Int = 0, var ps: String = "")

object Tuner {
    private const val TAG = "CorsaRadio"

    // naredbe i dogadaji (what)
    private const val RADIO = 1025        // upravljanje i stanje tunera
    private const val FREQ = 1026         // frekvencija / spremljene stanice
    private const val RDS = 1028          // RDS zastavice
    private const val RDS_TEXT = 1029     // RadioText
    private const val REGION = 1030       // granice i korak opsega
    private const val SOURCE = 769        // aktivni izvor zvuka
    private const val MUTE = 515
    private const val KEYS = 40449        // tipke na volanu: 3 next, 4 prev, 9 seek+, 10 seek-
    private const val SERVICE = 40448     // radio u prvom planu (1) ili ne (129)
    private const val REQUEST_SOURCE = 40465

    const val FM1 = 0
    const val FM2 = 1
    const val AM = 2

    // ---------------- stanje

    /** FM u 10 kHz (9850 = 98.50 MHz), AM u kHz. */
    var freq = 0; private set
    var band = FM1; private set
    var ps = ""; private set
    var radioText = ""; private set
    var stereo = false; private set
    var local = false; private set       // LOC (samo jake stanice) / DX
    var scanning = false; private set
    var af = false; private set
    var ta = false; private set
    var tp = false; private set
    var rdsAvailable = true; private set
    var muted = false; private set
    val presets = Array(18) { Preset() }
    var currentPreset = -1; private set

    var fmMin = 8750; private set
    var fmMax = 10800; private set
    var fmStep = 5; private set
    var amMin = 522; private set
    var amMax = 1620; private set
    var amStep = 9; private set

    val isAm get() = band == AM
    val min get() = if (isAm) amMin else fmMin
    val max get() = if (isAm) amMax else fmMax
    val step get() = if (isAm) amStep else fmStep

    /** true kad TWUtil nije dostupan (emulator, drugi headunit): glumi tuner. */
    var demo = false; private set

    private val listeners = ArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    // ---------------- veza

    private val main = Handler(Looper.getMainLooper()) { m -> onEvent(m.what, m.arg1, m.arg2, m.obj); true }
    private var link: Link? = null

    private interface Link {
        fun write(what: Int, arg1: Int, arg2: Int)
        fun close()
    }

    /** android.tw.john.TWUtil preko refleksije (u SDK-u ga nema). */
    private class TwLink(handler: Handler) : Link {
        private val cls = Class.forName("android.tw.john.TWUtil")
        private val tw: Any = cls.getConstructor(Int::class.javaPrimitiveType).newInstance(1)  // 1 = radio
        private val writeM = cls.getMethod("write", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

        init {
            val ids = shortArrayOf(265, 266, 274, 513, 515, 769, 1025, 1026, 1028, 1029, 1030, SERVICE.toShort(), KEYS.toShort())
            val r = cls.getMethod("open", ShortArray::class.java).invoke(tw, ids) as Int
            if (r != 0) throw IllegalStateException("TWUtil.open = $r")
            cls.getMethod("start").invoke(tw)
            cls.getMethod("addHandler", String::class.java, Handler::class.java).invoke(tw, TAG, handler)
        }

        override fun write(what: Int, arg1: Int, arg2: Int) {
            writeM.invoke(tw, what, arg1, arg2)
        }

        override fun close() {
            cls.getMethod("removeHandler", String::class.java).invoke(tw, TAG)
            cls.getMethod("stop").invoke(tw)
            cls.getMethod("close").invoke(tw)
        }
    }

    fun open() {
        if (link != null) return
        link = try {
            TwLink(main).also { demo = false }
        } catch (e: Throwable) {
            Log.w(TAG, "TWUtil nije dostupan, demo nacin", e)
            demo = true
            DemoLink()
        }
    }

    fun close() {
        try { link?.close() } catch (e: Throwable) { Log.w(TAG, "close", e) }
        link = null
    }

    private fun write(what: Int, arg1: Int = 0, arg2: Int = 0) {
        try {
            link?.write(what, arg1, arg2)
        } catch (e: Throwable) {
            Log.w(TAG, "write $what,$arg1,$arg2", e)
        }
    }

    // ---------------- zivotni ciklus ekrana (kao BaseRadioActivity)

    /** Ekran radija je u prvom planu: uzmi zvuk i zatrazi cijelo stanje. */
    fun resume() {
        write(SERVICE, 1)
        requestSource()
        write(266, 255)     // verzija MCU-a
        write(SOURCE, 255)
        write(REGION, 0)
        write(RADIO, 255)
        write(RDS, 255)
        write(MUTE, 255)
    }

    fun pause() = write(SERVICE, 129)

    /** Ugasi radio (otpusti izvor zvuka), kao tipka natrag u originalnoj aplikaciji. */
    fun powerOff() = write(REQUEST_SOURCE, 192, 129)

    private var source = 0
    private fun requestSource() {
        if (source != 1) write(REQUEST_SOURCE, 192, 1)
    }

    // ---------------- naredbe

    fun seekUp() = write(RADIO, 1, 0)
    fun seekDown() = write(RADIO, 1, 1)
    fun stepUp() = write(RADIO, 2, 0)
    fun stepDown() = write(RADIO, 2, 1)
    fun autoStore() = write(RADIO, 0, 0)
    fun toggleLocal() = write(RADIO, 4, if (local) 0 else 1)
    fun toggleAf() = write(RDS, 0, if (af) 0 else 1)
    fun toggleTa() = write(RDS, 1, if (ta) 0 else 1)
    fun toggleMute() = write(513, 2, 3)

    fun tune(f: Int) {
        write(FREQ, 255, f.coerceIn(min, max))
        requestSource()
    }

    fun selectPreset(i: Int) {
        write(FREQ, i, band)
        requestSource()
    }

    fun savePreset(i: Int) = write(RADIO, 8, i)

    /** FM1 -> FM2 -> AM -> FM1. */
    fun nextBand() {
        write(RADIO, 5, (band + 1) % 3)
        requestSource()
    }

    // ---------------- dogadaji s MCU-a

    private fun onEvent(what: Int, arg1: Int, arg2: Int, obj: Any?) {
        when (what) {
            RADIO -> when (arg1) {
                0 -> {
                    stereo = arg2 and 16 != 0
                    local = arg2 and 8 != 0
                    scanning = arg2 and 128 != 0
                }
                1 -> band = arg2
                2 -> {
                    freq = arg2
                    ps = (obj as? String)?.trim().orEmpty()
                    radioText = ""
                }
                4 -> currentPreset = if (arg2 in presets.indices) arg2 else -1
            }
            FREQ -> {
                val i = arg1 and 255
                if (i in presets.indices) {
                    presets[i].freq = arg2
                    presets[i].ps = (obj as? String)?.trim().orEmpty()
                }
            }
            RDS -> {
                af = arg1 and 64 != 0
                ta = arg1 and 32 != 0
                tp = arg1 and 128 != 0
            }
            RDS_TEXT -> radioText = (obj as? String)?.trim().orEmpty()
            REGION -> when (arg1) {
                1 -> fmMax = arg2
                2 -> fmMin = arg2
                3 -> amMax = arg2
                4 -> amMin = arg2
                5 -> fmStep = arg2
                6 -> amStep = arg2
                7 -> rdsAvailable = arg2 != 0
            }
            SOURCE -> source = arg1
            MUTE -> muted = arg1 and Int.MIN_VALUE != 0
            KEYS -> when (arg1) {
                3 -> nextPreset(1)
                4 -> nextPreset(-1)
                9 -> seekUp()
                10 -> seekDown()
            }
            else -> return
        }
        changed()
    }

    /** Tipke na volanu: sljedeca / prethodna spremljena stanica (preskace prazne). */
    private fun nextPreset(dir: Int) {
        val n = presets.size
        var i = currentPreset
        repeat(n) {
            i = ((i + dir) % n + n) % n
            if (presets[i].freq != 0) {
                selectPreset(i)
                return
            }
        }
    }

    // ---------------- demo (emulator): glumi MCU da se vidi sucelje

    private class DemoLink : Link {
        private val stations = listOf(
            8830 to "HR1", 9220 to "NARODNI", 9550 to "ANTENA", 9780 to "HR2",
            10050 to "OTVORENI", 10290 to "BBC", 10470 to "HR3", 10690 to "ENTER",
        )
        private val texts = mapOf(9780 to "Vijesti u 16 sati", 9550 to "Antena Zagreb - samo hitovi")
        private var f = 9780
        private var b = FM1
        private var loc = false
        private var afOn = true
        private var taOn = false
        private var preset = 3

        init {
            stations.take(6).forEachIndexed { i, (fr, name) -> post(FREQ, i, fr, name) }
            post(REGION, 1, 10800); post(REGION, 2, 8750); post(REGION, 5, 5)
            post(REGION, 3, 1620); post(REGION, 4, 522); post(REGION, 6, 9)
            post(SOURCE, 1, 0)
            state()
        }

        private fun post(what: Int, a1: Int, a2: Int, obj: Any? = null) {
            main.sendMessage(Message.obtain(main, what, a1, a2, obj))
        }

        private fun name(fr: Int) = if (b == AM) "" else stations.firstOrNull { it.first == fr }?.second.orEmpty()

        private fun state() {
            post(RADIO, 1, b)
            post(RADIO, 0, (if (name(f).isNotEmpty()) 16 else 0) or (if (loc) 8 else 0))
            post(RADIO, 2, f, name(f))
            post(RADIO, 4, preset)
            post(RDS, (if (afOn) 64 else 0) or (if (taOn) 32 else 0) or 128, 0)
            texts[f]?.let { post(RDS_TEXT, 0, 0, it) }
        }

        private fun lo() = if (b == AM) Tuner.amMin else Tuner.fmMin
        private fun hi() = if (b == AM) Tuner.amMax else Tuner.fmMax
        private fun st() = if (b == AM) Tuner.amStep else Tuner.fmStep

        private fun go(nf: Int) {
            f = if (nf > hi()) lo() else if (nf < lo()) hi() else nf
            preset = (0 until 6).firstOrNull { Tuner.presets[it].freq == f } ?: -1
            state()
        }

        override fun write(what: Int, arg1: Int, arg2: Int) {
            when (what) {
                RADIO -> when (arg1) {
                    1 -> {  // seek na sljedecu "stanicu"
                        val list = if (b == AM) listOf(531, 864, 1134, 1314) else stations.map { it.first }
                        go(if (arg2 == 0) list.firstOrNull { it > f } ?: list.first() else list.lastOrNull { it < f } ?: list.last())
                    }
                    2 -> go(f + if (arg2 == 0) st() else -st())
                    4 -> { loc = arg2 == 1; state() }
                    5 -> { b = arg2; f = if (b == AM) 864 else 9780; go(f) }
                    8 -> { post(FREQ, arg2, f, name(f)); preset = arg2; state() }
                    255 -> state()
                }
                FREQ -> if (arg1 == 255) go(arg2) else Tuner.presets.getOrNull(arg1)?.let { if (it.freq != 0) go(it.freq) }
                RDS -> {
                    if (arg1 == 0) afOn = arg2 == 1
                    if (arg1 == 1) taOn = arg2 == 1
                    state()
                }
            }
        }

        override fun close() {}
    }
}
