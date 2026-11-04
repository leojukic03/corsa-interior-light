package hr.leo.corsalight

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Cita sto svira na headunitu. Treba "Pristup obavijestima" (Notification access).
 *  1) MediaSession: Spotify, YouTube Music, BT glazba, USB player, vecina radio aplikacija
 *  2) obavijesti radio aplikacija koje nemaju MediaSession
 */
class MediaListener : NotificationListenerService() {
    companion object {
        var instance: MediaListener? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private val controllers = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        updateSessions(list.orEmpty())
    }

    override fun onListenerConnected() {
        Prefs.init(this)
        instance = this
        LinkService.start(this)
        val cn = ComponentName(this, MediaListener::class.java)
        val m = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm = m
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, handler)
            updateSessions(m.getActiveSessions(cn))
            Hub.log("Media listener connected")
        } catch (e: SecurityException) {
            Hub.log("No media permission: ${e.message}")
        }
        // radio aplikacije koje vec imaju obavijest
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        controllers.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        controllers.clear()
        if (instance === this) instance = null
        Hub.log("Media listener disconnected")
    }

    private fun updateSessions(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in tokens }.forEach { t ->
            controllers.remove(t)?.let { (c, cb) -> c.unregisterCallback(cb) }
        }
        for (c in list) {
            if (c.sessionToken in controllers) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    Hub.log("Meta ${c.packageName}: ${describe(metadata)}")
                    evaluate()
                }
                override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate()
                override fun onSessionDestroyed() = evaluate()
            }
            c.registerCallback(cb, handler)
            controllers[c.sessionToken] = c to cb
        }
        Hub.log("Sessions: " + list.joinToString {
            "${it.packageName}(${stateName(it.playbackState?.state)}) [${describe(it.metadata)}]"
        }.ifEmpty { "none" })
        evaluate()
    }

    private fun describe(md: MediaMetadata?): String {
        if (md == null) return "no data"
        val t = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val a = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        return "title=$t artist=$a"
    }

    private fun hasTitle(c: MediaController): Boolean {
        val md = c.metadata ?: return false
        return !(md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).isNullOrBlank()
    }

    fun evaluate() {
        val list = controllers.values.map { it.first }
        val active = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_BUFFERING }
            // ZLink (CarPlay) i neke kineske aplikacije ne javljaju stanje, ali imaju naslov
            ?: list.firstOrNull { it.playbackState == null && hasTitle(it) }
        if (active == null) {
            Hub.clearMedia("session")
            return
        }
        val md = active.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        Hub.onMedia(active.packageName, title, artist, true, "session")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg != packageName) {
            // dijagnostika: svaka obavijest, da vidimo kako se zove radio aplikacija na headunitu
            val ex0 = sbn.notification.extras
            Hub.log("Notification $pkg: ${ex0.getCharSequence(Notification.EXTRA_TITLE)} | ${ex0.getCharSequence(Notification.EXTRA_TEXT)}")
        }
        if (!Hub.isRadio(pkg)) return
        if (controllers.values.any { it.first.packageName == pkg }) return  // ima sesiju, ona je bolja
        val ex = sbn.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        Hub.onMedia(pkg, title, text, true, "notif")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (Hub.isRadio(sbn.packageName) && sbn.packageName == Hub.nowSource) Hub.clearMedia("notif")
    }

    private fun stateName(s: Int?) = when (s) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        null -> "?"
        else -> s.toString()
    }
}
