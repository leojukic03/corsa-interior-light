package hr.leo.corsalight

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/** Provjera i otvaranje dviju dozvola koje aplikacija treba. */
object Perms {
    fun hasMedia(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ctx.packageName)
    }

    fun hasVolume(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.contains(ctx.packageName)
    }

    fun openMedia(ctx: Context) = open(
        ctx, "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
        "cmd notification allow_listener ${ctx.packageName}/hr.leo.corsalight.MediaListener",
    )

    fun openVolume(ctx: Context) = open(
        ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "settings put secure enabled_accessibility_services ${ctx.packageName}/hr.leo.corsalight.VolumeReader",
    )

    private fun open(ctx: Context, action: String, adb: String) {
        try {
            ctx.startActivity(Intent(action))
        } catch (e: Exception) {
            Toast.makeText(ctx, "The head unit has no such menu. Use ADB:\n$adb", Toast.LENGTH_LONG).show()
        }
    }
}
