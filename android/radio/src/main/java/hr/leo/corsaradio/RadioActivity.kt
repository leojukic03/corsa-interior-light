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
import hr.leo.corsaradio.ui.Theme
import hr.leo.corsaradio.ui.headerOf
import hr.leo.corsaradio.ui.withAlpha
import java.io.File
import java.util.Locale

/**
 * Radio u izgledu Interior Lighta (Figma "Corsa C Preferences"), dizajn 1800 x 1200.
 *
 * Glavni ekran je cist: logo, ime stanice, frekvencija, RadioText, trazenje i 6 spremljenih
 * stanica odjednom (povuci ulijevo / udesno za sljedecih 6, ukupno 18). Skala za ugadanje,
 * Scan, LOC/DX, AF, TA, REG i log su u izborniku (gumb Menu gore).
 */
class RadioActivity : Activity() {

    private val theme = Theme.BLUE
    private val perPage = 6

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
    private val presetButtons = ArrayList<GButton>()
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

        // zaglavlje: naslov | band | menu | natrag
        d.add(GradientView(this, headerOf(accent), withAlpha(headerOf(accent), 0.2f), horizontal = false), 0, 0, 1800, 148, DesignLayout.Span.FULL_WIDTH)
        d.add(GlowLine(this, vertical = false), 0, 143, 1800, 10, DesignLayout.Span.FULL_WIDTH)
        title = d.add(GText(this, "Radio", 64f), 71, 10, 860, 120)
        d.add(GlowLine(this, vertical = true), 937, 1, 10, 140)
        bandLabel = d.add(GText(this, "FM1", 64f, Gravity.CENTER, plainColor = 0xFFD9D9D9.toInt()), 950, 10, 316, 120)
        bandLabel.setOnClickListener { Tuner.nextBand() }
        d.add(GlowLine(this, vertical = true), 1266, 1, 10, 130)
        val menu = d.add(GText(this, "Menu", 64f, Gravity.CENTER, plainColor = 0xFFD9D9D9.toInt()), 1276, 10, 247, 120)
        menu.setOnClickListener { menuOpen = true; logOpen = false; applyMenu() }
        d.add(GlowLine(this, vertical = true), 1523, 0, 10, 130)
        val back = d.add(ImageView(this), 1594, 21, 123, 73)
        back.setImageResource(R.drawable.ic_d_back)
        back.scaleType = ImageView.ScaleType.FIT_CENTER
        @Suppress("DEPRECATION")
        back.setOnClickListener { onBackPressed() }

        // logo | ime stanice, frekvencija | RadioText
        logo = d.add(LogoView(this, theme), 150, 200, 330, 330)
        psText = d.add(GText(this, "", 80f, Gravity.CENTER), 500, 175, 1150, 115)
        psText.highlight = true
        freqText = d.add(GText(this, "", 240f, Gravity.CENTER), 500, 300, 1150, 270)
        rtText = d.add(GText(this, "", 52f, Gravity.CENTER, plainColor = 0xFFB7B7B7.toInt()), 200, 590, 1400, 80)
        rtText.ellipsize = TextUtils.TruncateAt.MARQUEE
        rtText.marqueeRepeatLimit = -1
        rtText.setSingleLine()
        rtText.isSelected = true

        // oznake: ST, LOC/DX, AF, TA, TP (AF, TA i LOC se mijenjaju u izborniku, ovdje su samo pokazatelji)
        fun flag(label: String, i: Int): GText = d.add(GText(this, label, 52f, Gravity.CENTER), 450 + i * 190, 680, 170, 80)
        stereo = flag("ST", 0)
        local = flag("DX", 1)
        af = flag("AF", 2)
        ta = flag("TA", 3)
        tp = flag("TP", 4)

        // trazenje: dodir = sljedeca stanica, drzanje = korak po korak; u sredini mute
        val seekDown = d.add(GButton(this, "◀◀", theme, 80f), 150, 790, 520, 170)
        holdToStep(seekDown, { Tuner.seekDown() }, { Tuner.stepDown() })
        muteBtn = d.add(GButton(this, "Mute", theme, 56f), 690, 790, 420, 170)
        muteBtn.setOnClickListener { Tuner.toggleMute() }
        val seekUp = d.add(GButton(this, "▶▶", theme, 80f), 1130, 790, 520, 170)
        holdToStep(seekUp, { Tuner.seekUp() }, { Tuner.stepUp() })

        // spremljene stanice: 6 odjednom, povuci za sljedecih 6. Dodir = slusaj, drzanje = spremi trenutnu
        val pages = Tuner.presets.size / perPage
        dots = d.add(PageDots(this, theme, pages), 700, 982, 400, 36)
        pager = d.add(PagedStrip(this) { dots.page = it }, 0, 1025, 1800, 140)
        pager.pages = pages
        val row = ButtonRow(this, scale = { d.scale }, w = 280f, h = 140f, pitch = 300f, padStart = 10f)
        repeat(Tuner.presets.size) { i ->
            val b = GButton(this, "", theme, 38f)
            b.ellipsize = TextUtils.TruncateAt.END
            b.setOnClickListener { Tuner.selectPreset(i) }
            b.setOnLongClickListener {
                Tuner.savePreset(i)
                b.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                true
            }
            presetButtons += b
            row.addView(b)
        }
        pager.addView(row)

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

        // fino ugadanje: povuci prstom po skali
        dial = hidden(menuItems, FreqDial(this, theme) { Tuner.tune(it) }, 150, 200, 1500, 190)

        fun button(col: Int, row: Int, text: String, click: () -> Unit): GButton {
            val b = hidden(menuItems, GButton(this, text, theme, 56f), 150 + col * 510, 430 + row * 190, 480, 150)
            b.setOnClickListener { click() }
            return b
        }
        btnScan = button(0, 0, "Scan") { Tuner.autoStore() }
        btnLoc = button(1, 0, "DX") { Tuner.toggleLocal() }
        btnAf = button(2, 0, "AF") { Tuner.toggleAf() }
        btnTa = button(0, 1, "TA") { Tuner.toggleTa() }
        btnReg = button(1, 1, "REG") { Tuner.toggleReg() }
        button(2, 1, "Log") { logOpen = true; applyMenu() }
        afStatus = hidden(menuItems, GText(this, "", 44f, Gravity.CENTER, plainColor = 0xFFB7B7B7.toInt()), 150, 830, 1500, 90)

        // log sirovih poruka s MCU-a
        logView = hidden(logItems, LogView(this), 150, 190, 1500, 640)
        hidden(logItems, GButton(this, "Save log", theme, 56f), 150, 870, 480, 150).setOnClickListener { saveLog() }
        hidden(logItems, GButton(this, "Back", theme, 56f), 1170, 870, 480, 150).setOnClickListener { logOpen = false; applyMenu() }
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

        psText.text = t.ps
        if (t.freq != 0) {
            val num = fmt(t.freq)
            val s = SpannableString("$num ${if (t.isAm) "kHz" else "MHz"}")
            s.setSpan(RelativeSizeSpan(0.25f), num.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            freqText.text = s
        } else {
            freqText.text = ""
        }
        if (rtText.text.toString() != t.radioText) rtText.text = t.radioText
        logo.set(Logos.find(this, t.pi, t.ps), t.ps)

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
            b.text = when {
                !inBand -> "—"
                p.ps.isNotEmpty() -> p.ps
                else -> short(p.freq)
            }
            b.highlight = i == t.currentPreset
            b.alpha = if (inBand) 1f else 0.45f
        }
        if (t.currentPreset != lastPreset) {
            lastPreset = t.currentPreset
            if (lastPreset >= 0) pager.goTo(lastPreset / perPage)
        }

        if (!menuOpen) return
        dial.min = t.min
        dial.max = t.max
        dial.step = t.step
        dial.am = t.isAm
        dial.freq = t.freq
        dial.marks = t.presets.map { it.freq }.filter { it in t.min..t.max }

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
