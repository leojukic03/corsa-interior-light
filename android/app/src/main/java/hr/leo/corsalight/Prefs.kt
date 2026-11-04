package hr.leo.corsalight

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Sve postavke aplikacije. Iste vrijednosti Arduino drzi u EEPROM-u. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("corsalight", Context.MODE_PRIVATE)
        }
    }

    var r by int("r", 255)
    var g by int("g", 0)
    var b by int("b", 0)
    var colorName by str("colorName", "RED")
    var brightness by int("brightness", 32)  // posto, 0..100
    var power by bool("power", true)
    var mode by int("mode", 0)               // 0 mirna boja, 1 shooting star
    var react by bool("react", true)
    var showSong by bool("showSong", true)
    var showStation by bool("showStation", true)
    var showVolume by bool("showVolume", true)
    var ledMessages by bool("ledMessages", true)
    var idleText by str("idleText", "OPEL CORSA")
    var welcome1 by str("welcome1", "WILLKOMMEN")
    var welcome2 by str("welcome2", "LEO")
    var welcomeStyle by int("welcomeStyle", 2)    // TID: 0 pojavi se, 1 uklizi, 2 prolazi, 3 slovo po slovo, 4 iz sredine, 5 treptanje
    var ledWelcomeStyle by int("ledWelcomeStyle", 0)  // LED: 0 s rubova, 1 lagano paljenje, 2 klizi s lijeva, 3 iskrice
    var volumePkg by str("volumePkg", "")  // paket prozora s glasnocom (naucen automatski)
    var volumeMax by int("volumeMax", 40)

    // novi izgled (Figma): Center Display i User Preferences
    var welcomeOn by bool("welcomeOn", true)          // "Welcome message": pozdrav na TID-u kod paljenja
    var staticOn by bool("staticOn", true)            // "Static message": idle tekst kad nista ne svira
    var personalWelcome by bool("personalWelcome", true)  // ime iza pozdrava
    var userName by str("userName", "Leo")            // "User Name", na TID ide velikim slovima
    var rainbowAndrija by bool("rainbowAndrija", false)  // easter egg na ekranu Credits
    var autoUpdateFw by bool("autoUpdateFw", true)       // aplikacija sama snimi noviji Arduino kod

    /** Tekst koji Arduino dobiva kao drugi dio pozdrava. */
    fun welcome2ForTid(): String = if (personalWelcome) TextUtil.forTid(userName, 10) else ""

    /** Idle tekst za TID (prazno ako je "Static message" iskljucen). */
    fun idleForTid(): String = if (staticOn) TextUtil.forTid(idleText, 10) else ""

    /** Postotak u 0..255 s gama krivuljom, da su niske razine finije. */
    fun brightnessByte(): Int {
        val p = brightness.coerceIn(0, 100)
        if (p == 0) return 0
        return max(1, (255 * (p / 100.0) * (p / 100.0)).roundToInt())
    }

    fun syncCommands(): List<String> = listOf(
        "C$r,$g,$b",
        "B${brightnessByte()}",
        "P${if (power) 1 else 0}",
        "M$mode",
        "R${if (react) 1 else 0}",
        "I${idleForTid()}",
        "E${TextUtil.forTid(welcome1, 10)}",
        "F${welcome2ForTid()}",
        "A$welcomeStyle",
        "K$ledWelcomeStyle",
        "G${if (welcomeOn) 1 else 0}",
    )

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply()
        }
    }

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }

    private fun str(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
        }
    }
}
