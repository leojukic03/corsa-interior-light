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
