package hr.leo.corsalight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

/**
 * Pozadinski servis: drzi USB vezu s Arduinom, prati glasnocu i salje naredbe.
 */
class LinkService : Service(), SerialInputOutputManager.Listener {
    companion object {
        private const val ACTION_USB_PERMISSION = "hr.leo.corsalight.USB_PERMISSION"
        private const val CHANNEL = "link"
        private const val BAUD = 9600

        fun start(ctx: Context) {
            val i = Intent(ctx, LinkService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private lateinit var usb: UsbManager
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private var permissionAsked = false
    private val rx = StringBuilder()
    @Volatile private var flashing = false
    private var autoFlashTried = false  // ako automatska nadogradnja padne, ne pokusavaj u krug

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        usb = getSystemService(USB_SERVICE) as UsbManager
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        startForeground(1, buildNotification())
        Hub.link = this

        registerReceiver(usbReceiver, IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        registerReceiver(metaReceiver, IntentFilter().apply {
            addAction("com.android.music.metachanged")
            addAction("com.android.music.playstatechanged")
            addAction("com.spotify.music.metadatachanged")
            addAction("com.spotify.music.playbackstatechanged")
        })
        settingsObservers.forEach { (uri, obs) -> contentResolver.registerContentObserver(uri, true, obs) }

        Hub.initVolume(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        handler.post(connectLoop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        tryConnect()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(usbReceiver)
        unregisterReceiver(volumeReceiver)
        unregisterReceiver(metaReceiver)
        settingsObservers.forEach { (_, obs) -> contentResolver.unregisterContentObserver(obs) }
        disconnect()
        writer.shutdown()
        if (Hub.link === this) Hub.link = null
        super.onDestroy()
    }

    // ---------------- USB ----------------

    private val connectLoop = object : Runnable {
        override fun run() {
            if (port == null) tryConnect()
            handler.postDelayed(this, 5000)
        }
    }

    private fun tryConnect() {
        if (port != null) return
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull() ?: return
        val dev = driver.device
        if (!usb.hasPermission(dev)) {
            if (!permissionAsked) {
                permissionAsked = true
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
                usb.requestPermission(dev, pi)
            }
            return
        }
        val conn = usb.openDevice(dev)
        if (conn == null) {
            Hub.log("USB: cannot open ${dev.deviceName}")
            return
        }
        try {
            val p = driver.ports[0]
            p.open(conn)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = p
            io = startIo(p)
            Hub.usbInfo = "${driver.javaClass.simpleName.removeSuffix("Driver")} %04X:%04X".format(dev.vendorId, dev.productId)
            Hub.connected = true
            Hub.log("USB connected: ${Hub.usbInfo}")
            updateNotification()
            // Nano se resetira kad se port otvori: pricekaj bootloader pa posalji sve postavke
            handler.postDelayed({ syncAll() }, 2500)
        } catch (e: Exception) {
            Hub.log("USB error: ${e.message}")
            try {
                conn.close()
            } catch (_: Exception) {
            }
            port = null
        }
    }

    private fun disconnect() {
        io?.stop()
        io = null
        try {
            port?.close()
        } catch (_: Exception) {
        }
        val was = port != null
        port = null
        if (was) {
            Hub.connected = false
            Hub.log("USB disconnected")
            updateNotification()
        }
    }

    private fun startIo(p: UsbSerialPort): SerialInputOutputManager {
        val m = SerialInputOutputManager(p, this)
        m.readTimeout = 200  // da se citanje moze zaustaviti za snimanje Arduina
        m.start()
        return m
    }

    private fun syncAll() {
        Prefs.syncCommands().forEach { send(it) }
        send("N${Hub.nowText}")
        send("?")  // Arduino javi verziju koda -> po potrebi nadogradnja
    }

    // ---------------- nadogradnja Arduina iz aplikacije ----------------

    private fun onVersion(v: Int) {
        Hub.fwVersion = v
        val bundled = Firmware.bundledVersion(this)
        if (v < bundled && Prefs.autoUpdateFw && !autoFlashTried && !flashing) {
            autoFlashTried = true
            Hub.log("OAI firmware v$v is older than v$bundled in the app: updating")
            updateFirmware()
        }
    }

    /** Snimi Arduino kodom koji aplikacija nosi. Moze i rucno iz Advanced Settings. */
    fun updateFirmware() {
        val p = port
        if (p == null || flashing) return
        flashing = true
        Hub.flashState = "Updating OAI module…"
        // TID ne smije dobiti reset usred poruke: Arduino pauzira TID 5 s
        try {
            p.write("Z\n".toByteArray(Charsets.ISO_8859_1), 500)
        } catch (_: Exception) {
        }
        writer.execute {
            var ok = false
            try {
                Thread.sleep(300)
                io?.stop()
                val until = System.currentTimeMillis() + 1500
                while (io?.state != SerialInputOutputManager.State.STOPPED && System.currentTimeMillis() < until) Thread.sleep(20)
                val (img, size) = Firmware.loadImage(this)
                ok = Firmware.flash(p, img, size) { msg -> handler.post { Hub.log("Firmware: $msg"); Hub.flashState = msg } }
            } catch (e: Exception) {
                handler.post { Hub.log("Firmware update failed: ${e.message}") }
            } finally {
                try {
                    p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                } catch (_: Exception) {
                }
                handler.post {
                    flashing = false
                    Hub.flashState = ""
                    if (port === p) {
                        io = startIo(p)
                        // novi kod se pali s welcome animacijom: onda mu posalji postavke
                        handler.postDelayed({ syncAll() }, if (ok) 2500 else 500)
                    }
                    Hub.log(if (ok) "OAI module updated" else "OAI module not updated (old code still running)")
                }
            }
        }
    }

    fun send(cmd: String) {
        if (flashing) return
        val p = port ?: return
        writer.execute {
            try {
                p.write((cmd + "\n").toByteArray(Charsets.ISO_8859_1), 500)
            } catch (e: Exception) {
                handler.post {
                    Hub.log("Send failed: ${e.message}")
                    disconnect()
                }
            }
        }
    }

    override fun onNewData(data: ByteArray) {
        for (b in data) {
            val c = (b.toInt() and 0xFF).toChar()
            when (c) {
                '\r' -> {}
                '\n' -> {
                    val line = rx.toString()
                    rx.setLength(0)
                    if (line.isNotBlank()) Hub.log("Arduino: $line")
                    // "OK CorsaLight v31" / "HELLO CorsaLight v31"; stari kod javlja "OK CorsaLight 2"
                    Regex("""(?:OK|HELLO) CorsaLight v?(\d+)""").find(line)?.let { m ->
                        val v = m.groupValues[1].toInt().let { if (line.contains(" v")) it else 1 }
                        handler.post { onVersion(v) }
                    }
                }
                else -> if (rx.length < 200) rx.append(c)
            }
        }
    }

    override fun onRunError(e: Exception) {
        if (flashing) return  // citanje je namjerno zaustavljeno za snimanje Arduina
        handler.post { disconnect() }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_USB_PERMISSION ->
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) tryConnect()
                    else Hub.log("USB permission denied")
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> tryConnect()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    permissionAsked = false
                    disconnect()
                }
            }
        }
    }

    // ---------------- glasnoca ----------------

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val type = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            val value = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            Hub.log("Volume stream $type = $value")
            if (type != AudioManager.STREAM_MUSIC) return
            val v = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            Hub.onVolume(v, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        }
    }

    // Kineski headunitovi glasnocu i radio cesto drze u vlastitim postavkama (System/Global/Secure).
    // Za dijagnostiku logiramo svaku promjenu, osim onih koje se mijenjaju stalno.
    private val noisyKeys = setOf("screen_brightness", "screen_off_timeout", "next_alarm_formatted")

    private inner class SettingsLogger(private val table: String) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val key = uri?.lastPathSegment ?: return
            if (key in noisyKeys) return
            val value = try {
                when (table) {
                    "global" -> Settings.Global.getString(contentResolver, key)
                    "secure" -> Settings.Secure.getString(contentResolver, key)
                    else -> Settings.System.getString(contentResolver, key)
                }
            } catch (_: Exception) {
                "?"
            }
            Hub.log("Setting $table/$key = ${value?.take(60)}")
            Hub.onVolume(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        }
    }

    private val settingsObservers = listOf(
        Settings.System.CONTENT_URI to SettingsLogger("system"),
        Settings.Global.CONTENT_URI to SettingsLogger("global"),
        Settings.Secure.CONTENT_URI to SettingsLogger("secure"),
    )

    // ---------------- stari "metachanged" broadcastovi ----------------

    private val metaReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val title = i.getStringExtra("track") ?: return
            val artist = i.getStringExtra("artist")
            val playing = i.getBooleanExtra("playing", true)
            Hub.log("Broadcast ${i.action}: $artist - $title")
            Hub.onMedia(i.action ?: "broadcast", title, artist, playing, "broadcast")
        }
    }

    // ---------------- obavijest ----------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Arduino connection", NotificationManager.IMPORTANCE_MIN))
        }
        val piFlags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), piFlags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (Hub.connected) "Arduino connected" else "Waiting for Arduino (USB)…")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, buildNotification())
    }
}
