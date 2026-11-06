package hr.leo.corsaradio

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.Toast
import hr.leo.corsaradio.ui.ButtonRow
import hr.leo.corsaradio.ui.DesignLayout
import hr.leo.corsaradio.ui.FreqDial
import hr.leo.corsaradio.ui.GButton
import hr.leo.corsaradio.ui.GText
import hr.leo.corsaradio.ui.GlowLine
import hr.leo.corsaradio.ui.GradientView
import hr.leo.corsaradio.ui.LogView
import hr.leo.corsaradio.ui.LogoView
import hr.leo.corsaradio.ui.PageDots
import hr.leo.corsaradio.ui.PagedStrip
import hr.leo.corsaradio.ui.PresetTile
import hr.leo.corsaradio.ui.Theme
import hr.leo.corsaradio.ui.headerOf
import hr.leo.corsaradio.ui.withAlpha
import java.io.File
import java.util.Locale

/**
 * Radio u izgledu Interior Lighta (Figma "Corsa C Preferences"), dizajn 1800 x 1200.
 *
 * Glavni ekran je cist: seek uz frekvenciju, veliki logo, ime stanice, RadioText i 5 spremljenih
 * stanica odjednom (povuci ulijevo / udesno za sljedecih 5, ukupno 18). Skala iskoci na Tune;
 * Scan, LOC/DX, AF, TA, REG, Mute i log su u izborniku (gumb Menu gore).
 */
class RadioActivity : Activity() {

    private val theme = Theme.BLUE
    private val perPage = 5

    private lateinit var d: DesignLayout
    private lateinit var title: GText
    private lateinit var bandLabel: GText
    private lateinit var psText: GText
    private lateinit var freqText: GText
    private lateinit var rtText: GText
    private lateinit var logo: LogoView
    private lateinit var stereo: GText
    private lateinit var local: GText
    private lateinit var af: GText
    private lateinit var ta: GText
    private lateinit var tp: GText
    private lateinit var muteBtn: GButton
    private lateinit var pager: PagedStrip
    private lateinit var dots: PageDots
    private val presetButtons = ArrayList<PresetTile>()
    private lateinit var content: DesignLayout
    private var rdsShown: Boolean? = null
    private val easeOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)        // ulaz / izlaz
    private val easeInOut = PathInterpolator(0.77f, 0f, 0.175f, 1f)    // pomicanje po ekranu
    private var dialOpen = false
    private val hideDial = Runnable { showDial(false) }
    private var lastPreset = -2

    // izbornik
    private lateinit var menuTitle: GText
    private lateinit var dial: FreqDial
    private lateinit var btnScan: GButton
    private lateinit var btnLoc: GButton
    private lateinit var btnAf: GButton
    private lateinit var btnTa: GButton
    private lateinit var btnReg: GButton
    private lateinit var afStatus: GText
    private lateinit var logView: LogView
    private val menuPanel = ArrayList<View>()   // pozadina + zaglavlje izbornika
    private val menuItems = ArrayList<View>()   // sadrzaj izbornika
    private val logItems = ArrayList<View>()    // sadrzaj loga
    private var menuOpen = false
    private var logOpen = false

    private val refresh: () -> Unit = { update() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tuner.open(this)
        Logos.onLoaded = { update() }
        d = DesignLayout(this, theme)
        d.setBackgroundColor(Color.BLACK)
        build()
        setContentView(d)
        applyMenu()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        Tuner.addListener(refresh)
        Tuner.resume()
        update()
    }

    override fun onPause() {
        Tuner.removeListener(refresh)
        Tuner.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) Tuner.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Natrag: zatvara log, pa izbornik, a tek onda gasi radio (kao u originalnoj aplikaciji). Home ga ostavi da svira. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            dialOpen -> showDial(false)
            logOpen -> { logOpen = false; applyMenu() }
            menuOpen -> { menuOpen = false; applyMenu() }
            else -> {
                Tuner.powerOff()
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ---------------- izgled

    private fun <T : View> hidden(
        list: MutableList<View>, v: T, x: Number, y: Number, w: Number, h: Number,
        span: DesignLayout.Span = DesignLayout.Span.NONE,
    ): T {
        d.add(v, x, y, w, h, span)
        v.visibility = View.GONE
        list += v
        return v
    }

    private fun build() {
        val accent = theme.accent
        // sjaj uz lijevi i desni rub
        val glow = withAlpha(accent, 0.21f)
        val clear = withAlpha(accent, 0f)
        d.add(GradientView(this, glow, clear, horizontal = true), 0, 0, 263, 1200, DesignLayout.Span.LEFT_EDGE)
        d.add(GradientView(this, clear, glow, horizontal = true), 1537, 0, 263, 1200, DesignLayout.Span.RIGHT_EDGE)

        // zaglavlje: naslov | band | tune | menu | natrag
        d.add(GradientView(this, headerOf(accent), withAlpha(headerOf(accent), 0.2f), horizontal = false), 0, 0, 1800, 148, DesignLayout.Span.FULL_WIDTH)
        d.add(GlowLine(this, vertical = false), 0, 143, 1800, 10, DesignLayout.Span.FULL_WIDTH)
        title = d.add(GText(this, "Radio", 64f), 71, 10, 560, 120)
        d.add(GlowLine(this, vertical = true), 640, 1, 10, 140)
        bandLabel = d.add(GText(this, "FM1", 64f, Gravity.CENTER, plainColor = 0xFFD9D9D9.toInt()), 650, 10, 287, 120)
        bandLabel.setOnClickListener { Tuner.nextBand() }
        d.add(GlowLine(this, vertical = true), 937, 1, 10, 140)
        val tune = d.add(GText(this, "Tune", 64f, Gravity.CENTER, plainColor = 0xFFD9D9D9.toInt()), 950, 10, 316, 120)
        tune.setOnClickListener { showDial(!dialOpen) }
        d.add(GlowLine(this, vertical = true), 1266, 1, 10, 130)
        val menu = d.add(GText(this, "Menu", 64f, Gravity.CENTER, plainColor = 0xFFD9D9D9.toInt()), 1276, 10, 247, 120)
        menu.setOnClickListener { menuOpen = true; logOpen = false; applyMenu() }
        d.add(GlowLine(this, vertical = true), 1523, 0, 10, 130)
        val back = d.add(ImageView(this), 1594, 21, 123, 73)
        back.setImageResource(R.drawable.ic_d_back)
        back.scaleType = ImageView.ScaleType.FIT_CENTER
        @Suppress("DEPRECATION")
        back.setOnClickListener { onBackPressed() }

        // sav sadrzaj ispod zaglavlja je u jednom rasporedu, da ga skala moze gurnuti dolje i smanjiti
        content = DesignLayout(this, theme, fixedScale = { d.scale })
        d.add(content, 0, 0, 1800, 1200, DesignLayout.Span.FULL_WIDTH)

        // [<]  veliki logo | ime stanice (veliko kad stigne RDS) + frekvencija  [>]
        // dodir = sljedeca stanica, drzanje = korak po korak
        val seekDown = content.add(GButton(this, "◀", theme, 64f), 150, 330, 150, 150)
        holdToStep(seekDown, { Tuner.seekDown() }, { Tuner.stepDown() })
        val seekUp = content.add(GButton(this, "▶", theme, 64f), 1500, 330, 150, 150)
        holdToStep(seekUp, { Tuner.seekUp() }, { Tuner.stepUp() })
        logo = content.add(LogoView(this, theme), 340, 190, 420, 420)
        // bez RDS-a frekvencija je velika u sredini; s RDS-om frekvencija je mala gore, a ime veliko ispod
        freqText = content.add(GText(this, "", 170f, Gravity.CENTER), 800, 190, 660, 300)
        psText = content.add(GText(this, "", 125f, Gravity.CENTER), 800, 300, 660, 170)
        psText.highlight = true
        rtText = content.add(GText(this, "", 44f, Gravity.CENTER, plainColor = 0xFFB7B7B7.toInt()), 800, 495, 660, 65)
        rtText.ellipsize = TextUtils.TruncateAt.MARQUEE
        rtText.marqueeRepeatLimit = -1
        rtText.setSingleLine()
        rtText.isSelected = true

        // oznake: ST, LOC/DX, AF, TA, TP (mijenjaju se u izborniku, ovdje su samo pokazatelji)
        fun flag(label: String, i: Int): GText = content.add(GText(this, label, 44f, Gravity.CENTER), 805 + i * 132, 565, 120, 60)
        stereo = flag("ST", 0)
        local = flag("DX", 1)
        af = flag("AF", 2)
        ta = flag("TA", 3)
        tp = flag("TP", 4)

        // spremljene stanice: 5 odjednom (logo + ime), povuci za sljedecih 5. Dodir = slusaj, drzanje = spremi
        val pages = (Tuner.presets.size + perPage - 1) / perPage
        pager = content.add(PagedStrip(this) { dots.page = it }, 0, 705, 1800, 345)
        dots = content.add(PageDots(this, theme, pages), 700, 1080, 400, 40)
        pager.pages = pages
        val row = ButtonRow(this, scale = { d.scale }, w = 340f, h = 335f, pitch = 360f, padStart = 10f)
        repeat(Tuner.presets.size) { i ->
            val b = PresetTile(this, theme)
            b.setOnClickListener { Tuner.selectPreset(i) }
            b.setOnLongClickListener {
                Tuner.savePreset(i)
                b.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                true
            }
            presetButtons += b
            row.addView(b)
        }
        // prazna mjesta do kraja zadnje stranice, da se i ona zaustavi na cijeloj sirini
        repeat(pages * perPage - Tuner.presets.size) { row.addView(View(this)) }
        pager.addView(row)

        // skala za ugadanje: na tipku Tune izade ispod zaglavlja i gurne sadrzaj dolje; sama se skrije nakon 8 s
        dial = d.add(FreqDial(this, theme) { Tuner.tune(it); showDial(true) }, 150, 175, 1500, 200)
        dial.visibility = View.GONE

        buildMenu()
    }

    private fun buildMenu() {
        val accent = theme.accent
        // pozadina zakrije glavni ekran i hvata dodire
        hidden(menuPanel, View(this).apply { setBackgroundColor(Color.BLACK); isClickable = true },
            0, 0, 1800, 1200, DesignLayout.Span.FULL_WIDTH)
        hidden(menuPanel, GradientView(this, headerOf(accent), withAlpha(headerOf(accent), 0.2f), horizontal = false),
            0, 0, 1800, 148, DesignLayout.Span.FULL_WIDTH)
        hidden(menuPanel, GlowLine(this, vertical = false), 0, 143, 1800, 10, DesignLayout.Span.FULL_WIDTH)
        menuTitle = hidden(menuPanel, GText(this, "Menu", 64f), 71, 10, 860, 120)
        val back = hidden(menuPanel, ImageView(this), 1594, 21, 123, 73)
        back.setImageResource(R.drawable.ic_d_back)
        back.scaleType = ImageView.ScaleType.FIT_CENTER
        @Suppress("DEPRECATION")
        back.setOnClickListener { onBackPressed() }

        fun button(col: Int, row: Int, text: String, click: () -> Unit): GButton {
            val b = hidden(menuItems, GButton(this, text, theme, 56f), 150 + col * 510, 220 + row * 190, 480, 150)
            b.setOnClickListener { click() }
            return b
        }
        btnScan = button(0, 0, "Scan") { Tuner.autoStore() }
        btnLoc = button(1, 0, "DX") { Tuner.toggleLocal() }
        btnAf = button(2, 0, "AF") { Tuner.toggleAf() }
        btnTa = button(0, 1, "TA") { Tuner.toggleTa() }
        btnReg = button(1, 1, "REG") { Tuner.toggleReg() }
        button(2, 1, "Mute") { Tuner.toggleMute() }.also { muteBtn = it }
        button(0, 2, "Log") { logOpen = true; applyMenu() }
        afStatus = hidden(menuItems, GText(this, "", 44f, Gravity.CENTER, plainColor = 0xFFB7B7B7.toInt()), 660, 635, 990, 90)

        // log sirovih poruka s MCU-a
        logView = hidden(logItems, LogView(this), 150, 190, 1500, 640)
        hidden(logItems, GButton(this, "Save log", theme, 56f), 150, 870, 480, 150).setOnClickListener { saveLog() }
        hidden(logItems, GButton(this, "Back", theme, 56f), 1170, 870, 480, 150).setOnClickListener { logOpen = false; applyMenu() }
    }

    /** Skala izade odozgo (ease-out), a sadrzaj se istovremeno spusti i smanji, da sve ostane vidljivo. */
    private fun showDial(open: Boolean) {
        d.removeCallbacks(hideDial)
        if (open) d.postDelayed(hideDial, 8000)
        if (open == dialOpen) return
        dialOpen = open
        val s = d.scale
        content.pivotX = content.width / 2f
        content.pivotY = 190f * s  // vrh sadrzaja ostaje poravnat, sve ispod se skupi
        content.animate()
            .scaleX(if (open) 0.82f else 1f).scaleY(if (open) 0.82f else 1f)
            .translationY(if (open) 215f * s else 0f)
            .setInterpolator(easeInOut).setDuration(280).start()
        dial.animate().cancel()
        if (open) {
            dial.visibility = View.VISIBLE
            dial.alpha = 0f
            dial.translationY = -30f * s
            dial.animate().alpha(1f).translationY(0f)
                .setInterpolator(easeOut).setStartDelay(100).setDuration(200).start()  // krene kad se sadrzaj vec odmakne
        } else {
            dial.animate().alpha(0f).translationY(-30f * s)
                .setInterpolator(easeOut).setStartDelay(0).setDuration(180)
                .withEndAction { if (!dialOpen) dial.visibility = View.GONE }.start()
        }
        update()
    }

    /**
     * Stigao (ili nestao) RDS: frekvencija se smanji i ode gore kao natpis, a veliko ime se pojavi ispod nje.
     * Prvi put (otvaranje ekrana) bez animacije.
     */
    private fun setRds(on: Boolean) {
        if (rdsShown == on) return
        if (freqText.height == 0) {  // jos nije rasporedeno: probaj nakon prvog crtanja
            d.post { update() }
            return
        }
        val first = rdsShown == null
        rdsShown = on
        val lift = psText.height * 0.15f
        freqText.animate()
            .scaleX(if (on) 0.45f else 1f).scaleY(if (on) 0.45f else 1f)
            .translationY(if (on) -freqText.height * 0.33f else 0f)
            .setInterpolator(easeInOut).setStartDelay(0).setDuration(if (first) 0L else 260L).start()
        if (on) {
            if (!first) {
                psText.alpha = 0f
                psText.translationY = lift
                psText.scaleX = 0.96f
                psText.scaleY = 0.96f
            }
            psText.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setInterpolator(easeOut).setStartDelay(if (first) 0L else 60L).setDuration(if (first) 0L else 240L).start()
        } else {
            psText.animate().alpha(0f).translationY(lift)
                .setInterpolator(easeOut).setStartDelay(0).setDuration(if (first) 0L else 180L).start()
        }
    }

    private fun applyMenu() {
        fun show(list: List<View>, on: Boolean) = list.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
        show(menuPanel, menuOpen)
        show(menuItems, menuOpen && !logOpen)
        show(logItems, menuOpen && logOpen)
        menuTitle.text = if (logOpen) "Log" else "Menu"
        update()
    }

    private fun saveLog() {
        try {
            val f = File(getExternalFilesDir(null), "radio-log.txt")
            f.writeText(Tuner.logText())
            Toast.makeText(this, f.path, Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Dodir = onTap, drzanje = onStep ponavlja dok je prst na gumbu (GButton zadrzi svoj dodir za izgled). */
    private fun holdToStep(v: View, onTap: () -> Unit, onStep: () -> Unit) {
        val repeat = object : Runnable {
            override fun run() {
                if (!v.isPressed) return
                onStep()
                v.postDelayed(this, 180)
            }
        }
        v.setOnClickListener { onTap() }
        v.setOnLongClickListener {
            v.post(repeat)
            true
        }
    }

    // ---------------- stanje -> ekran

    private fun fmt(f: Int) = if (Tuner.isAm) "$f" else String.format(Locale.US, "%.2f", f / 100f)

    private fun short(f: Int) = if (Tuner.isAm) "$f" else String.format(Locale.US, "%.1f", f / 100f)

    private fun update() {
        val t = Tuner
        title.text = if (t.demo) "Radio  ·  Demo" else "Radio"
        bandLabel.text = when (t.band) {
            Tuner.FM2 -> "FM2"
            Tuner.AM -> "AM"
            else -> "FM1"
        }

        if (t.ps.isNotEmpty()) psText.text = t.ps  // kod nestanka RDS-a ime ostane dok se ne izgubi
        psText.sizePx = if (t.ps.length > 7) 100f else 125f
        setRds(t.ps.isNotEmpty())
        if (t.freq != 0) {
            val num = fmt(t.freq)
            val s = SpannableString("$num ${if (t.isAm) "kHz" else "MHz"}")
            s.setSpan(RelativeSizeSpan(0.25f), num.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            freqText.text = s
        } else {
            freqText.text = ""
        }
        if (rtText.text.toString() != t.radioText) rtText.text = t.radioText
        logo.set(Logos.find(this, t.pi, t.ps), t.ps.ifEmpty { if (t.isAm) "AM" else "FM" })

        fun on(v: GText, active: Boolean) {
            v.highlight = active
            v.alpha = if (active) 1f else 0.4f
        }
        on(stereo, t.stereo)
        local.text = if (t.local) "LOC" else "DX"
        on(local, true)
        on(af, t.softAf)
        on(ta, t.ta)
        on(tp, t.tp)
        listOf(af, ta, tp).forEach { it.visibility = if (t.rdsAvailable && !t.isAm) View.VISIBLE else View.INVISIBLE }

        muteBtn.text = if (t.muted) "Muted" else "Mute"
        muteBtn.highlight = t.muted

        t.presets.forEachIndexed { i, p ->
            val b = presetButtons[i]
            val inBand = p.freq != 0 && p.freq in t.min..t.max
            if (inBand) b.set(Logos.find(this, p.pi, p.ps), p.ps.ifEmpty { short(p.freq) }, if (p.ps.isEmpty()) "" else short(p.freq))
            else b.set(null, "", "")
            b.active = i == t.currentPreset
            b.alpha = if (inBand) 1f else 0.45f
        }
        if (t.currentPreset != lastPreset) {
            lastPreset = t.currentPreset
            if (lastPreset >= 0) pager.goTo(lastPreset / perPage)
        }

        if (dialOpen) {
            dial.min = t.min
            dial.max = t.max
            dial.step = t.step
            dial.am = t.isAm
            dial.freq = t.freq
            dial.marks = t.presets.map { it.freq }.filter { it in t.min..t.max }
        }
        if (!menuOpen) return

        btnScan.text = if (t.scanning) "Stop" else "Scan"
        btnLoc.text = if (t.local) "LOC" else "DX"
        btnLoc.highlight = t.local
        btnAf.text = if (t.softAf) "AF on" else "AF off"
        btnAf.highlight = t.softAf
        btnTa.text = if (t.ta) "TA on" else "TA off"
        btnTa.highlight = t.ta
        btnReg.text = if (t.reg) "REG on" else "REG off"
        btnReg.highlight = t.reg
        afStatus.text = t.afStatus()
        if (logOpen) logView.show(t.logText(150))
    }
}
