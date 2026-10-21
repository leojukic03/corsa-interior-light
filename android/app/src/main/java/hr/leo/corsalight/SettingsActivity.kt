package hr.leo.corsalight

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Sve sto se rijetko mijenja: prikaz na TID-u, tekstovi, dozvole i dijagnostika. */
class SettingsActivity : Activity() {

    private lateinit var diag: TextView
    private lateinit var accessState: TextView
    private lateinit var a11yState: TextView
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_settings)

        diag = findViewById(R.id.diag)
        accessState = findViewById(R.id.accessState)
        a11yState = findViewById(R.id.a11yState)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAccess).setOnClickListener { Perms.openMedia(this) }
        findViewById<Button>(R.id.btnA11y).setOnClickListener { Perms.openVolume(this) }

        bindSwitch(R.id.swSong, { Prefs.showSong }) { Prefs.showSong = it; Hub.refreshNowText() }
        bindSwitch(R.id.swStation, { Prefs.showStation }) { Prefs.showStation = it; Hub.refreshNowText() }
        bindSwitch(R.id.swVolume, { Prefs.showVolume }) { Prefs.showVolume = it }
        bindSwitch(R.id.swLedMsg, { Prefs.ledMessages }) { Prefs.ledMessages = it }
        bindSwitch(R.id.swReact, { Prefs.react }) {
            Prefs.react = it
            Hub.send("R${if (it) 1 else 0}")
        }

        val idle = findViewById<EditText>(R.id.idleText)
        idle.setText(Prefs.idleText)
        val saveIdle = {
            Prefs.idleText = TextUtil.forTid(idle.text.toString(), 10).ifEmpty { "OPEL CORSA" }
            idle.setText(Prefs.idleText)
            Hub.send("I${Prefs.idleText}")
            Toast.makeText(this, "Spremljeno", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnIdle).setOnClickListener { saveIdle() }
        idle.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) saveIdle()
            false
        }

        val w1 = findViewById<EditText>(R.id.welcome1)
        val w2 = findViewById<EditText>(R.id.welcome2)
        w1.setText(Prefs.welcome1)
        w2.setText(Prefs.welcome2)
        val saveWelcome = {
            Prefs.welcome1 = TextUtil.forTid(w1.text.toString(), 10).ifEmpty { "WILLKOMMEN" }
            Prefs.welcome2 = TextUtil.forTid(w2.text.toString(), 10)  // prazno = samo prvi tekst
            w1.setText(Prefs.welcome1)
            w2.setText(Prefs.welcome2)
            Hub.send("E${Prefs.welcome1}")
            Hub.send("F${Prefs.welcome2}")
        }
        findViewById<Button>(R.id.btnWelcomeSave).setOnClickListener {
            saveWelcome()
            Toast.makeText(this, "Spremljeno", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnWelcomeTest).setOnClickListener {
            saveWelcome()
            Hub.send("W")
        }
        w2.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) saveWelcome()
            false
        }

        bindSpinner(
            R.id.spTidAnim,
            listOf("Pojavi se", "Uklizi", "Prolazi (pozdrav izlazi, ime ulazi)", "Slovo po slovo", "Iz sredine", "Treptanje"),
            Prefs.welcomeStyle,
        ) {
            Prefs.welcomeStyle = it
            Hub.send("A$it")
        }
        bindSpinner(
            R.id.spLedAnim,
            listOf("S rubova prema sredini", "Lagano paljenje", "Klizi s lijeva", "Iskrice"),
            Prefs.ledWelcomeStyle,
        ) {
            Prefs.ledWelcomeStyle = it
            Hub.send("K$it")
        }
        findViewById<Button>(R.id.btnAnimTest).setOnClickListener {
            if (!Hub.connected) {
                Toast.makeText(this, "Arduino nije spojen", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Hub.send("W")
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            if (!Hub.connected) {
                Toast.makeText(this, "Arduino nije spojen", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Hub.log("Test: Å¡aljem D i T Arduinuâ€¦")
            Hub.send("D")
            Hub.send("T")
        }
        findViewById<Button>(R.id.btnRelearn).setOnClickListener {
            val r = VolumeReader.instance
            if (r == null) {
                Toast.makeText(this, "Prvo ukljuÄi Äitanje glasnoÄ‡e (PristupaÄnost)", Toast.LENGTH_LONG).show()
            } else {
                r.relearn()
                Toast.makeText(this, "Sad jednom promijeni glasnoÄ‡u", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Hub.addListener(refresh)
        render()
    }

    override fun onPause() {
        Hub.removeListener(refresh)
        super.onPause()
    }

    private fun render() {
        val media = Perms.hasMedia(this)
        val vol = Perms.hasVolume(this)
        accessState.text = if (media) "âœ“ ÄŒitanje medija: ukljuÄeno" else "âœ— ÄŒitanje medija: iskljuÄeno"
        accessState.setTextColor(if (media) 0xFF7CFFB2.toInt() else 0xFFFFB27C.toInt())
        a11yState.text = when {
            !vol -> "âœ— ÄŒitanje glasnoÄ‡e: iskljuÄeno"
            VolumeReader.instance == null -> "â€¦ ÄŒitanje glasnoÄ‡e: ukljuÄeno, Äeka pokretanje"
            Prefs.volumePkg.isEmpty() -> "âœ“ ÄŒitanje glasnoÄ‡e: radi â€“ promijeni glasnoÄ‡u da je nauÄi"
            else -> "âœ“ ÄŒitanje glasnoÄ‡e: radi (${Prefs.volumePkg})"
        }
        a11yState.setTextColor(if (vol) 0xFF7CFFB2.toInt() else 0xFFFFB27C.toInt())

        val head = "Media: ${if (media) "DA" else "NE"} (listener ${if (MediaListener.instance != null) "radi" else "ne radi"})   " +
            "GlasnoÄ‡a: ${if (VolumeReader.instance != null) "radi" else "ne radi"}\n" +
            "Izvor: ${Hub.nowSource.ifEmpty { "-" }} (${Hub.nowVia.ifEmpty { "-" }})   " +
            "Zadnja glasnoÄ‡a: ${if (Hub.volume >= 0) "${Hub.volume}/${Hub.volumeMax}" else "-"}\n\n"
        diag.text = head + Hub.diag.joinToString("\n")
    }

    private fun bindSpinner(id: Int, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val sp = findViewById<Spinner>(id)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(selected.coerceIn(0, items.size - 1), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, rowId: Long) {
                if (pos != selected || sp.tag != null) onPick(pos)
                sp.tag = true  // nakon prvog odabira svaki odabir salje naredbu
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun bindSwitch(id: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val s = findViewById<Switch>(id)
        s.isChecked = get()
        s.setOnCheckedChangeListener { _, checked -> set(checked) }
    }
}
