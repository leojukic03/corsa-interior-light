package hr.leo.corsaradio.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView

/**
 * Skala za ugadanje u okviru iz dizajna: crtice za cijeli opseg, oznake MHz (kHz za AM),
 * tockice za spremljene stanice i kazaljka u boji sucelja. Povuci prstom = nova frekvencija.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class FreqDial(ctx: Context, private val theme: Theme, private val onTune: (Int) -> Unit) : View(ctx), Scalable {
    var min = 8750
    var max = 10800
    var step = 5
    var am = false
    var freq = 9780
        set(v) {
            if (!dragging) field = v
            invalidate()
        }
    var marks: List<Int> = emptyList()
        set(v) { field = v; invalidate() }

    private var dragging = false
    private var dragFreq = 0
    private val frame = FrameDrawable(theme.accent, filled = false)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var s = 0.5f
    private val pad = 70f  // od ruba okvira do krajeva skale (dizajn px)

    override fun onScale(scale: Float) {
        s = scale
        frame.border = 10f * scale
    }

    private fun xOf(f: Int): Float {
        val l = pad * s
        val r = width - pad * s
        return l + (r - l) * (f - min).toFloat() / (max - min).coerceAtLeast(1)
    }

    private fun freqAt(x: Float): Int {
        val l = pad * s
        val r = width - pad * s
        val raw = min + (x - l) / (r - l) * (max - min)
        val st = step.coerceAtLeast(1)
        return (min + Math.round((raw - min) / st) * st).coerceIn(min, max)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.shader = null
        p.color = 0x55000000
        c.drawRect(0f, 0f, w, h, p)
        frame.setBounds(0, 0, width, height)
        frame.draw(c)

        // crtice: svaki 1 MHz duga, svakih 0.5 MHz kratka, broj svaka 2 MHz.
        // FM je u 10 kHz, AM u kHz, pa su isti brojevi za AM 100 / 50 / 200 kHz.
        val major = 100
        val minor = 50
        p.strokeCap = Paint.Cap.ROUND
        p.textAlign = Paint.Align.CENTER
        p.textSize = 40f * s
        val base = h * 0.70f
        var f = ((min + minor - 1) / minor) * minor
        while (f <= max) {
            val x = xOf(f)
            val isMajor = f % major == 0
            p.color = if (isMajor) 0xFFD9D9D9.toInt() else 0xFF6E747C.toInt()
            p.strokeWidth = (if (isMajor) 5f else 3f) * s
            c.drawLine(x, base, x, base - (if (isMajor) 46f else 24f) * s, p)
            if (f % 200 == 0) {
                p.color = 0xFFB7B7B7.toInt()
                p.style = Paint.Style.FILL
                c.drawText(if (am) "$f" else "${f / 100}", x, h * 0.30f, p)
            }
            f += minor
        }
        // spremljene stanice
        p.color = withAlpha(theme.accent, 0.9f)
        for (m in marks) if (m in min..max) c.drawCircle(xOf(m), base + 22f * s, 7f * s, p)

        // kazaljka: svijetla crta s mekim sjajem u boji sucelja
        val x = xOf(if (dragging) dragFreq else freq)
        val glow = 34f * s
        p.shader = LinearGradient(x - glow, 0f, x + glow, 0f,
            intArrayOf(withAlpha(theme.accent, 0f), withAlpha(theme.accent, 0.55f), withAlpha(theme.accent, 0f)),
            null, Shader.TileMode.CLAMP)
        c.drawRect(x - glow, 14f * s, x + glow, h - 14f * s, p)
        p.shader = LinearGradient(0f, 14f * s, 0f, h - 14f * s,
            intArrayOf(mix(theme.accent, Color.WHITE, 0.5f), theme.accent, darkOf(theme.accent)), null, Shader.TileMode.CLAMP)
        c.drawRect(x - 4f * s, 14f * s, x + 4f * s, h - 14f * s, p)
        p.shader = RadialGradient(x, base + 22f * s, 16f * s, mix(theme.accent, Color.WHITE, 0.3f), darkOf(theme.accent), Shader.TileMode.CLAMP)
        c.drawCircle(x, base + 22f * s, 16f * s, p)
        p.shader = null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragFreq = freqAt(e.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                freq = freqAt(e.x)
                onTune(freq)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

/**
 * Vodoravni niz gumba (spremljene stanice) u koordinatama dizajna: svaki gumb w x h,
 * razmak pitch. Mjerilo uzima od vanjskog DesignLayouta, a skrola se u HorizontalScrollViewu.
 */
class ButtonRow(ctx: Context, private val scale: () -> Float, private val w: Float, private val h: Float,
                private val pitch: Float, private val padStart: Float) : ViewGroup(ctx) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val s = scale()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            (c as? Scalable)?.onScale(s)
            c.measure(MeasureSpec.makeMeasureSpec((w * s).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((h * s).toInt(), MeasureSpec.EXACTLY))
        }
        val total = (2 * padStart + (childCount - 1).coerceAtLeast(0) * pitch + w) * s
        setMeasuredDimension(total.toInt(), (h * s).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = scale()
        for (i in 0 until childCount) {
            val x = ((padStart + i * pitch) * s).toInt()
            getChildAt(i).layout(x, 0, x + (w * s).toInt(), (h * s).toInt())
        }
    }
}

/** HorizontalScrollView bez trake i "gume" na krajevima, kao liste u Interior Lightu. */
class Strip(ctx: Context) : HorizontalScrollView(ctx) {
    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }
}

// ---------------------------------------------------------------- logo, stranice, log

/** Okvir s logotipom stanice. Bez slike pokazuje ime stanice. */
@SuppressLint("ViewConstructor")
class LogoView(ctx: Context, theme: Theme) : View(ctx), Scalable {
    private val frame = FrameDrawable(theme.accent)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bmp: android.graphics.Bitmap? = null
    private var label = ""

    fun set(b: android.graphics.Bitmap?, ps: String) {
        if (b !== bmp || ps != label) {
            bmp = b
            label = ps
            invalidate()
        }
    }

    override fun onScale(scale: Float) {
        frame.border = 10f * scale
    }

    override fun onDraw(c: Canvas) {
        frame.setBounds(0, 0, width, height)
        frame.draw(c)
        val pad = width * 0.1f
        val bw = width - 2 * pad
        val bh = height - 2 * pad
        val b = bmp
        if (b != null) {
            val k = minOf(bw / b.width, bh / b.height)
            val w = b.width * k
            val h = b.height * k
            val l = (width - w) / 2f
            val t = (height - h) / 2f
            c.drawBitmap(b, null, android.graphics.RectF(l, t, l + w, t + h), p)
        } else {
            p.color = 0xFFD9D9D9.toInt()
            p.textAlign = Paint.Align.CENTER
            val txt = label.ifEmpty { "FM" }
            p.textSize = (bw / (txt.length.coerceAtLeast(3) * 0.62f)).coerceAtMost(height * 0.32f)
            c.drawText(txt, width / 2f, height / 2f - (p.descent() + p.ascent()) / 2f, p)
        }
    }
}

/** Tockice ispod liste: koja je stranica spremljenih stanica aktivna. */
@SuppressLint("ViewConstructor")
class PageDots(ctx: Context, private val theme: Theme, private val count: Int) : View(ctx) {
    var page = 0
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val r = height * 0.28f
        val gap = height * 1.0f
        val total = (count - 1) * gap
        val x0 = (width - total) / 2f
        for (i in 0 until count) {
            p.color = if (i == page) theme.accent else 0xFF4A5058.toInt()
            c.drawCircle(x0 + i * gap, height / 2f, if (i == page) r * 1.25f else r, p)
        }
    }
}

/**
 * Lista koja se uvijek zaustavi na cijeloj stranici (sirina = jedna stranica).
 * Prati prst 1:1, a na otpustanju projicira zamah (kao iOS) i odabere stranicu prema tome kamo
 * je potez isao, pa do nje dode kriticno prigusenom oprugom koja preuzme brzinu prsta.
 * Dodir usred animacije je odmah uhvati (moze se prekinuti i vratiti).
 */
class PagedStrip(ctx: Context, private val onPage: (Int) -> Unit) : HorizontalScrollView(ctx) {
    var pages = 1
    var page = 0
        private set
    private var flung = false

    // opruga: kriticno prigusena (bez prebacivanja), odziv 0.35 s
    private val omega = (2 * Math.PI / 0.35).toFloat()
    private var springFrom = 0f
    private var springV = 0f
    private var springTarget = 0f
    private var springStart = 0L
    private var springing = false
    private val tick = object : Runnable {
        override fun run() {
            if (!springing) return
            val t = (android.os.SystemClock.uptimeMillis() - springStart) / 1000f
            val x0 = springFrom - springTarget
            val x = springTarget + (x0 + (springV + omega * x0) * t) * Math.exp((-omega * t).toDouble()).toFloat()
            if (t > 1.5f || Math.abs(x - springTarget) < 0.5f && t > 0.1f) {
                scrollTo(springTarget.toInt(), 0)
                springing = false
                return
            }
            scrollTo(Math.round(x), 0)
            postOnAnimation(this)
        }
    }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    private fun motionOff(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()

    /** Kamo bi zamah odnio listu (Appleova projekcija, deceleracija 0.998). */
    private fun project(v: Float) = v / 1000f * 0.998f / (1f - 0.998f)

    fun goTo(p: Int, smooth: Boolean = true, velocity: Float = 0f) {
        val t = p.coerceIn(0, (pages - 1).coerceAtLeast(0))
        val changed = t != page
        page = t
        if (!smooth || width == 0 || motionOff()) {
            springing = false
            scrollTo(t * width, 0)
        } else {
            springFrom = scrollX.toFloat()
            springTarget = (t * width).toFloat()
            springV = velocity
            springStart = android.os.SystemClock.uptimeMillis()
            if (!springing) {
                springing = true
                postOnAnimation(tick)
            }
        }
        if (changed) onPage(t)
    }

    override fun fling(velocityX: Int) {
        flung = true
        val w = width.coerceAtLeast(1)
        val projected = scrollX + project(velocityX.toFloat())
        val target = Math.round(projected / w).coerceIn(page - 1, page + 1)  // najvise jedna stranica po potezu
        goTo(target, velocity = velocityX.toFloat())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            springing = false  // uhvati listu tamo gdje je sad
            flung = false
        }
        val r = super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!flung) goTo(if (width > 0) Math.round(scrollX / width.toFloat()) else 0)
            flung = false
        }
        return r
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) springing = false
        return super.onInterceptTouchEvent(e)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrollTo(page * w, 0)
    }
}

/** Tekst loga u monospace fontu koji se skrola. */
class LogView(ctx: Context) : android.widget.ScrollView(ctx), Scalable {
    private val tv = android.widget.TextView(ctx).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        setTextColor(0xFFD9D9D9.toInt())
    }

    init {
        addView(tv)
        isVerticalScrollBarEnabled = false
        setBackgroundColor(0x66000000)
    }

    override fun onScale(scale: Float) {
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30f * scale)
        val pad = (16f * scale).toInt()
        tv.setPadding(pad, pad, pad, pad)
    }

    fun show(text: String) {
        val atBottom = tv.height - height - scrollY < 80
        tv.text = text
        if (atBottom) post { fullScroll(View.FOCUS_DOWN) }
    }
}

/** Spremljena stanica: logo gore, ime (ili frekvencija) ispod. Bez loga gore pise frekvencija. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PresetTile(ctx: Context, private val theme: Theme) : View(ctx), Scalable {
    private val frame = FrameDrawable(theme.accent)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var s = 0.5f
    private var bmp: android.graphics.Bitmap? = null
    private var name = ""
    private var freq = ""
    var active = false
        set(v) { if (field != v) { field = v; invalidate() } }

    init {
        isClickable = true
        isLongClickable = true
    }

    fun set(b: android.graphics.Bitmap?, name: String, freq: String) {
        if (b !== bmp || name != this.name || freq != this.freq) {
            bmp = b
            this.name = name
            this.freq = freq
            invalidate()
        }
    }

    override fun onScale(scale: Float) {
        s = scale
        frame.border = 10f * scale
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {  // odziv na dodir odmah, ne na otpustanje
                frame.pressed = true
                invalidate()
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).setInterpolator(pressEase).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                frame.pressed = false
                invalidate()
                animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(pressEase).start()
            }
        }
        return super.onTouchEvent(e)
    }

    private fun fit(text: String, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var t = text
        while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    override fun onDraw(c: Canvas) {
        frame.accent = theme.accent
        frame.setBounds(0, 0, width, height)
        frame.draw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 22f * s
        val logoBottom = h * 0.68f
        p.textAlign = Paint.Align.CENTER
        val b = bmp
        if (b != null) {
            val bw = w - 2 * pad
            val bh = logoBottom - 2 * pad
            val k = minOf(bw / b.width, bh / b.height)
            val dw = b.width * k
            val dh = b.height * k
            val l = (w - dw) / 2f
            val t = pad + (bh - dh) / 2f
            c.drawBitmap(b, null, android.graphics.RectF(l, t, l + dw, t + dh), p)
        } else if (freq.isNotEmpty()) {
            p.color = 0xFFD9D9D9.toInt()
            p.textSize = 64f * s
            p.letterSpacing = -0.01f
            c.drawText(freq, w / 2f, logoBottom / 2f + pad / 2 - (p.descent() + p.ascent()) / 2f, p)
        }
        // ime ispod (sitni tekst: malo razmaknutiji)
        p.textSize = 38f * s
        p.letterSpacing = 0.02f
        p.color = if (active) mix(theme.accent, Color.WHITE, 0.25f) else 0xFFB7B7B7.toInt()
        val label = fit(name.ifEmpty { if (freq.isEmpty()) "—" else "" }, w - 2 * pad)
        val y = (logoBottom + h - pad) / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(label, w / 2f, y, p)
        if (active) {  // svjetla crta na dnu za trenutnu stanicu
            p.color = theme.accent
            c.drawRect(w * 0.25f, h - pad * 0.9f, w * 0.75f, h - pad * 0.6f, p)
        }
    }
}

/** Krivulja za stisak gumba (jaki ease-out). */
val pressEase = android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f)
