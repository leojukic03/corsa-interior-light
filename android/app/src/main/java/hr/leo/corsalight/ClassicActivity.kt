package hr.leo.corsalight

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class ClassicActivity : Activity() {

    private data class LedColor(val name: String, val label: String, val r: Int, val g: Int, val b: Int)

    // Iste boje kao u staroj aplikaciji (omjeri iz starog Arduino koda), plus bijela
    private val colors = listOf(
        LedColor("RED", "Crvena", 255, 0, 0),
        LedColor("ORANGE", "Narančasta", 255, 128, 0),
        LedColor("YELLOW", "Žuta", 255, 255, 0),
        LedColor("GREEN", "Zelena", 0, 255, 0),
        LedColor("BLUE", "Plava", 0, 0, 255),
        LedColor("VIOLET", "Ljubičasta", 128, 0, 255),
        LedColor("INDIGO", "Indigo", 255, 0, 255),
        LedColor("WHITE", "Bijela", 255, 255, 255),
    )
    private val colorButtons = mutableListOf<Pair<LedColor, View>>()

    private lateinit var status: TextView
    private lateinit var nowText: TextView
    private lateinit var volumeText: TextView
    private lateinit var warn: TextView
    private lateinit var btnPower: Button
    private lateinit var btnStatic: Button
    private lateinit var btnStar: Button
    private lateinit var brightLabel: TextView

    private var lastBrightSend = 0L
    private var lastHueSend = 0L
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_main)
        LinkService.start(this)

        status = findViewById(R.id.status)
        nowText = findViewById(R.id.nowText)
        volumeText = findViewById(R.id.volumeText)
        warn = findViewById(R.id.warn)
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnPower = findViewById(R.id.btnPower)
        btnStatic = findViewById(R.id.btnStatic)
        btnStar = findViewById(R.id.btnStar)
        brightLabel = findViewById(R.id.brightLabel)

        buildColorButtons()

        btnPower.setOnClickListener {
            Prefs.power = !Prefs.power
            Hub.send("P${if (Prefs.power) 1 else 0}")
            ledMessage(if (Prefs.power) "LED ON" else "LED OFF")
            render()
        }
        findViewById<Button>(R.id.btnWelcome).setOnClickListener { Hub.send("W") }
        btnStatic.setOnClickListener { setMode(0) }
        btnStar.setOnClickListener { setMode(1) }

        findViewById<SeekBar>(R.id.hue).setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val now = System.currentTimeMillis()
                if (now - lastHueSend < 60) return
                lastHueSend = now
                setHue(p)
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                setHue(s.progress)
                ledMessage("LED CUSTOM")
            }

            private fun setHue(p: Int) {
                val c = Color.HSVToColor(floatArrayOf(p.toFloat(), 1f, 1f))
                setColor(LedColor("CUSTOM", "", Color.red(c), Color.green(c), Color.blue(c)), message = false)
            }
        })

        val bright = findViewById<SeekBar>(R.id.bright)
        bright.progress = Prefs.brightness
        bright.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                Prefs.brightness = p
                val now = System.currentTimeMillis()
                if (now - lastBrightSend > 60) {  // ne zatrpavaj serial dok se vuce
                    lastBrightSend = now
                    Hub.send("B${Prefs.brightnessByte()}")
                }
                render()
            }

            override fun onStopTrackingTouch(s: SeekBar) = Hub.send("B${Prefs.brightnessByte()}")
        })
    }

    override fun onResume() {
        super.onResume()
        Hub.addListener(refresh)
        if (Perms.hasMedia(this) && Build.VERSION.SDK_INT >= 24 && MediaListener.instance == null) {
            NotificationListenerService.requestRebind(ComponentName(this, MediaListener::class.java))
        }
        render()
    }

    override fun onPause() {
        Hub.removeListener(refresh)
        super.onPause()
    }

    // ---------------- akcije ----------------

    private fun setColor(c: LedColor, message: Boolean = true) {
        Prefs.r = c.r
        Prefs.g = c.g
        Prefs.b = c.b
        Prefs.colorName = c.name
        Hub.send("C${c.r},${c.g},${c.b}")
        if (!Prefs.power) {
            Prefs.power = true
            Hub.send("P1")
        }
        if (message) ledMessage("LED ${c.name}")
        render()
    }

    private fun setMode(m: Int) {
        Prefs.mode = m
        Hub.send("M$m")
        ledMessage(if (m == 1) "STARS" else "LED ${Prefs.colorName}")
        render()
    }

    private fun ledMessage(text: String) {
        if (Prefs.ledMessages) Hub.send("L$text")
    }

    // ---------------- UI ----------------

    private fun buildColorButtons() {
        val rows = listOf<LinearLayout>(findViewById(R.id.colorRow1), findViewById(R.id.colorRow2))
        val dp = resources.displayMetrics.density
        colors.forEachIndexed { i, c ->
            val v = Button(this).apply {
                text = c.label
                textSize = 13f
                isAllCaps = false
                setTextColor(if (c.g > 200 || c.name == "WHITE") Color.BLACK else Color.WHITE)
                stateListAnimator = null
                setOnClickListener { setColor(c) }
            }
            val lp = LinearLayout.LayoutParams(0, (56 * dp).toInt(), 1f)
            if (i % 4 != 0) lp.marginStart = (10 * dp).toInt()
            rows[i / 4].addView(v, lp)
            colorButtons += c to v
        }
    }

    private fun colorBg(c: LedColor, selected: Boolean) = GradientDrawable().apply {
        cornerRadius = 12 * resources.displayMetrics.density
        setColor(Color.rgb(c.r, c.g, c.b))
        if (selected) setStroke((3 * resources.displayMetrics.density).toInt(), Color.WHITE)
    }

    private fun render() {
        status.text = if (Hub.connected) "● Arduino spojen  (${Hub.usbInfo})" else "○ Arduino nije spojen — uključi USB"
        status.setTextColor(if (Hub.connected) 0xFF7CFFB2.toInt() else 0xFFFFB27C.toInt())

        nowText.text = Hub.nowText.ifEmpty { Prefs.idleText }
        volumeText.text = if (Hub.volume >= 0) "Glasnoća: ${Hub.volume} / ${Hub.volumeMax}" else ""

        val missing = listOfNotNull(
            if (!Perms.hasMedia(this)) "čitanje medija" else null,
            if (!Perms.hasVolume(this)) "čitanje glasnoće" else null,
        )
        warn.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        warn.text = "Isključeno: ${missing.joinToString(", ")} – uključi u Postavkama"

        btnPower.text = if (Prefs.power) "Svjetlo: UPALJENO" else "Svjetlo: UGAŠENO"
        btnPower.isActivated = Prefs.power
        btnStatic.isActivated = Prefs.mode == 0
        btnStar.isActivated = Prefs.mode == 1
        brightLabel.text = "SVJETLINA  ${Prefs.brightness}%"

        for ((c, v) in colorButtons) v.background = colorBg(c, c.name == Prefs.colorName)

    }


    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    }
}
