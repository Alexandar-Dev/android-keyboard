package org.futo.inputmethod.latin.uix.alex

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import org.futo.inputmethod.latin.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// alex: swipe log van zakljucanog foldera Android/data.
// ACTION_SAVE (precica na pocetnom ekranu) = kopija u Download/FUTO/; inace meni za deljenje.
class ShareSwipeLogActivity : Activity() {
    companion object {
        const val ACTION_SAVE = "org.futo.alex.SAVE_SWIPE_LOG"

        private fun logFiles(ctx: Context): List<File> {
            val main = SwipeLog.file(ctx)
            return listOf(File(main.parentFile, "swipe_log_staro.txt"), main)
                .filter { it.exists() && it.length() > 0 }
        }

        // spaja stari i novi log u jedan fajl u Download/FUTO/; vraca putanju za poruku
        fun saveToDownloads(ctx: Context): String? {
            val files = logFiles(ctx)
            if (files.isEmpty()) return null
            val name = "swipe_log_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".txt"
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FUTO")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IllegalStateException("MediaStore insert")
                ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                    files.forEach { f -> f.inputStream().use { it.copyTo(out) } }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FUTO")
                dir.mkdirs()
                File(dir, name).outputStream().use { out ->
                    files.forEach { f -> f.inputStream().use { it.copyTo(out) } }
                }
            }
            return "Download/FUTO/$name"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (intent?.action == ACTION_SAVE) {
                val p = saveToDownloads(this)
                Toast.makeText(this,
                    if (p == null) getString(R.string.alex_swipe_log_empty)
                    else getString(R.string.alex_swipe_log_saved, p),
                    Toast.LENGTH_LONG).show()
            } else {
                share()
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun share() {
        val files = logFiles(this)
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.alex_swipe_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val auth = "$packageName.swipelog"
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(this, auth, it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_SUBJECT, "swipe_log")
        val clip = ClipData.newRawUri("swipe_log", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.alex_swipe_log_share)))
    }
}
