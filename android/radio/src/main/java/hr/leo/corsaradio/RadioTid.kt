package hr.leo.corsaradio

import java.util.Locale

/** Tekstovi za TID (gornji ekran) preko Interior Lighta. Frekvencija: FM u 10 kHz, AM u kHz. */
object RadioTid {
    /** Naslov medijske sesije: RDS ime, a bez njega frekvencija. */
    fun title(ps: String, freq: Int, am: Boolean): String {
        val name = ps.trim()
        if (name.isNotEmpty()) return name
        return if (am) "AM $freq" else "FM " + String.format(Locale.US, "%.2f", freq / 100f)
    }

    fun af(freq: Int): String = "AF " + String.format(Locale.US, "%.2f", freq / 100f)

    fun band(band: Int): String = when (band) {
        Tuner.FM2 -> "FM2"
        Tuner.AM -> "AM"
        else -> "FM1"
    }
}
