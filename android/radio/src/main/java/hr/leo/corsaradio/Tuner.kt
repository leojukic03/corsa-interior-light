package hr.leo.corsaradio

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import kotlin.math.abs

/*
 * Tuner headunita TS18 (TopWay). Originalna radio aplikacija prica s MCU-om preko
 * android.tw.john.TWUtil iz framework.jar (JNI -> /dev/tw). Javne metode te klase su
 * na whitelisti, /dev/tw je 0666, a SELinux dopusta untrusted_app, pa ide i iz obicne
 * aplikacije. Kodovi naredbi i dogadaja su iz dekompilirane com.tw.radio (radio/NALAZI.md).
 */

/** Jedno od 18 spremljenih mjesta. freq 0 = prazno. */
class Preset(var freq: Int = 0, var ps: String = "", var pi: Int = 0)

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
    var pi = 0; private set              // RDS PI kod trenutne stanice (0 = nepoznat)
    var reg = false; private set
    /** Softverski AF: aplikacija sama trazi istu stanicu (isti PI) na drugoj frekvenciji. */
    var softAf = true; private set

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

    fun open(ctx: Context) {
        if (link != null) return
        if (prefs == null) {
            prefs = ctx.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)
            softAf = prefs?.getBoolean("softAf", true) ?: true
            loadKnown()
        }
        link = try {
            TwLink(main).also { demo = false }
        } catch (e: Throwable) {
            Log.w(TAG, "TWUtil nije dostupan, demo nacin", e)
            demo = true
            DemoLink()
        }
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1000)
    }

    fun close() {
        main.removeCallbacks(tick)
        try { link?.close() } catch (e: Throwable) { Log.w(TAG, "close", e) }
        link = null
    }

    private fun write(what: Int, arg1: Int = 0, arg2: Int = 0, quiet: Boolean = false) {
        if (!quiet) log("TX $what $arg1 $arg2")
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
        write(RDS, 0, if (softAf) 1 else 0)
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

    fun seekUp() { userAction(); write(RADIO, 1, 0) }
    fun seekDown() { userAction(); write(RADIO, 1, 1) }
    fun stepUp() { userAction(); write(RADIO, 2, 0) }
    fun stepDown() { userAction(); write(RADIO, 2, 1) }
    fun autoStore() { userAction(); write(RADIO, 0, 0) }
    fun toggleLocal() = write(RADIO, 4, if (local) 0 else 1)
    fun toggleTa() = write(RDS, 1, if (ta) 0 else 1)
    fun toggleReg() = write(RDS, 3, if (reg) 0 else 1)
    fun toggleMute() = write(513, 2, 3)

    /** Ukljuci / iskljuci AF. Isti prekidac salje i MCU-u (RDS AF), a softver radi povrh toga. */
    fun toggleAf() {
        softAf = !softAf
        prefs?.edit()?.putBoolean("softAf", softAf)?.apply()
        afNote = ""
        userAction()
        write(RDS, 0, if (softAf) 1 else 0)
        write(RDS, 255)
        changed()
    }

    fun tune(f: Int) {
        userAction()
        write(FREQ, 255, f.coerceIn(min, max))
        requestSource()
    }

    fun selectPreset(i: Int) {
        userAction()
        write(FREQ, i, band)
        requestSource()
    }

    fun savePreset(i: Int) = write(RADIO, 8, i)

    /** FM1 -> FM2 -> AM -> FM1. */
    fun nextBand() {
        userAction()
        write(RADIO, 5, (band + 1) % 3)
        requestSource()
    }

    fun format(f: Int) = if (isAm) "$f" else String.format(Locale.US, "%.2f", f / 100f)

    // ---------------- dogadaji s MCU-a

    private fun onEvent(what: Int, arg1: Int, arg2: Int, obj: Any?) {
        val body = "$arg1 $arg2" + (if (obj != null) " \"$obj\"" else "")
        // iste poruke koje se ponavljaju ne zatrpavaju log; RADIO/FREQ/REGION imaju podnaredbu u arg1
        val key = if (what == RADIO || what == FREQ || what == REGION) what * 1000 + (arg1 and 255) else what * 1000
        if (lastRx[key] != body) {
            lastRx[key] = body
            log("RX $what $body")
        }
        when (what) {
            RADIO -> when (arg1) {
                0 -> {
                    stereo = arg2 and 16 != 0
                    local = arg2 and 8 != 0
                    scanning = arg2 and 128 != 0
                }
                1 -> band = arg2
                2 -> {
                    if (arg2 != freq) { pi = 0; piSince = 0; lostSince = 0 }
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
                    presets[i].pi = arg1 ushr 16
                    if (band != AM) learn(presets[i].pi, arg2)
                }
            }
            RDS -> {
                af = arg1 and 64 != 0
                ta = arg1 and 32 != 0
                tp = arg1 and 128 != 0
                reg = arg1 and 4 != 0
                pi = arg1 ushr 16
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
            else -> Unit
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


    // ---------------- softverski AF
    //
    // MCU na TS18 ne prebacuje frekvenciju pouzdano, pa to radi aplikacija: dok je stanica
    // stabilna (PI se vidi 3 s), zapamti njezin PI i frekvencije. Kad PI nestane (ili stereo
    // padne) 4 s, proba redom ostale poznate frekvencije istog PI-ja i ostane na prvoj gdje
    // se PI vrati. Poznate frekvencije dolaze iz spremljenih stanica, skeniranja i ranijeg slusanja.

    private const val LOCK_MS = 3000L
    private const val LOSS_MS = 4000L
    private const val CANDIDATE_MS = 3000L
    private const val USER_GRACE_MS = 6000L

    var afNote = ""; private set

    private var prefs: SharedPreferences? = null
    private val known = HashMap<Int, MutableSet<Int>>()   // PI -> frekvencije
    private var lockPi = 0
    private var lockFreq = 0
    private var lockStereo = false
    private var piSince = 0L
    private var lostSince = 0L
    private var userT = 0L
    private var cooldownUntil = 0L
    private var searching = false
    private var queue: List<Int> = emptyList()
    private var qi = 0
    private var origin = 0
    private var candT = 0L

    private val tick = object : Runnable {
        override fun run() {
            try { afTick() } catch (e: Throwable) { Log.w(TAG, "afTick", e) }
            main.postDelayed(this, 1000)
        }
    }

    /** Svaka radnja korisnika (ili tipki na volanu) prekida trazenje i daje 6 s mira. */
    private fun userAction() {
        userT = SystemClock.elapsedRealtime()
        searching = false
        lockPi = 0; piSince = 0; lostSince = 0
    }

    fun afStatus(): String = when {
        !softAf -> "AF off"
        isAm -> "AF: FM only"
        searching -> "AF: searching " + format(queue.getOrElse(qi) { freq })
        afNote.isNotEmpty() -> afNote
        pi != 0 -> "AF on  \u00B7  PI " + String.format(Locale.US, "%04X", pi) + "  \u00B7  " + (known[pi]?.size ?: 0) + " freq"
        else -> "AF on  \u00B7  waiting for RDS"
    }

    private fun learn(p: Int, f: Int) {
        if (p == 0 || f == 0) return
        val set = known.getOrPut(p) { HashSet() }
        if (set.size < 12 && set.add(f)) saveKnown()
    }

    private fun saveKnown() {
        val txt = known.entries.joinToString(";") { e -> "${e.key}:${e.value.joinToString(",")}" }
        prefs?.edit()?.putString("known", txt)?.apply()
    }

    private fun loadKnown() {
        known.clear()
        try {
            val txt = prefs?.getString("known", "").orEmpty()
            for (part in txt.split(";")) {
                val kv = part.split(":")
                if (kv.size != 2) continue
                known[kv[0].toInt()] = kv[1].split(",").mapNotNull { it.toIntOrNull() }.toMutableSet()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "loadKnown", e)
        }
    }

    private fun afTick() {
        val now = SystemClock.elapsedRealtime()
        if (demo || !softAf || isAm || scanning || source != 1) {
            if (searching) searching = false
            lockPi = 0; piSince = 0; lostSince = 0
            return
        }
        if (now - userT < USER_GRACE_MS) return
        write(RDS, 255, quiet = true)  // osvjezi PI i zastavice

        if (searching) {
            if (pi != 0 && pi == lockPi && now - candT >= 1500) {
                searching = false
                lockFreq = freq
                piSince = now
                lostSince = 0
                cooldownUntil = now + 10_000
                afNote = "AF: " + format(origin) + " \u2192 " + format(freq)
                log("AF ok ${origin} -> $freq PI ${String.format(Locale.US, "%04X", pi)}")
                changed()
            } else if (now - candT >= CANDIDATE_MS) {
                nextCandidate(now)
            }
            return
        }

        if (pi != 0) {
            if (piSince == 0L) piSince = now
            if (now - piSince >= LOCK_MS && (lockPi != pi || lockFreq != freq)) {
                lockPi = pi; lockFreq = freq; lockStereo = stereo
                learn(pi, freq)
            }
            lostSince = 0
            if (lockPi == pi && lockFreq == freq && afNote.isNotEmpty() && now - piSince > 30_000) {
                afNote = ""
                changed()
            }
        } else {
            piSince = 0
        }
        val weak = pi == 0 || (lockStereo && !stereo)
        if (lockPi != 0 && lockFreq == freq && weak) {
            if (lostSince == 0L) lostSince = now
            else if (now - lostSince >= LOSS_MS && now >= cooldownUntil) startSearch(now)
        } else if (!weak) {
            lostSince = 0
        }
    }

    private fun startSearch(now: Long) {
        val cands = (known[lockPi] ?: emptySet<Int>())
            .filter { it != freq && it in min..max }
            .sortedBy { abs(it - freq) }
        if (cands.isEmpty()) {
            cooldownUntil = now + 20_000
            afNote = "AF: no known frequency for PI " + String.format(Locale.US, "%04X", lockPi)
            log("AF none for PI ${String.format(Locale.US, "%04X", lockPi)}")
            changed()
            return
        }
        queue = cands
        qi = -1
        origin = freq
        searching = true
        log("AF search ${cands.joinToString(",")} from $origin")
        nextCandidate(now)
    }

    private fun nextCandidate(now: Long) {
        qi++
        pi = 0
        candT = now
        if (qi >= queue.size) {
            searching = false
            cooldownUntil = now + 20_000
            lostSince = 0
            afNote = "AF: no better signal"
            write(FREQ, 255, origin)
            log("AF back to $origin")
        } else {
            write(FREQ, 255, queue[qi])
        }
        changed()
    }

    // ---------------- log sirovih poruka (za dijagnostiku)

    private val logLines = ArrayList<String>()
    private val lastRx = HashMap<Int, String>()

    private fun log(s: String) {
        val t = SystemClock.elapsedRealtime() / 1000.0
        logLines.add(String.format(Locale.US, "%8.1f  %s", t, s))
        if (logLines.size > 400) logLines.removeAt(0)
    }

    fun logText(last: Int = 400): String = logLines.takeLast(last).joinToString("\n")

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
