package hr.leo.corsalight

import android.app.Activity
import android.content.ComponentName
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import hr.leo.corsalight.ui.DesignLayout
import hr.leo.corsalight.ui.GButton
import hr.leo.corsalight.ui.GText
import hr.leo.corsalight.ui.GlowLine
import hr.leo.corsalight.ui.HomeScreen
import hr.leo.corsalight.ui.Screen
import hr.leo.corsalight.ui.Theme
import hr.leo.corsalight.ui.UiHost

/** Novi izgled "Corsa C Preferences" (Figma). Ekrani se slazu na stog, kucica vraca na pocetak. */
class MainActivity : Activity(), UiHost {

    override val activity: Activity get() = this

    private lateinit var container: FrameLayout
    private val stack = ArrayList<Screen>()
    private var popupView: View? = null
    private val refresh: () -> Unit = { stack.lastOrNull()?.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        LinkService.start(this)
        container = FrameLayout(this)
        container.setBackgroundColor(Color.BLACK)
        setContentView(container)
        home()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        Hub.addListener(refresh)
        if (Perms.hasMedia(this) && Build.VERSION.SDK_INT >= 24 && MediaListener.instance == null) {
            NotificationListenerService.requestRebind(ComponentName(this, MediaListener::class.java))
        }
        stack.lastOrNull()?.refresh()
    }

    override fun onPause() {
        Hub.removeListener(refresh)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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

    // ---------------- navigacija

    private fun show(s: Screen) {
        dismissPopup()
        val old = if (container.childCount > 0) container.getChildAt(container.childCount - 1) else null
        val v = s.create()
        v.alpha = 0f
        container.addView(v, FrameLayout.LayoutParams(-1, -1))
        // prijelaz kao u prototipu (dissolve)
        v.animate().alpha(1f).setDuration(220).withEndAction {
            if (old != null) container.removeView(old)
        }.start()
    }

    override fun push(s: Screen) {
        stack += s
        show(s)
    }

    override fun back() {
        if (popupView != null) {
            dismissPopup()
            return
        }
        if (stack.size <= 1) {
            finish()
            return
        }
        stack.removeAt(stack.size - 1)
        // prethodni ekran se gradi ispocetka, da pokaze svjeze vrijednosti
        val prev = stack.removeAt(stack.size - 1)
        push(prev.javaClass.getConstructor(UiHost::class.java).newInstance(this))
    }

    override fun home() {
        stack.clear()
        push(HomeScreen(this))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    // ---------------- "Feature currently not available"

    override fun popup(message: String, button: String, onOk: () -> Unit) {
        dismissPopup()
        val theme = stack.lastOrNull()?.theme ?: Theme.BLUE
        val d = DesignLayout(this, theme)
        // Figma: pozadina iza prozoricica je zamucena
        val blur = hr.leo.corsalight.ui.blurredSnapshot(container)
        if (blur != null) d.background = android.graphics.drawable.BitmapDrawable(resources, blur)
        else d.setBackgroundColor(0xD0000000.toInt())
        d.isClickable = true
        d.add(GText(this, message, 64f, Gravity.CENTER), 200, 470, 1400, 100)
        d.add(GlowLine(this, vertical = false), 490, 575, 820, 10)
        val ok = d.add(GButton(this, button, Theme.BLUE), 770, 722, 261, 130)
        ok.setOnClickListener {
            dismissPopup()
            onOk()
        }
        d.alpha = 0f
        container.addView(d, FrameLayout.LayoutParams(-1, -1))
        d.animate().alpha(1f).setDuration(180).start()
        popupView = d
    }

    private fun dismissPopup() {
        popupView?.let { container.removeView(it) }
        popupView = null
    }
}
