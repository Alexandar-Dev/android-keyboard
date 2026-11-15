package org.futo.inputmethod.latin.uix.alex

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import org.futo.inputmethod.latin.uix.SettingsKey
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// alex: dijagnosticki log za swipe (iskljucen po difoltu). Fajl ostaje na telefonu:
// Android/data/<paket>/files/swipe_log.txt (cita se preko USB-a: adb pull).
val SwipeLogSetting = SettingsKey(booleanPreferencesKey("alex_swipe_log"), false)

object SwipeLog {
    private const val MAX_BYTES = 4L * 1024 * 1024
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun file(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "swipe_log.txt")

    @Synchronized
    fun append(ctx: Context, record: String) {
        try {
            val f = file(ctx)
            if (f.exists() && f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "swipe_log_staro.txt")
                old.delete(); f.renameTo(old)
            }
            f.appendText("=== " + fmt.format(Date()) + "\n" + record.trimEnd() + "\n\n")
        } catch (_: Exception) { }
    }

    @Synchronized
    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
            File(file(ctx).parentFile, "swipe_log_staro.txt").delete()
        } catch (_: Exception) { }
    }

    fun sizeKb(ctx: Context): Long = try {
        (file(ctx).length() + File(file(ctx).parentFile, "swipe_log_staro.txt").length()) / 1024
    } catch (_: Exception) { 0 }
}
