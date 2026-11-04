package hr.leo.corsalight.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import hr.leo.corsalight.ClassicActivity
import hr.leo.corsalight.Hub
import hr.leo.corsalight.Perms
import hr.leo.corsalight.Prefs
import hr.leo.corsalight.R
import hr.leo.corsalight.TextUtil
import hr.leo.corsalight.VolumeReader

/** Sto ekran treba od aktivnosti koja ga prikazuje. */
interface UiHost {
    val activity: android.app.Activity
    fun push(s: Screen)
    fun back()
    fun home()
    fun popup(message: String, button: String = "OK", onOk: () -> Unit = {})
}

/** Jedan ekran iz Figme. build() slaze elemente u koordinatama dizajna 1800 x 1200. */
abstract class Screen(protected val host: UiHost) {
    abstract val theme: Theme
    lateinit var root: DesignLayout
        private set
    protected val ctx get() = host.activity

    fun create(): DesignLayout {
        root = DesignLayout(ctx, theme)
        root.setBackgroundColor(Color.BLACK)
        build(root)
        return root
    }

    protected abstract fun build(d: DesignLayout)

    /** Poziva se kad se nesto promijeni (Arduino spojen, glasnoca...). */
    open fun refresh() {}

    // ---------- zajednicki dijelovi

    /** Plavi (ili crveni, zeleni...) sjaj uz lijevi i desni rub ekrana. */
    protected fun sideGlow(d: DesignLayout) {
        val c = withAlpha(theme.accent, 0.21f)
        val clear = withAlpha(theme.accent, 0f)
        d.add(GradientView(ctx, c, clear, horizontal = true), 0, 0, 263, 1200, DesignLayout.Span.LEFT_EDGE)
        d.add(GradientView(ctx, clear, c, horizontal = true), 1537, 0, 263, 1200, DesignLayout.Span.RIGHT_EDGE)
    }

    /**
     * Zaglavlje: naslov lijevo, opcionalno "Off", kucica i strelica natrag.
     * Vraca "Off" tekst (ako ga ima) da ga ekran moze mijenjati.
     */
    protected fun header(d: DesignLayout, title: String, offButton: Boolean = false, homeButton: Boolean = true,
                         centeredTitle: Boolean = false): GText? {
        d.add(GradientView(ctx, headerOf(theme.accent), withAlpha(headerOf(theme.accent), 0.2f), horizontal = false), 0, 0, 1800, 148, DesignLayout.Span.FULL_WIDTH)
        d.add(GlowLine(ctx, vertical = false), 0, 143, 1800, 10, DesignLayout.Span.FULL_WIDTH)
        if (centeredTitle) d.add(GText(ctx, title, 64f, Gravity.CENTER), 300, 10, 1200, 120)
        else d.add(GText(ctx, title, 64f), 71, 10, 1180, 120)
        d.add(GlowLine(ctx, vertical = true), 1523, 0, 10, 130)
        val back = d.add(ImageView(ctx), 1594, 21, 123, 73)
        back.setImageResource(R.drawable.ic_d_back)
        back.scaleType = ImageView.ScaleType.FIT_CENTER
        back.setOnClickListener { host.back() }
        if (homeButton) {
            d.add(GlowLine(ctx, vertical = true), 1266, 1, 10, 130)
            val home = d.add(ImageView(ctx), 1343, 9, 113, 113)
            home.setImageResource(R.drawable.ic_d_home)
            home.setOnClickListener { host.home() }
        }
        if (!offButton) return null
        d.add(GlowLine(ctx, vertical = true), 937, 1, 10, 140)
        val off = d.add(GText(ctx, "", 64f, Gravity.CENTER, plainColor = 0xFFD9D9D9.toInt()), 950, 10, 316, 120)
        off.setOnClickListener {
            setPower(!Prefs.power)
            refresh()
        }
        return off
    }

    protected fun powerLabel() = if (Prefs.power) "Off" else "On"

    protected fun setPower(on: Boolean) {
        Prefs.power = on
        Hub.send("P${if (on) 1 else 0}")
        ledMessage(if (on) "LED ON" else "LED OFF")
    }

    protected fun ledMessage(text: String) {
        if (Prefs.ledMessages) Hub.send("L$text")
    }

    protected fun button(d: DesignLayout, text: String, x: Int, y: Int, w: Int, h: Int = 130, onClick: () -> Unit): GButton {
        val b = d.add(GButton(ctx, text, theme), x, y, w, h)
        b.setOnClickListener { onClick() }
        return b
    }

    protected fun notAvailable() = host.popup("Feature currently not available")

    // ---------- popisi postavki (Center Display, User Preferences, Car Setup...)

    sealed class Row(val label: String) {
        class Check(label: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Row(label)
        class Value(label: String, val value: () -> String, val onClick: () -> Unit) : Row(label)
        class Action(label: String, val onClick: () -> Unit) : Row(label)
    }

    private val valueViews = mutableListOf<Pair<GButton, () -> String>>()
    private val checkViews = mutableListOf<Pair<GCheck, () -> Boolean>>()

    /** Redovi svakih 200 px od y = 265 (kao u Figmi), u ScrollViewu ako ih ima vise nego sto stane. */
    protected fun rows(d: DesignLayout, rows: List<Row>) {
        val innerH = 265f + rows.size * 200f + 40f
        val inner = DesignLayout(ctx, theme, designH = innerH, fixedScale = { d.scale })
        rows.forEachIndexed { i, r ->
            val y = 265 + i * 200 - 153  // unutarnji raspored pocinje ispod zaglavlja (y = 153)
            when (r) {
                is Row.Check -> {
                    inner.add(GText(ctx, r.label, 64f), 210, y, 1220, 130)
                    val c = inner.add(GCheck(ctx, theme, r.get()) { r.set(it) }, 1460, y, 130, 130)
                    checkViews += c to r.get
                }
                is Row.Value -> {
                    inner.add(GText(ctx, r.label, 64f), 210, y, 450, 130)
                    val b = inner.add(GButton(ctx, r.value(), theme, 56f), 670, y, 920, 130)
                    b.setOnClickListener { r.onClick() }
                    valueViews += b to r.value
                }
                is Row.Action -> {
                    val b = inner.add(GButton(ctx, r.label, theme), 210, y, 1380, 130)
                    b.setOnClickListener { r.onClick() }
                }
            }
        }
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.addView(inner)
        d.add(sv, 0, 153, 1800, 1047, DesignLayout.Span.FULL_WIDTH)
    }

    protected fun refreshRows() {
        valueViews.forEach { (b, v) -> b.text = v() }
        checkViews.forEach { (c, g) -> c.checked = g() }
    }

    // ---------- dijalozi

    protected fun editText(title: String, current: String, maxLen: Int = 10, onDone: (String) -> Unit) {
        val et = EditText(ctx)
        et.setText(current)
        et.setSingleLine()
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        et.filters = arrayOf(InputFilter.LengthFilter(maxLen))
        et.setSelection(et.text.length)
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setMessage("Up to $maxLen characters (TID)")
            .setView(et)
            .setPositiveButton("OK") { _, _ -> onDone(et.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    protected fun pick(title: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), selected) { dlg, which ->
                onPick(which)
                dlg.dismiss()
            }
            .show()
    }
}

// =================================================================== POCETNI EKRAN

class HomeScreen(host: UiHost) : Screen(host) {
    private val connected = Hub.uiConnected
    override val theme = if (connected) Theme.BLUE else Theme.RED

    override fun build(d: DesignLayout) {
        sideGlow(d)
        d.add(GradientView(ctx, headerOf(theme.accent), withAlpha(headerOf(theme.accent), 0.2f), horizontal = false), 0, 0, 1800, 148, DesignLayout.Span.FULL_WIDTH)
        d.add(GlowLine(ctx, vertical = false), 0, 143, 1800, 10, DesignLayout.Span.FULL_WIDTH)
        d.add(GText(ctx, "Corsa C Preferences", 64f, Gravity.CENTER), 300, 10, 1200, 120)

        val items = listOf(
            "Ambient Lighting" to { host.push(AmbientScreen(host)) },
            "Center Display" to { host.push(CenterDisplayScreen(host)) },
            "User Preferences" to { host.push(UserPrefsScreen(host)) },
            "Car Setup" to { host.push(CarSetupScreen(host)) },
        )
        items.forEachIndexed { i, (label, open) ->
            val b = button(d, label, 263, 203 + i * 197, 584) {
                if (Hub.uiConnected) open() else notAvailable()
            }
            if (!connected) b.alpha = 0.6f  // Figma: crni sloj 30 % + manje zasicenja preko gumba
        }

        val logo = d.add(ImageView(ctx), 1056, 279, 597, 465)
        logo.setImageResource(R.drawable.d_opel_logo)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        logo.colorFilter = tintFilter(theme.accent)
        logo.setOnLongClickListener {
            Hub.demo = !Hub.demo  // demo nacin za probu bez Arduina
            host.home()
            true
        }

        d.add(GText(ctx, "OAI Module", 64f, Gravity.CENTER), 1054, 800, 600, 90)
        val state = when {
            Hub.connected -> "Connected"
            Hub.demo -> "Demo"
            else -> "Not Connected"
        }
        d.add(GText(ctx, state, 64f, Gravity.CENTER), 1054, 880, 600, 90)

        button(d, "Advanced Settings", 263, 1018, 1365) { host.push(AdvancedScreen(host)) }
    }

    /** Ako se Arduino spoji ili odspoji, pocetni ekran mijenja boju. */
    override fun refresh() {
        if (Hub.uiConnected != connected) host.home()
    }
}

// =================================================================== AMBIENT LIGHTING

class AmbientScreen(host: UiHost) : Screen(host) {
    override val theme = Theme.BLUE

    override fun build(d: DesignLayout) {
        sideGlow(d)
        header(d, "Ambient Lighting")
        button(d, "Footwell Lighting", 440, 351, 920) { host.push(FootwellScreen(host)) }
        button(d, "Courtesy Lighting", 440, 535, 920) { notAvailable() }
    }
}

/** Isjecak interijera (podni dio) s bojom iza prozirnog poda, za sliku na ekranu Footwell. */
class InteriorView(ctx: android.content.Context, private val crop: Rect?) : View(ctx) {
    var color: Int = Color.BLACK
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)

    companion object {
        private var cached: Bitmap? = null
        fun bitmap(ctx: android.content.Context): Bitmap =
            cached ?: BitmapFactory.decodeResource(ctx.resources, R.drawable.d_interior).also { cached = it }
    }

    override fun onDraw(c: Canvas) {
        val bmp = bitmap(context)
        val src = crop ?: Rect(0, 0, bmp.width, bmp.height)
        val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
        // boja samo ispod poda (kao pravokutnik 0,166 - 1800,1180 u Figmi)
        p.color = color
        val sy = height / src.height().toFloat()
        val top = ((166 - src.top) * sy).coerceAtLeast(0f)
        val bottom = ((1180 - src.top) * sy).coerceAtMost(height.toFloat())
        c.drawRect(0f, top, width.toFloat(), bottom, p)
        c.drawBitmap(bmp, src, dst, p)
    }
}

class FootwellScreen(host: UiHost) : Screen(host) {
    override val theme = Theme.BLUE
    private lateinit var glow: View

    override fun build(d: DesignLayout) {
        sideGlow(d)
        // Figma (Frame 10): iza gornje slicice je pravokutnik u boji, vidi se kroz prozirni tepih
        glow = d.add(View(ctx), 994, 282, 533, 325)
        listOf(217, 647).forEach { y ->
            val img = d.add(ImageView(ctx), 946, y, 648, 430)
            img.setImageResource(R.drawable.d_footwell_thumb)
            img.scaleType = ImageView.ScaleType.FIT_XY
        }
        header(d, "Footwell Lighting")
        button(d, "Footwell Lighting Colour", 48, 360, 875) { host.push(ColourScreen(host)) }
        button(d, "Footwell Lighting Animation", 48, 776, 875) { host.push(AnimationScreen(host)) }
        refresh()
    }

    override fun refresh() {
        glow.setBackgroundColor(if (Prefs.power) ColourScreen.swatchFor(Prefs.colorName) else Color.BLACK)
    }
}

// =================================================================== BOJA

class ColourScreen(host: UiHost) : Screen(host) {
    override val theme = Theme.BLUE

    data class Swatch(val name: String, val ui: Int, val r: Int, val g: Int, val b: Int)

    companion object {
        // boja kvadratica iz Figme + boja koju dobiva LED traka
        val swatches = listOf(
            Swatch("RED", 0xFFEE0C0C.toInt(), 255, 0, 0),
            Swatch("ORANGE", 0xFFCD7B00.toInt(), 255, 128, 0),
            Swatch("YELLOW", 0xFFEDE412.toInt(), 255, 255, 0),
            Swatch("GREEN", 0xFF24ED12.toInt(), 0, 255, 0),
            Swatch("BLUE", 0xFF072AE3.toInt(), 0, 0, 255),
            Swatch("VIOLET", 0xFF6512ED.toInt(), 128, 0, 255),
        )

        fun swatchFor(name: String): Int = swatches.firstOrNull { it.name == name }?.ui
            ?: Color.rgb(Prefs.r, Prefs.g, Prefs.b)
    }

    private lateinit var interior: InteriorView
    private var off: GText? = null
    private val marks = mutableListOf<ImageView>()
    private lateinit var slider: BrightnessSlider
    private var lastBrightSend = 0L

    override fun build(d: DesignLayout) {
        interior = d.add(InteriorView(ctx, null), 0, 0, 1800, 1200)
        d.add(GradientView(ctx, withAlpha(theme.accent, 0f), withAlpha(theme.accent, 0.21f), horizontal = false),
            0, 937, 1800, 263, DesignLayout.Span.FULL_WIDTH)
        off = header(d, "Footwell Lighting", offButton = true)

        swatches.forEachIndexed { i, s ->
            val x = 71 + i * 242
            val v = d.add(View(ctx), x, 985, 210, 140)
            v.setBackgroundColor(s.ui)
            v.setOnClickListener { choose(s) }
            val mark = d.add(ImageView(ctx), x, 985, 210, 140)
            mark.setImageResource(R.drawable.d_swatch_selected)
            mark.scaleType = ImageView.ScaleType.FIT_XY
            mark.isClickable = false
            marks += mark
        }
        d.add(GlowLine(ctx, vertical = true), 1523, 985, 10, 140)

        slider = d.add(BrightnessSlider(ctx, theme, Prefs.brightness) { v, done ->
            Prefs.brightness = v
            val now = System.currentTimeMillis()
            if (done || now - lastBrightSend > 60) {
                lastBrightSend = now
                Hub.send("B${Prefs.brightnessByte()}")
            }
        }, 1582, 227, 144, 747)
        slider.visibility = View.GONE

        val icon = d.add(ImageView(ctx), 1600, 1001, 108, 108)
        icon.setImageResource(R.drawable.ic_d_brightness)
        icon.setOnClickListener {
            slider.visibility = if (slider.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        refresh()
    }

    private fun choose(s: Swatch) {
        Prefs.r = s.r
        Prefs.g = s.g
        Prefs.b = s.b
        Prefs.colorName = s.name
        Hub.send("C${s.r},${s.g},${s.b}")
        if (!Prefs.power) {
            Prefs.power = true
            Hub.send("P1")
        }
        ledMessage("LED ${s.name}")
        refresh()
    }

    override fun refresh() {
        interior.color = if (Prefs.power) swatchFor(Prefs.colorName) else Color.BLACK
        off?.text = powerLabel()
        swatches.forEachIndexed { i, s -> marks[i].visibility = if (s.name == Prefs.colorName) View.VISIBLE else View.INVISIBLE }
    }
}

// =================================================================== ANIMACIJE

class AnimationScreen(host: UiHost) : Screen(host) {
    override val theme = Theme.BLUE

    /** Naziv u listi -> mod na Arduinu (null = jos ne postoji). */
    private val items = listOf(
        "Static Colour" to 0,
        "Shooting Star" to 1,
        "Music Sync" to null,
        "Raindrops" to 2,
        "Random" to 3,
        "Calm" to 4,
        "Smoke" to 5,
        "Thunder" to 6,
        "Changing Colour" to 7,
    )
    private val texts = mutableListOf<GText>()
    private var off: GText? = null

    override fun build(d: DesignLayout) {
        val inner = DesignLayout(ctx, theme, designH = 250f + items.size * 172f + 250f, fixedScale = { d.scale })
        items.forEachIndexed { i, (name, mode) ->
            val t = inner.add(GText(ctx, name, 96f, Gravity.CENTER), 253, 250 + i * 172, 1294, 130)
            t.setOnClickListener {
                if (mode == null) notAvailable() else {
                    Prefs.mode = mode
                    Hub.send("M$mode")
                    ledMessage(name.uppercase().take(10))
                    refresh()
                }
            }
            texts += t
        }
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.addView(inner)
        d.add(sv, 0, 209, 1800, 906, DesignLayout.Span.FULL_WIDTH)
        // tamni prijelaz gore i dolje, kao kotacic
        d.add(GradientView(ctx, Color.BLACK, 0x00000000, horizontal = false), 0, 209, 1800, 250, DesignLayout.Span.FULL_WIDTH)
            .isClickable = false
        d.add(GradientView(ctx, 0x00000000, Color.BLACK, horizontal = false), 0, 865, 1800, 250, DesignLayout.Span.FULL_WIDTH)
            .isClickable = false
        sideGlow(d)  // u Figmi je bocni sjaj iznad tamnih prijelaza liste
        d.add(GlowLine(ctx, vertical = true), 503, 209, 10, 906)
        d.add(GlowLine(ctx, vertical = true), 1270, 209, 10, 906)
        off = header(d, "Footwell Lighting Animation", offButton = true)
        refresh()
    }

    override fun refresh() {
        off?.text = powerLabel()
        items.forEachIndexed { i, (_, mode) -> texts[i].highlight = mode == Prefs.mode }
    }
}

// =================================================================== CENTER DISPLAY (TID)

class CenterDisplayScreen(host: UiHost) : Screen(host) {
    override val theme = Theme.YELLOW

    companion object {
        val tidStyles = listOf("Appear", "Slide in", "Pass through", "Typewriter", "From centre", "Blink")
    }

    override fun build(d: DesignLayout) {
        sideGlow(d)
        header(d, "Center Display Preferences")
        rows(d, listOf(
            Row.Check("Welcome message", { Prefs.welcomeOn }) {
                Prefs.welcomeOn = it
                Hub.send("G${if (it) 1 else 0}")
            },
            Row.Value("Welcome text", { Prefs.welcome1 }) {
                editText("Welcome text", Prefs.welcome1) { t ->
                    Prefs.welcome1 = TextUtil.forTid(t, 10).ifEmpty { "WILLKOMMEN" }
                    Hub.send("E${Prefs.welcome1}")
                    refreshRows()
                }
            },
            Row.Value("Animation", { tidStyles[Prefs.welcomeStyle.coerceIn(0, 5)] }) {
                pick("Welcome animation", tidStyles, Prefs.welcomeStyle) { i ->
                    Prefs.welcomeStyle = i
                    Hub.send("A$i")
                    Hub.send("W")  // odmah pokazi kako izgleda
                    refreshRows()
                }
            },
            Row.Check("Ambient light change alert", { Prefs.ledMessages }) { Prefs.ledMessages = it },
            Row.Check("Static message", { Prefs.staticOn }) {
                Prefs.staticOn = it
                Hub.send("I${Prefs.idleForTid()}")
            },
            Row.Value("Static text", { Prefs.idleText }) {
                editText("Static message", Prefs.idleText) { t ->
                    Prefs.idleText = TextUtil.forTid(t, 10).ifEmpty { "OPEL CORSA" }
                    Hub.send("I${Prefs.idleForTid()}")
                    refreshRows()
                }
            },
            Row.Check("Song and artist", { Prefs.showSong }) {
                Prefs.showSong = it
                Hub.refreshNowText()
            },
            Row.Check("Volume", { Prefs.showVolume }) { Prefs.showVolume = it },
        ))
    }
}

// =================================================================== USER PREFERENCES

class UserPrefsScreen(host: UiHost) : Screen(host) {
    override val theme = Theme.BLUE

    override fun build(d: DesignLayout) {
        sideGlow(d)
        header(d, "User Preferences")
        rows(d, listOf(
            Row.Value("User Name", { Prefs.userName }) {
                editText("User Name", Prefs.userName) { t ->
                    Prefs.userName = t.ifEmpty { "Leo" }
                    Hub.send("F${Prefs.welcome2ForTid()}")
                    refreshRows()
                }
            },
            Row.Check("Personal welcome message", { Prefs.personalWelcome }) {
                Prefs.personalWelcome = it
                Hub.send("F${Prefs.welcome2ForTid()}")
            },
        ))
    }
}

// =================================================================== CAR SETUP

class CarSetupScreen(host: UiHost) : Screen(host) {
    override val theme = Theme.BLUE

    companion object {
        val ledStyles = listOf("From the edges", "Fade in", "Sweep", "Sparkle")
    }

    override fun build(d: DesignLayout) {
        sideGlow(d)
        header(d, "Car Setup")
        rows(d, listOf(
            Row.Value("LED welcome", { ledStyles[Prefs.ledWelcomeStyle.coerceIn(0, 3)] }) {
                pick("LED welcome animation", ledStyles, Prefs.ledWelcomeStyle) { i ->
                    Prefs.ledWelcomeStyle = i
                    Hub.send("K$i")
                    Hub.send("W")
                    refreshRows()
                }
            },
            Row.Check("LED reacts to volume and song", { Prefs.react }) {
                Prefs.react = it
                Hub.send("R${if (it) 1 else 0}")
            },
            Row.Value("Media access", { if (Perms.hasMedia(ctx)) "On" else "Off – tap" }) { Perms.openMedia(ctx) },
            Row.Value("Volume reading", { if (Perms.hasVolume(ctx)) "On" else "Off – tap" }) { Perms.openVolume(ctx) },
            Row.Check("Auto-update OAI module", { Prefs.autoUpdateFw }) { Prefs.autoUpdateFw = it },
            Row.Action("All colours and classic screen") { ctx.startActivity(Intent(ctx, ClassicActivity::class.java)) },
        ))
    }

    override fun refresh() = refreshRows()
}

// =================================================================== ADVANCED SETTINGS

class AdvancedScreen(host: UiHost) : Screen(host) {
    override val theme = Theme.GREEN
    private lateinit var status: GButton
    private lateinit var fw: GButton
    private lateinit var fwState: GText

    override fun build(d: DesignLayout) {
        sideGlow(d)
        header(d, "Advanced Settings")
        d.add(GText(ctx, "Opel Advanced Interior Status", 64f), 210, 265, 950, 130)
        status = d.add(GButton(ctx, "", theme), 1170, 265, 420, 130)
        d.add(GText(ctx, "OAI firmware", 64f), 210, 466, 950, 130)
        fw = d.add(GButton(ctx, "", theme), 1170, 466, 420, 130)
        fw.setOnClickListener { askUpdate() }
        fwState = d.add(GText(ctx, "", 36f, plainColor = 0xFF9AA0A8.toInt()), 210, 596, 1380, 60)
        button(d, "OAI Debugging", 210, 666, 1380) { host.push(DebugScreen(host)) }
        button(d, "Assist", 210, 866, 670) { host.push(AssistScreen(host)) }
        button(d, "Credits", 920, 866, 670) { host.push(CreditsScreen(host)) }
        refresh()
    }

    private fun askUpdate() {
        val link = Hub.link
        if (!Hub.connected || link == null) {
            notAvailable()
            return
        }
        val b = hr.leo.corsalight.Firmware.bundledVersion(ctx)
        host.popup("Update OAI module to v$b?", "Update") { link.updateFirmware() }
    }

    override fun refresh() {
        status.text = if (Hub.connected) "Connected" else if (Hub.demo) "Demo" else "Disconnected"
        val b = hr.leo.corsalight.Firmware.bundledVersion(ctx)
        val v = Hub.fwVersion
        fw.text = when {
            Hub.flashState.isNotEmpty() -> "Updating…"
            !Hub.connected || v < 0 -> "v$b in app"
            v < b -> "v$v → v$b"
            else -> "v$v"
        }
        fwState.text = when {
            Hub.flashState.isNotEmpty() -> Hub.flashState
            Hub.connected && v in 0 until b -> "Tap to update the OAI module from the app"
            Hub.connected && v >= b -> "Up to date"
            else -> ""
        }
    }
}

class DebugScreen(host: UiHost) : Screen(host) {
    override val theme = Theme.GREEN
    private lateinit var log: TextView

    override fun build(d: DesignLayout) {
        sideGlow(d)
        header(d, "OAI Debugging")
        button(d, "Test OAI and TID", 210, 200, 670, 110) {
            if (!Hub.connected) notAvailable() else {
                Hub.log("Test: sending D and T to the Arduino…")
                Hub.send("D")
                Hub.send("T")
            }
        }
        button(d, "Relearn volume", 920, 200, 670, 110) {
            val r = VolumeReader.instance
            if (r == null) host.popup("Volume reading is off") else {
                r.relearn()
                host.popup("Change the volume once")
            }
        }
        log = TextView(ctx)
        log.typeface = Typeface.MONOSPACE
        log.setTextColor(0xFFB7B7B7.toInt())
        log.setTextIsSelectable(true)
        val sv = ScrollView(ctx)
        sv.addView(log)
        d.add(sv, 210, 340, 1380, 840)
        refresh()
    }

    override fun refresh() {
        log.textSize = 12f
        val head = "OAI: ${if (Hub.connected) "connected (${Hub.usbInfo})" else "not connected"}\n" +
            "Media: ${if (Perms.hasMedia(ctx)) "on" else "off"}   Volume reading: ${if (VolumeReader.instance != null) "on" else "off"}" +
            "   Last volume: ${if (Hub.volume >= 0) "${Hub.volume}/${Hub.volumeMax}" else "-"}\n\n"
        log.text = head + Hub.diag.joinToString("\n")
    }
}

class AssistScreen(host: UiHost) : Screen(host) {
    override val theme = Theme.BLUE

    override fun build(d: DesignLayout) {
        sideGlow(d)
        header(d, "Assist", homeButton = false, centeredTitle = true)
        val panel = LinearLayout(ctx)
        panel.orientation = LinearLayout.VERTICAL
        val t = TextView(ctx)
        t.setTextColor(0xFF1A1A1A.toInt())
        t.textSize = 15f
        t.setLineSpacing(0f, 1.15f)
        t.text = ASSIST_TEXT
        val pad = 24
        t.setPadding(pad, pad, pad, pad)
        val sv = ScrollView(ctx)
        sv.addView(t)
        sv.setBackgroundColor(0xFFD4D4D4.toInt())
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val frame = FrameDrawable(theme.accent, filled = false)
            frame.border = 5f
            sv.foreground = frame
        }
        d.add(sv, 132, 228, 1536, 883)
    }
}

private const val ASSIST_TEXT = """1. Close the app from the recents menu
  • Open the head unit's recent apps list and close Interior Light, then open it again.

2. Restart the head unit
  • Turn the ignition off completely, wait a few seconds and turn it on again.

3. The Center Display (TID) shows nothing
  • If the date disappears but the text stays empty, the TID receives the data with errors: check the ground (GND) between the OAI module and TID pin 6, and the contacts on SDA (pin 11) and SCL (pin 10).
  • If the TID does not react at all, it may be stuck: unplug the connector on the back of the TID for 2 minutes. The TID has permanent power, so the ignition alone does not reset it.

4. The OAI module is not connected
  • Check the USB cable between the head unit and the Arduino.
  • Open Advanced Settings → OAI Debugging and press "Test OAI and TID".

5. Volume or song is not shown
  • Car Setup → Media access and Volume reading must be On. After a full power off the head unit turns Volume reading off, so turn it on again.

6. Disconnect the car battery (last resort)
  • Turn the car off, disconnect the negative (black) terminal for about 2 minutes and reconnect it."""

// =================================================================== CREDITS

class CreditsScreen(host: UiHost) : Screen(host) {
    override val theme = Theme.GREEN
    private var taps = 0
    private var lastTap = 0L

    override fun build(d: DesignLayout) {
        sideGlow(d)
        header(d, "Credits")
        d.add(GText(ctx, "Corsa C Preferences", 80f, Gravity.CENTER), 200, 215, 1400, 120)

        d.add(GText(ctx, "App, OAI module and TID", 44f, Gravity.CENTER, plainColor = 0xFF9AA0A8.toInt()), 200, 400, 1400, 70)
        d.add(GText(ctx, "Leo Jukić", 88f, Gravity.CENTER), 200, 470, 1400, 130)

        d.add(GText(ctx, "Design", 44f, Gravity.CENTER, plainColor = 0xFF9AA0A8.toInt()), 200, 680, 1400, 70)
        val andrija = d.add(GText(ctx, "Andrija Pulić", 88f, Gravity.CENTER), 500, 750, 800, 130)
        andrija.rainbow = Prefs.rainbowAndrija
        // easter egg: 10 brzih pritisaka -> ime u duginim bojama (i natrag)
        andrija.setOnClickListener {
            val now = System.currentTimeMillis()
            taps = if (now - lastTap < 1500) taps + 1 else 1
            lastTap = now
            if (taps >= 10) {
                taps = 0
                Prefs.rainbowAndrija = !Prefs.rainbowAndrija
                andrija.rainbow = Prefs.rainbowAndrija
                if (Prefs.rainbowAndrija) Hub.send("Y5000")  // 5 s duge na LED traci
            }
        }

        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        d.add(GText(ctx, "Interior Light $version", 36f, Gravity.CENTER, plainColor = 0xFF6F757D.toInt()), 200, 1080, 1400, 60)
    }
}
