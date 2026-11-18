package hr.leo.corsaradio

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Medijska sesija radija. Interior Light (i sustav headunita) je cita: naslov ide na TID.
 * Tipke medija (volan, sustav) = sljedeca / prethodna spremljena stanica.
 */
class RadioSession(ctx: Context) {
    var onNext: () -> Unit = {}
    var onPrevious: () -> Unit = {}

    private val session = MediaSession(ctx, "CorsaRadio").apply {
        @Suppress("DEPRECATION")
        setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        setCallback(object : MediaSession.Callback() {
            override fun onSkipToNext() = onNext()
            override fun onSkipToPrevious() = onPrevious()
        })
        isActive = true
    }

    private var last: Triple<String, String, Boolean>? = null

    /** Javi sesiji stanje; isto stanje se ne salje ponovno (Tuner javlja promjene cesto). */
    fun update(title: String, subtitle: String, playing: Boolean) {
        val now = Triple(title, subtitle, playing)
        if (now == last) return
        last = now
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED, 0, 1f)
                .build()
        )
    }

    fun release() {
        session.isActive = false
        session.release()
    }
}
