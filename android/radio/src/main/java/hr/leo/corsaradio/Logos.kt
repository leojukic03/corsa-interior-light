package hr.leo.corsaradio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logotipi stanica.
 *
 * 1. Rucno: slike u `Android/data/hr.leo.corsaradio/files/logos/`, imenovane po PI kodu
 *    (npr. `9201.png`) ili po RDS imenu bez razmaka i znakova (npr. `ANTENA.png`). PI ima prednost.
 * 2. Automatski: ako slike nema, potrazi stanicu po RDS imenu na radio-browser.info (besplatna,
 *    otvorena baza stanica) i spremi logo u istu mapu. Prvo trazi u Hrvatskoj, pa svugdje.
 *    Neuspjeh se pamti 7 dana (`<IME>.none`), da se ne pita stalno.
 */
object Logos {
    private const val TAG = "CorsaRadio"
    private val cache = HashMap<String, Bitmap?>()
    private val firstSeen = HashMap<String, Long>()
    private val inFlight = HashSet<String>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Zove se kad stigne novi logo s interneta. */
    var onLoaded: (() -> Unit)? = null

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "logos")

    private fun piKey(pi: Int) = if (pi != 0) String.format(Locale.US, "%04X", pi and 0xFFFF) else null
    private fun nameKey(ps: String) = ps.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }.ifEmpty { null }

    fun find(ctx: Context, pi: Int, ps: String): Bitmap? {
        for (k in listOfNotNull(piKey(pi), nameKey(ps))) {
            if (!cache.containsKey(k)) cache[k] = load(ctx, k)
            cache[k]?.let { return it }
        }
        maybeFetch(ctx, ps)
        return null
    }

    private fun load(ctx: Context, key: String): Bitmap? {
        for (ext in listOf("png", "webp", "jpg", "jpeg")) {
            val f = File(dir(ctx), "$key.$ext")
            if (f.isFile) return decode(f)
        }
        return null
    }

    private fun decode(f: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var s = 1
        while (bounds.outWidth / s > 600 || bounds.outHeight / s > 600) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Throwable) {
        null
    }

    // ---------------- dohvat s interneta

    private fun maybeFetch(ctx: Context, ps: String) {
        val key = nameKey(ps) ?: return
        if (key.length < 2 || key in inFlight) return
        // dinamicki PS (ime koje se vrti) ne smije pokrenuti pretragu za svaki komad teksta:
        // trazi tek kad je isto ime tu vise od 4 s
        val now = SystemClock.elapsedRealtime()
        val first = firstSeen.getOrPut(key) {
            main.postDelayed({ onLoaded?.invoke() }, 4500)  // ponovo pitaj kad prode 4 s
            now
        }
        if (now - first < 4000) return
        val d = dir(ctx)
        val miss = File(d, "$key.none")
        if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < 7L * 24 * 3600 * 1000) return
        inFlight += key
        io.execute {
            val ok = try { fetch(ps.trim(), File(d, "$key.png")) } catch (e: Throwable) { Log.w(TAG, "logo $ps", e); false }
            if (!ok) try { d.mkdirs(); miss.writeText("") } catch (_: Throwable) {}
            main.post {
                inFlight -= key
                if (ok) {
                    cache.remove(key)
                    onLoaded?.invoke()
                }
            }
        }
    }

    private fun fetch(name: String, out: File): Boolean {
        val q = URLEncoder.encode(name, "UTF-8")
        for (country in listOf("&countrycode=HR", "")) {
            val json = get("https://de1.api.radio-browser.info/json/stations/search?name=$q$country" +
                "&limit=10&order=clickcount&reverse=true&hidebroken=true") ?: continue
            val arr = JSONArray(String(json, Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val icon = arr.getJSONObject(i).optString("favicon").trim()
                if (icon.isEmpty()) continue
                val bytes = get(icon.replaceFirst("http://", "https://")) ?: continue
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                if (bmp.width < 32 || bmp.height < 32) continue
                out.parentFile?.mkdirs()
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return true
            }
        }
        return false
    }

    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 5000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "CorsaRadio/0.2")
            if (c.responseCode != 200) null
            else c.inputStream.use { s -> s.readBytes().takeIf { it.size < 3_000_000 } }
        } catch (e: Throwable) {
            null
        } finally {
            c.disconnect()
        }
    }
}
