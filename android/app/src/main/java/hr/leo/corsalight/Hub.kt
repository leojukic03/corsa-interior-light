package hr.leo.corsalight

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zajednicko stanje izmedu servisa, media listenera i ekrana (sve je u istom procesu).
 * Ovdje se odlucuje sto ide na TID.
 */
object Hub {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    var link: LinkService? = null
    var connected = false
        set(v) {
            field = v
            changed()
        }
    var usbInfo = ""

    /** Demo nacin (dugi pritisak na Opel logo): izbornici rade i bez Arduina, npr. u emulatoru. */
    /** Verzija Arduino koda koju je Arduino javio (-1 = jos ne znamo). */
    var fwVersion = -1
        set(v) {
            field = v
            changed()
        }

    /** Tekst za ekran dok se Arduino nadograduje ("" = ne nadograduje se). */
    var flashState = ""
        set(v) {
            field = v
            changed()
        }

    var demo = false
        set(v) {
            field = v
            changed()
        }
    val uiConnected get() = connected || demo

    var nowText = ""      // ocisceni tekst koji je poslan na TID
        private set
    var nowSource = ""    // paket aplikacije koja svira
        private set
    var nowVia = ""       // "session", "notif" ili "broadcast"
        private set

    var volume = -1
        private set
    var volumeMax = 15
        private set

    private val diagLines = ArrayList<String>()
    val diag: List<String> get() = synchronized(diagLines) { diagLines.toList() }

    fun send(cmd: String) {
        link?.send(cmd)
    }

    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(diagLines) {
            diagLines.add(0, "$t  $msg")
            while (diagLines.size > 150) diagLines.removeAt(diagLines.size - 1)
        }
        changed()
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    fun changed() {
        main.post { listeners.toList().forEach { it() } }
    }

    // ---------------- mediji ----------------

    fun isRadio(pkg: String): Boolean {
        val p = pkg.lowercase()
        return "radio" in p || "tuner" in p || p.split('.').any { it == "fm" || it.startsWith("fm") }
    }

    /** Javlja ga MediaListener (sesije i obavijesti) ili broadcast iz LinkService-a. */
    fun onMedia(pkg: String, title: String?, artist: String?, playing: Boolean, via: String) {
        val t = title?.trim().orEmpty()
        val a = artist?.trim().orEmpty()
        val text = when {
            !playing -> ""
            isRadio(pkg) -> if (Prefs.showStation) t.ifEmpty { a } else ""
            else -> if (Prefs.showSong) listOf(a, t).filter { it.isNotEmpty() }.joinToString(" - ") else ""
        }
        // pauza u jednoj aplikaciji ne brise ono sto svira druga
        if (text.isEmpty() && pkg != nowSource && nowText.isNotEmpty()) return
        setNowText(text, pkg, via)
    }

    fun clearMedia(via: String) {
        if (nowVia == via) setNowText("", nowSource, via)
    }

    private fun setNowText(text: String, pkg: String, via: String) {
        val clean = TextUtil.forTid(text, 60)
        nowSource = pkg
        nowVia = via
        if (clean == nowText) return
        nowText = clean
        log("TID: ${clean.ifEmpty { "(idle)" }}  [$pkg]")
        send("N$clean")
        if (clean.isNotEmpty() && Prefs.react) send("X")
        changed()
    }

    /** Ponovno primijeni postavke prikaza (kad se promijeni switch). */
    fun refreshNowText() {
        val hidden = if (isRadio(nowSource)) !Prefs.showStation else !Prefs.showSong
        if (hidden) setNowText("", nowSource, nowVia)
        MediaListener.instance?.evaluate()
    }

    // ---------------- glasnoca ----------------

    fun initVolume(v: Int, max: Int) {
        volume = v
        volumeMax = max
        changed()
    }

    /** true kad radi VolumeReader: tada je glasnoca s ekrana tocna, a AudioManager se ignorira. */
    var screenVolume = false

    fun onScreenVolume(v: Int, max: Int) {
        if (v == volume && max == volumeMax) return
        volume = v
        volumeMax = max
        changed()
        if (Prefs.showVolume || Prefs.react) {
            send("V$v,$max,${if (Prefs.showVolume) 1 else 0}")
        }
    }

    fun onVolume(v: Int, max: Int) {
        if (screenVolume) return  // TS18: AudioManager ne prati pravu glasnocu (npr. CarPlay je digne na 10)
        if (v == volume && max == volumeMax) return
        volume = v
        volumeMax = max
        changed()
        if (Prefs.showVolume || Prefs.react) {
            send("V$v,$max,${if (Prefs.showVolume) 1 else 0}")
        }
    }
}
