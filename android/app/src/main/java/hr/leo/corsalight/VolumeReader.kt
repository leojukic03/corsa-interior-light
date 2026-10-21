package hr.leo.corsalight

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Cita glasnocu iz prozorcica koji headunit (Dofun/TS18) prikaze kad se mijenja glasnoca.
 * TS18 glasnocu mijenja preko MCU-a, pa je Android AudioManager ne vidi.
 *
 * Prvi put kad u nekom prozoru nadje klizac (SeekBar/ProgressBar) i broj koji se slaze s
 * njegovom vrijednoscu, zapamti paket tog prozora i dalje gleda samo njega.
 */
class VolumeReader : AccessibilityService() {
    companion object {
        var instance: VolumeReader? = null
            private set
    }

    private data class Reading(val value: Int, val max: Int, val strong: Boolean, val info: String)

    private val handler = Handler(Looper.getMainLooper())
    private var lastScan = 0L
    private var pendingPkg: String? = null
    private var candidateLogs = 0
    private val rescan = Runnable { pendingPkg?.let { scan(it) } }

    // Dofun mijenja broj u vec otvorenom prozoricu bez ikakvog dogadjaja, pa ga
    // dok je otvoren sami citamo svakih 100 ms (sa svjezim podacima, ne iz cachea).
    private var pollPkg: String? = null
    private var pollUntil = 0L
    private val poll = object : Runnable {
        override fun run() {
            val pkg = pollPkg ?: return
            val found = scan(pkg, fresh = true)
            val now = System.currentTimeMillis()
            if (found) pollUntil = maxOf(pollUntil, now + 1500)
            // prozor otvoren, ali 5 s bez promjene: stani (sljedeci dogadjaj ga opet pokrene)
            val idle = now - lastChange > 5000
            if (!idle && (found || now < pollUntil)) handler.postDelayed(this, 100) else pollPkg = null
        }
    }
    private var lastChange = 0L

    private fun startPolling(pkg: String) {
        pollUntil = System.currentTimeMillis() + 1500
        if (pollPkg != pkg) lastChange = System.currentTimeMillis()
        if (pollPkg == pkg) return
        pollPkg = pkg
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 100)
    }

    override fun onServiceConnected() {
        Prefs.init(this)
        instance = this
        Hub.screenVolume = true
        Hub.log("PristupaÄnost spojena (Äitanje glasnoÄ‡e)" +
            if (Prefs.volumePkg.isNotEmpty()) ", izvor: ${Prefs.volumePkg}" else ", Äekam prvu promjenu glasnoÄ‡e")
        LinkService.start(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        Hub.screenVolume = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return  // nas klizac za svjetlinu nije glasnoca
        val learned = Prefs.volumePkg
        if (learned.isNotEmpty() && pkg != learned) return

        // dok se glasnoca vrti dolazi puno dogadaja: citaj najvise svakih 80 ms
        // i jos jednom malo kasnije, da uhvatimo zavrsnu vrijednost
        pendingPkg = pkg
        handler.removeCallbacks(rescan)
        handler.postDelayed(rescan, 200)
        val now = System.currentTimeMillis()
        if (now - lastScan < 80) return
        lastScan = now
        scan(pkg)
    }

    fun relearn() {
        Prefs.volumePkg = ""
        candidateLogs = 0
        Hub.log("GlasnoÄ‡a: zaboravio izvor, Äekam iduÄ‡u promjenu glasnoÄ‡e")
    }

    /** Vraca true ako je nasao glasnocu u prozoru tog paketa. Uvijek svjezi podaci: cache drzi staru glasnocu. */
    private fun scan(pkg: String, fresh: Boolean = true): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows.forEach { w -> w.root?.let { if (it.packageName?.toString() == pkg) roots += it } }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) roots += it }

        for (root in roots) {
            val r = findVolume(root, fresh) ?: continue
            val learned = Prefs.volumePkg
            if (learned.isEmpty()) {
                if (!r.strong) {
                    if (candidateLogs++ < 20) Hub.log("PristupaÄnost kandidat $pkg: ${r.info}")
                    continue
                }
                Prefs.volumePkg = pkg
                Prefs.volumeMax = r.max
                Hub.log("GlasnoÄ‡a: nauÄio izvor $pkg (max ${r.max})")
            }
            val max = if (r.max > 0) r.max else Prefs.volumeMax
            if (r.max > 0 && r.max != Prefs.volumeMax) Prefs.volumeMax = r.max
            if (r.value != Hub.volume) lastChange = System.currentTimeMillis()
            Hub.onScreenVolume(r.value, max)
            startPolling(pkg)
            return true
        }
        return false
    }

    /** Trazi klizac s rasponom i/ili broj 0-99 u stablu prozora. */
    private fun findVolume(root: AccessibilityNodeInfo, fresh: Boolean = true): Reading? {
        var seekVal = -1
        var seekMax = -1
        val numbers = mutableListOf<Int>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 300) {
            val n = queue.removeFirst()
            visited++
            if (fresh && !n.refresh()) continue  // cvor vise ne postoji (prozor se zatvorio)
            val cls = n.className?.toString().orEmpty()
            val range = n.rangeInfo
            if (range != null && seekVal < 0 && (cls.contains("SeekBar") || cls.contains("ProgressBar") || cls.contains("Slider"))) {
                if (range.max in 5f..100f) {
                    seekVal = range.current.toInt()
                    seekMax = range.max.toInt()
                }
            }
            val txt = n.text?.toString()?.trim()
            if (txt != null && txt.length in 1..2 && txt.all { it.isDigit() }) numbers += txt.toInt()
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        if (seekVal < 0 && numbers.isEmpty()) return null
        val info = "klizaÄ=${if (seekVal >= 0) "$seekVal/$seekMax" else "-"} brojevi=$numbers"
        return when {
            seekVal >= 0 && seekVal in numbers -> Reading(seekVal, seekMax, true, info)
            seekVal >= 0 -> Reading(seekVal, seekMax, false, info)
            numbers.size == 1 -> Reading(numbers[0], -1, false, info)
            else -> null
        }
    }
}
