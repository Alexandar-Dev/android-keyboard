package org.futo.inputmethod.latin.uix.alex

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
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

    // koji recnik (fajl + verzija iz zaglavlja) i koji transformer su aktivni; kesira se po fajlu
    private var dictKey: String? = null
    private var dictLabel: String = "?"

    fun dictionaryLabel(ctx: Context, locale: java.util.Locale?): String {
        if (locale == null) return "nema jezika"
        return try {
            val f = org.futo.inputmethod.latin.uix.ResourceHelper.findFileForKind(
                ctx, locale, org.futo.inputmethod.latin.uix.FileKind.Dictionary
            ) ?: return "ugradjeni ($locale)"
            val key = f.absolutePath + ":" + f.lastModified()
            if (key != dictKey) {
                val h = org.futo.inputmethod.latin.utils.DictionaryInfoUtils
                    .getDictionaryFileHeaderOrNull(f, 0, f.length())
                dictLabel = "${f.name} v${h?.mVersionString ?: "?"} \"${h?.description ?: ""}\" " +
                    "${f.length() / 1024} KB, ${fmt.format(Date(f.lastModified()))}"
                dictKey = key
            }
            dictLabel
        } catch (e: Exception) { "greska: $e" }
    }

    // alex: tap na predlog (gornja traka ili drugi red) -> red u logu.
    // allowed = false u poljima za lozinku / inkognito.
    private val pickExec = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun pick(ctx: Context, allowed: Boolean, word: String, source: String, pos: Int,
             list: List<String>, before: String?) {
        if (!allowed) return
        try {
            if (!ctx.getSetting(SwipeLogSetting)) return
        } catch (_: Exception) { return }
        val rec = "izabrano: $word ($source, mesto ${pos + 1})" +
            (if (list.isNotEmpty()) "\nlista: " + list.joinToString(" ") else "") +
            "\npre kursora: [" + (before ?: "?").takeLast(80).replace("\n", " ") + "]"
        pickExec.execute { append(ctx, rec) }
    }

    fun sizeKb(ctx: Context): Long = try {
        (file(ctx).length() + File(file(ctx).parentFile, "swipe_log_staro.txt").length()) / 1024
    } catch (_: Exception) { 0 }
}
