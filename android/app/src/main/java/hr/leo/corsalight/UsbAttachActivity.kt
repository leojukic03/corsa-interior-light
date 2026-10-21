package hr.leo.corsalight

import android.app.Activity
import android.os.Bundle

/** Android je otvara kad se Arduino ukljuci; samo pokrene servis i zatvori se. */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LinkService.start(this)
        finish()
    }
}
