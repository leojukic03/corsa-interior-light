package hr.leo.corsalight.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.min

/*
 * Mali "design system" za izgled iz Figme (Corsa C Preferences, Andrija Pulić).
 * Dizajn je nacrtan na 1800 x 1200. DesignLayout ga skalira da stane na ekran
 * (TS18: 1024 x 600) i centrira, a pozadinu, zaglavlje i rubne sjaje razvuce
 * preko cijele sirine. Sve mjere u kodu su u pikselima iz Figme.
 */

const val DESIGN_W = 1800f
const val DESIGN_H = 1200f

/**
 * Boja okvira po sekciji. U Figmi je sve nacrtano plavo (ili crveno) i preko ide
 * sloj u "hue" nacinu (zeleni #24ED12, zuti #EDE412), pa je ovdje isti ton s istom
 * svjetlinom kao plava #299BD0.
 */
enum class Theme(val accent: Int) {
    BLUE(0xFF299BD0.toInt()),     // normalno
    RED(0xFFD02929.toInt()),      // OAI modul nije spojen
    GREEN(0xFF37D129.toInt()),    // Advanced Settings
    YELLOW(0xFFD0C829.toInt()),   // Center Display
}

/** Tamni kraj prijelaza okvira (#06222F za plavu, #2F0606 za crvenu). */
fun darkOf(accent: Int) = mix(accent, Color.BLACK, 0.82f)

/** Pozadina zaglavlja (#0E1B21 za plavu, #210E0E za crvenu). */
fun headerOf(accent: Int) = mix(accent, Color.BLACK, 0.9f)

fun mix(a: Int, b: Int, t: Float): Int {
    val r = Color.red(a) + (Color.red(b) - Color.red(a)) * t
    val g = Color.green(a) + (Color.green(b) - Color.green(a)) * t
    val bl = Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t
    val al = Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t
    return Color.argb(al.toInt(), r.toInt(), g.toInt(), bl.toInt())
}

fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

/** View koji mijenja velicinu teksta / debljinu okvira s mjerilom dizajna. */
interface Scalable {
    fun onScale(scale: Float)
}

/**
 * Raspored u koordinatama dizajna. Djeca imaju x, y, w, h u pikselima iz Figme.
 * Span odlucuje sto se radi s praznim prostorom lijevo i desno (ekran je siri od 3:2).
 */
class DesignLayout(
    ctx: Context,
    val theme: Theme,
    private val designH: Float = DESIGN_H,
    private val fixedScale: (() -> Float)? = null,  // unutarnji raspored u ScrollViewu koristi mjerilo vanjskog
) : ViewGroup(ctx) {

    enum class Span { NONE, FULL_WIDTH, LEFT_EDGE, RIGHT_EDGE }

    class LP(val x: Float, val y: Float, val w: Float, val h: Float, val span: Span) : ViewGroup.LayoutParams(0, 0)

    var scale = 0.5f
        private set
    private var offX = 0f
    private var offY = 0f

    fun <T : View> add(v: T, x: Number, y: Number, w: Number, h: Number, span: Span = Span.NONE): T {
        addView(v, LP(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), span))
        return v
    }

    private fun rect(lp: LP, width: Int): RectF {
        val s = scale
        val l = when (lp.span) {
            Span.FULL_WIDTH, Span.LEFT_EDGE -> 0f
            else -> offX + lp.x * s
        }
        val r = when (lp.span) {
            Span.FULL_WIDTH, Span.RIGHT_EDGE -> width.toFloat()
            else -> offX + (lp.x + lp.w) * s
        }
        val t = offY + lp.y * s
        return RectF(l, t, r, t + lp.h * s)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val fs = fixedScale
        if (fs != null) {
            scale = fs()
            offX = (w - DESIGN_W * scale) / 2f
            offY = 0f
        } else {
            val h = MeasureSpec.getSize(heightSpec)
            scale = min(w / DESIGN_W, h / designH)
            offX = (w - DESIGN_W * scale) / 2f
            offY = (h - designH * scale) / 2f
        }
        val h = if (fs != null) (designH * scale).toInt() else MeasureSpec.getSize(heightSpec)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LP
            (c as? Scalable)?.onScale(scale)
            val r = rect(lp, w)
            c.measure(
                MeasureSpec.makeMeasureSpec(r.width().toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height().toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val rr = rect(c.layoutParams as LP, width)
            c.layout(rr.left.toInt(), rr.top.toInt(), rr.right.toInt(), rr.bottom.toInt())
        }
    }
}

// ---------------------------------------------------------------- tekst

/** Tekst s prijelazom sive u bijelo (kao "Linear glass white" u Figmi). */
@SuppressLint("ViewConstructor")
open class GText(
    ctx: Context,
    text: CharSequence,
    private val sizePx: Float,
    gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL,
    private val plainColor: Int? = null,
) : TextView(ctx), Scalable {

    var highlight = false
        set(v) {
            field = v
            updateShader()
        }

    /** Easter egg: duga koja se polako pomice kroz tekst. */
    var rainbow = false
        set(v) {
            field = v
            updateShader()
        }
    private var rainbowShader: LinearGradient? = null
    private val rainbowMatrix = Matrix()

    override fun onDraw(canvas: Canvas) {
        val rs = rainbowShader
        if (rainbow && rs != null) {
            val w = width.toFloat()
            val shift = (android.os.SystemClock.uptimeMillis() % 2500L) / 2500f * w
            rainbowMatrix.setTranslate(shift, 0f)
            rs.setLocalMatrix(rainbowMatrix)
            paint.shader = rs
        }
        super.onDraw(canvas)
        if (rainbow) postInvalidateOnAnimation()
    }

    init {
        this.text = text
        this.gravity = gravity
        includeFontPadding = false
        maxLines = 1
        setTextColor(plainColor ?: Color.WHITE)
    }

    override fun onScale(scale: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * scale)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader()
    }

    private fun updateShader() {
        if (rainbow && width > 0) {
            val colors = intArrayOf(0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFE600.toInt(), 0xFF34C759.toInt(),
                0xFF2F8CFF.toInt(), 0xFFAF52DE.toInt(), 0xFFFF3B30.toInt())
            rainbowShader = LinearGradient(0f, 0f, width.toFloat(), 0f, colors, null, Shader.TileMode.REPEAT)
            paint.shader = rainbowShader
        } else if (plainColor != null || height == 0) {
            paint.shader = null
        } else {
            val colors = if (highlight) intArrayOf(0xFF299BD0.toInt(), 0xFF77CDF4.toInt())
            else intArrayOf(0xFFB7B7B7.toInt(), 0xFFFFFFFF.toInt())
            paint.shader = LinearGradient(0f, height.toFloat(), 0f, 0f, colors, floatArrayOf(0.13f, 0.89f), Shader.TileMode.CLAMP)
        }
        invalidate()
    }
}

// ---------------------------------------------------------------- okvir (gumbi, kucice, polja)

/** Okvir od 10 px u boji sekcije, s "staklenim" bijelim odsjajem na gornjim kutovima. */
class FrameDrawable(var accent: Int, private val filled: Boolean = true) : Drawable() {
    var border = 5f
    var pressed = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val bw = border
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        if (filled) {
            // Figma: svijetli vrh (#90AEDA) koji brzo prelazi u #363E49, pa u #101113
            val mid = if (pressed) 0xFF4A5462.toInt() else 0xFF363E49.toInt()
            val light = mix(0xFF90AEDA.toInt(), mid, 0.85f)
            p.shader = LinearGradient(0f, bw, 0f, h - bw, intArrayOf(light, mid, 0xFF101113.toInt()),
                floatArrayOf(0f, 0.14f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(bw, bw, w - bw, h - bw, p)
        }
        // okvir: elipticni prijelaz iz boje teme u sredini prema tamnim kutovima
        val g = RadialGradient(0f, 0f, 1f, accent, darkOf(accent), Shader.TileMode.CLAMP)
        val m = Matrix()
        m.setScale(w * 0.52f, h * 1.09f)
        m.postTranslate(w / 2f, h / 2f)
        g.setLocalMatrix(m)
        p.shader = g
        p.style = Paint.Style.STROKE
        p.strokeWidth = bw
        c.drawRect(bw / 2, bw / 2, w - bw / 2, h - bw / 2, p)
        p.style = Paint.Style.FILL
        // odsjaj: bijelo na vrhu lijevog i desnog ruba i na krajevima gornjeg ruba
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.5f, 0xF0FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw, h * 0.5f, p)
        c.drawRect(w - bw, 0f, w, h * 0.5f, p)
        p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0xE0FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF, 0xE0FFFFFF.toInt()),
            floatArrayOf(0f, 0.12f, 0.88f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, bw * 0.6f, p)
        p.shader = null
        c.restore()
    }

    override fun setAlpha(alpha: Int) {
        p.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        p.colorFilter = cf
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Gumb iz dizajna: tamno punjenje, okvir u boji sekcije, tekst 64 px. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class GButton(ctx: Context, text: CharSequence, theme: Theme, sizePx: Float = 64f) :
    GText(ctx, text, sizePx, Gravity.CENTER), Scalable {
    private val frame = FrameDrawable(theme.accent)

    init {
        background = frame
        isClickable = true
        setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { frame.pressed = true; invalidate() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { frame.pressed = false; invalidate() }
            }
            false
        }
    }

    override fun onScale(scale: Float) {
        super.onScale(scale)
        frame.border = 10f * scale
        val pad = (30f * scale).toInt()
        setPadding(pad, 0, pad, 0)
    }

    fun setAccent(c: Int) {
        frame.accent = c
        invalidate()
    }
}

/** Kucica za kvacicu 130 x 130, kvacica je plavi kvadrat 80 x 80. */
@SuppressLint("ViewConstructor")
class GCheck(ctx: Context, private val theme: Theme, checked: Boolean, private val onChange: (Boolean) -> Unit) :
    View(ctx), Scalable {
    var checked = checked
        set(v) {
            field = v
            invalidate()
        }
    private val frame = FrameDrawable(theme.accent, filled = false)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var s = 0.5f

    init {
        isClickable = true
        setOnClickListener {
            this.checked = !this.checked
            onChange(this.checked)
        }
    }

    override fun onScale(scale: Float) {
        s = scale
        frame.border = 10f * scale
    }

    override fun onDraw(c: Canvas) {
        frame.setBounds(0, 0, width, height)
        frame.draw(c)
        if (checked) {
            val a = theme.accent
            p.shader = LinearGradient(0f, 25 * s, 0f, 105 * s,
                intArrayOf(mix(a, Color.WHITE, 0.2f), mix(a, Color.BLACK, 0.35f), mix(a, Color.BLACK, 0.65f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(25 * s, 25 * s, width - 25 * s, height - 25 * s, 5 * s, 5 * s, p)
        }
    }
}

// ---------------------------------------------------------------- crte i sjaji

/** Svijetla crta koja se gasi prema krajevima (horizontalna ili okomita). */
class GlowLine(ctx: Context, private val vertical: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val g = RadialGradient(0f, 0f, 1f, intArrayOf(0xFFD9D9D9.toInt(), 0xFFD9D9D9.toInt(), 0x00D9D9D9),
            floatArrayOf(0f, 0.585f, 1f), Shader.TileMode.CLAMP)
        val m = Matrix()
        m.setScale(w / 2f, h / 2f)
        m.postTranslate(w / 2f, h / 2f)
        g.setLocalMatrix(m)
        p.shader = g
        c.drawRect(0f, 0f, w, h, p)
    }

    @Suppress("unused")
    val isVertical get() = vertical
}

/** Linearni prijelaz u boji, npr. plavi sjaj na lijevom i desnom rubu ekrana. */
class GradientView(ctx: Context, private val from: Int, private val to: Int, private val horizontal: Boolean) : View(ctx) {
    private val p = Paint()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.shader = if (horizontal) LinearGradient(0f, 0f, w, 0f, from, to, Shader.TileMode.CLAMP)
        else LinearGradient(0f, 0f, 0f, h, from, to, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
    }
}

/** Filter za Opel logo: kromirani logo dobije ton boje sekcije (blend "saturation" iz Figme). */
fun tintFilter(accent: Int): ColorMatrixColorFilter {
    // svjetlina ostaje ista (krom ostaje krom), samo dobije ton boje
    val lum = floatArrayOf(0.299f, 0.587f, 0.114f)
    val r = Color.red(accent) / 255f
    val g = Color.green(accent) / 255f
    val b = Color.blue(accent) / 255f
    val la = (lum[0] * r + lum[1] * g + lum[2] * b).coerceAtLeast(0.05f)
    val k = 0.45f  // koliko boje
    fun ch(t: Float) = 1 + k * (t / la - 1)
    val m = ColorMatrix(floatArrayOf(
        lum[0] * ch(r), lum[1] * ch(r), lum[2] * ch(r), 0f, 0f,
        lum[0] * ch(g), lum[1] * ch(g), lum[2] * ch(g), 0f, 0f,
        lum[0] * ch(b), lum[1] * ch(b), lum[2] * ch(b), 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))
    return ColorMatrixColorFilter(m)
}

/** Brzo zamucenje (smanji pa povecaj sliku) za pozadinu prozoricica, kao blur u Figmi. */
fun blurredSnapshot(v: View, darken: Float = 0.45f): android.graphics.Bitmap? {
    if (v.width == 0 || v.height == 0) return null
    val small = android.graphics.Bitmap.createBitmap(v.width / 12 + 1, v.height / 12 + 1, android.graphics.Bitmap.Config.ARGB_8888)
    val c = Canvas(small)
    c.scale(1 / 12f, 1 / 12f)
    v.draw(c)
    // jos jednom smanji i povecaj za mekse zamucenje
    val tiny = android.graphics.Bitmap.createScaledBitmap(small, small.width / 2 + 1, small.height / 2 + 1, true)
    val out = android.graphics.Bitmap.createScaledBitmap(tiny, v.width, v.height, true)
    Canvas(out).drawColor(Color.argb((darken * 255).toInt(), 0, 0, 0))
    return out
}

// ---------------------------------------------------------------- klizac svjetline

/** Okomiti klizac 144 x 747 iz dizajna: gore = 100 %, dolje = 0 %. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class BrightnessSlider(ctx: Context, theme: Theme, value: Int, private val onChange: (Int, Boolean) -> Unit) :
    View(ctx), Scalable {
    var value = value.coerceIn(0, 100)
        private set
    private val frame = FrameDrawable(theme.accent, filled = false)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var s = 0.5f

    // u koordinatama dizajna, relativno na klizac (1582, 227)
    private val lineTop = 71f
    private val lineLen = 604f
    private val knob = 60f

    override fun onScale(scale: Float) {
        s = scale
        frame.border = 10f * scale
    }

    private fun knobCenterY(): Float {
        val travel = lineLen - knob
        return (lineTop + knob / 2 + travel * (1 - value / 100f)) * s
    }

    override fun onDraw(c: Canvas) {
        p.shader = null
        p.color = 0x55000000
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        frame.setBounds(0, 0, width, height)
        frame.draw(c)
        p.color = 0xFFB7B7B7.toInt()
        p.strokeWidth = 10f * s
        p.strokeCap = Paint.Cap.ROUND
        val cx = width / 2f
        c.drawLine(cx, lineTop * s, cx, (lineTop + lineLen) * s, p)
        val cy = knobCenterY()
        p.shader = RadialGradient(cx, cy, knob / 2 * s, 0xFF299BD0.toInt(), 0xFF06222F.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, knob / 2 * s, p)
        p.shader = null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val travel = (lineLen - knob) * s
                val top = (lineTop + knob / 2) * s
                val v = (100 * (1 - (e.y - top) / travel)).toInt().coerceIn(0, 100)
                if (v != value || e.action == MotionEvent.ACTION_UP) {
                    value = v
                    invalidate()
                    onChange(v, e.action == MotionEvent.ACTION_UP)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
