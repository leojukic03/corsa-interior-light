package hr.leo.corsalight

import java.text.Normalizer

object TextUtil {
    // Znakovi koje TID moze prikazati (isto kao cleanText() na Arduinu)
    private const val ALLOWED = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'*+,-/<>_!\"#$%&().:;=?@[]^"

    /** Velika slova, bez kvacica (Č -> C, Đ -> D, ß -> SS), nepoznato -> razmak. */
    fun forTid(text: String, maxLen: Int): String {
        val replaced = text
            .replace("đ", "d").replace("Đ", "D")
            .replace("ß", "SS").replace("æ", "AE").replace("Æ", "AE")
            .replace("ø", "O").replace("Ø", "O").replace("ł", "L").replace("Ł", "L")
            .replace('–', '-').replace('—', '-').replace('’', '\'').replace('‘', '\'')
            .replace('“', '"').replace('”', '"')
        val noMarks = Normalizer.normalize(replaced, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .uppercase()
        val sb = StringBuilder()
        for (c in noMarks) {
            val ch = if (c in ALLOWED) c else ' '
            if (ch == ' ' && (sb.isEmpty() || sb.last() == ' ')) continue
            sb.append(ch)
        }
        return sb.toString().trim().take(maxLen).trim()
    }
}
