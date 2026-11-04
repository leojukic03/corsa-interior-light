package hr.leo.corsaradio

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import hr.leo.corsaradio.ui.ButtonRow
import hr.leo.corsaradio.ui.DesignLayout
import hr.leo.corsaradio.ui.FreqDial
import hr.leo.corsaradio.ui.GButton
import hr.leo.corsaradio.ui.GText
import hr.leo.corsaradio.ui.GlowLine
import hr.leo.corsaradio.ui.GradientView
import hr.leo.corsaradio.ui.Strip
import hr.leo.corsaradio.ui.Theme
import hr.leo.corsaradio.ui.headerOf
import hr.leo.corsaradio.ui.withAlpha
import java.util.Locale

/** Radio u izgledu Interior Lighta (Figma "Corsa C Preferences"), dizajn 1800 x 1200. */
class RadioActivity : Activity() {

    private val theme = Theme.BLUE
    private lateinit var title: GText
    private lateinit var bandLabel: GText
    private lateinit var scanLabel: GText
    private lateinit var psText: GText
    private lateinit var freqText: GText
    private lateinit var rtText: GText
    private lateinit var dial: FreqDial
    private lateinit var stereo: GText
    private lateinit var local: GText
    private lateinit var af: GText
    private lateinit var ta: GText
    private lateinit var tp: GText
    private val presetButtons = ArrayList<GButton>()
    private val refresh: () -> Unit = { update() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tuner.open()
        val d = DesignLayout(this, theme)
        d.setBackgroundColor(Color.BLACK)
        build(d)
        setContentView(d)
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

    /** Natrag gasi radio, kao u originalnoj aplikaciji. Tipka Home ga ostavi da svira. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Tuner.powerOff()
        finish()
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

    private fun build(d: DesignLayout) {
        // sjaj uz lijevi i desni rub
        val glow = withAlpha(theme.accent, 0.21f)
        val clear = withAlpha(theme.accent, 0f)
        d.add(GradientView(this, glow, clear, horizontal = true), 0, 0, 263, 1200, DesignLayout.Span.LEFT_EDGE)
        d.add(GradientView(this, clear, glow, horizontal = true), 1537, 0, 263, 1200, DesignLayout.Span.RIGHT_EDGE)

        // zaglavlje: naslov | band | scan | natrag
        d.add(GradientView(this, headerOf(theme.accent), withAlpha(headerOf(theme.accent), 0.2f), horizontal = false), 0, 0, 1800, 148, DesignLayout.Span.FULL_WIDTH)
        d.add(GlowLine(this, vertical = false), 0, 143, 1800, 10, DesignLayout.Span.FULL_WIDTH)
        title = d.add(GText(this, "Radio", 64f), 71, 10, 860, 120)
        d.add(GlowLine(this, vertical = true), 937, 1, 10, 140)
        bandLabel = d.add(GText(this, "FM1", 64f, Gravity.CENTER, plainColor = 0xFFD9D9D9.toInt()), 950, 10, 316, 120)
        bandLabel.setOnClickListener { Tuner.nextBand() }
        d.add(GlowLine(this, vertical = true), 1266, 1, 10, 130)
        scanLabel = d.add(GText(this, "Scan", 64f, Gravity.CENTER, plainColor = 0xFFD9D9D9.toInt()), 1276, 10, 247, 120)
        scanLabel.setOnClickListener { Tuner.autoStore() }
        d.add(GlowLine(this, vertical = true), 1523, 0, 10, 130)
        val back = d.add(ImageView(this), 1594, 21, 123, 73)
        back.setImageResource(R.drawable.ic_d_back)
        back.scaleType = ImageView.ScaleType.FIT_CENTER
        @Suppress("DEPRECATION")
        back.setOnClickListener { onBackPressed() }

        // ime stanice, frekvencija, RadioText
        psText = d.add(GText(this, "", 80f, Gravity.CENTER), 300, 175, 1200, 115)
        psText.highlight = true
        freqText = d.add(GText(this, "", 240f, Gravity.CENTER), 395, 300, 1010, 270)
        rtText = d.add(GText(this, "", 52f, Gravity.CENTER, plainColor = 0xFFB7B7B7.toInt()), 200, 590, 1400, 80)
        rtText.ellipsize = TextUtils.TruncateAt.MARQUEE
        rtText.marqueeRepeatLimit = -1
        rtText.setSingleLine()
        rtText.isSelected = true

        // trazenje: dodir = sljedeca stanica, drzanje = korak po korak
        val seekDown = d.add(GButton(this, "◀◀", theme, 80f), 150, 310, 240, 240)
        holdToStep(seekDown, { Tuner.seekDown() }, { Tuner.stepDown() })
        val seekUp = d.add(GButton(this, "▶▶", theme, 80f), 1410, 310, 240, 240)
        holdToStep(seekUp, { Tuner.seekUp() }, { Tuner.stepUp() })

        // oznake: ST, LOC/DX, AF, TA, TP
        fun flag(label: String, i: Int, onClick: (() -> Unit)?): GText {
            val t = d.add(GText(this, label, 52f, Gravity.CENTER), 450 + i * 190, 680, 170, 80)
            if (onClick != null) t.setOnClickListener { onClick() }
            return t
        }
        stereo = flag("ST", 0, null)
        local = flag("DX", 1) { Tuner.toggleLocal() }
        af = flag("AF", 2) { Tuner.toggleAf() }
        ta = flag("TA", 3) { Tuner.toggleTa() }
        tp = flag("TP", 4, null)

        dial = d.add(FreqDial(this, theme) { Tuner.tune(it) }, 150, 790, 1500, 190)

        // 18 spremljenih: dodir = slusaj, drzanje = spremi trenutnu
        val row = ButtonRow(this, scale = { d.scale }, w = 260f, h = 140f, pitch = 280f, padStart = 150f)
        repeat(Tuner.presets.size) { i ->
            val b = GButton(this, "", theme, 40f)
            b.setOnClickListener { Tuner.selectPreset(i) }
            b.setOnLongClickListener {
                Tuner.savePreset(i)
                true
            }
            presetButtons += b
            row.addView(b)
        }
        val strip = d.add(Strip(this), 0, 1025, 1800, 140, DesignLayout.Span.FULL_WIDTH)
        strip.addView(row)
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
        scanLabel.alpha = if (t.scanning) 1f else 0.85f
        scanLabel.text = if (t.scanning) "Stop" else "Scan"

        psText.text = t.ps
        if (t.freq != 0) {
            val num = fmt(t.freq)
            val s = SpannableString("$num ${if (t.isAm) "kHz" else "MHz"}")
            s.setSpan(RelativeSizeSpan(0.25f), num.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            freqText.text = s
        }
        if (rtText.text.toString() != t.radioText) rtText.text = t.radioText

        fun on(v: GText, active: Boolean) {
            v.highlight = active
            v.alpha = if (active) 1f else 0.4f
        }
        on(stereo, t.stereo)
        local.text = if (t.local) "LOC" else "DX"
        on(local, true)
        on(af, t.af)
        on(ta, t.ta)
        on(tp, t.tp)
        listOf(af, ta, tp).forEach { it.visibility = if (t.rdsAvailable && !t.isAm) View.VISIBLE else View.INVISIBLE }

        dial.min = t.min
        dial.max = t.max
        dial.step = t.step
        dial.am = t.isAm
        dial.freq = t.freq
        dial.marks = t.presets.map { it.freq }.filter { it in t.min..t.max }

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
    }
}
