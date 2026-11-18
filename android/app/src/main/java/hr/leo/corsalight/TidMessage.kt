package hr.leo.corsalight

/**
 * Kratka poruka na TID-u od druge aplikacije (Corsa Radio: "AF 100.20", "FM2").
 * Broadcast ACTION s tekstom u EXTRA; LinkService ga salje Arduinu kao L<tekst> (2 s).
 */
object TidMessage {
    const val ACTION = "hr.leo.corsalight.TID_MESSAGE"
    const val EXTRA = "text"

    /** Naredba za Arduino ili null ako nema sto pokazati. TID ima 10 znakova. */
    fun command(text: String?): String? {
        val clean = TextUtil.forTid(text ?: return null, 10)
        return if (clean.isEmpty()) null else "L$clean"
    }
}
