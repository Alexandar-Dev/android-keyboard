package org.futo.inputmethod.latin.uix.alex

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// alex: ucenje iz izbora posle swipe-a. Kad dekoder stavi X prvi, a korisnik tapne Y,
// pamti se par (prethodna rec, X -> Y). Posle MIN_COUNT puta, za istu prethodnu rec i kad je Y
// medju ponudjenima, Y ide prvi. Tap na X kad je Y bio stavljen prvi ponistava par.
val SwipeLearnSetting = SettingsKey(booleanPreferencesKey("alex_swipe_learn"), true)

object SwipePairs {
    const val MIN_COUNT = 2
    private const val MAX_PAIRS = 3000
    private const val FILE = "swipe_parovi.txt"

    private data class Entry(val prev: String, val from: String, val to: String, var count: Int)

    private val map = LinkedHashMap<String, Entry>()
    private var loaded = false

    private fun key(prev: String, from: String, to: String) = "$prev|$from|$to"

    /** poslednja rec iz ngram konteksta ("<S> A" -> "a"); pocetak recenice = "<s>" */
    fun prevKey(prevWordsContext: String?): String {
        val last = prevWordsContext?.trim()?.split(' ')?.lastOrNull { it.isNotBlank() } ?: return "<s>"
        if (last == "<S>") return "<s>"
        val w = last.lowercase().trim { !it.isLetterOrDigit() }
        return w.ifEmpty { "<s>" }
    }

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) f.readLines().forEach { parseLine(it)?.let { e -> map[key(e.prev, e.from, e.to.lowercase())] = e } }
        } catch (_: Exception) { }
    }

    private fun parseLine(l: String): Entry? {
        val p = l.trim().split('|')
        if (p.size != 4) return null
        val c = p[3].toIntOrNull() ?: return null
        if (p[1].isBlank() || p[2].isBlank()) return null
        return Entry(p[0].lowercase(), p[1].lowercase(), p[2], c)
    }

    @Synchronized
    private fun save(ctx: Context) {
        try {
            File(ctx.filesDir, FILE).writeText(map.values.joinToString("\n") { "${it.prev}|${it.from}|${it.to}|${it.count}" })
        } catch (_: Exception) { }
    }

    fun enabled(ctx: Context) = try { ctx.getSetting(SwipeLearnSetting) } catch (_: Exception) { false }

    /** tap posle swipe-a: prvi predlog je bio [shownFirst], korisnik je izabrao [picked]. Vraca red za log ili null. */
    @Synchronized
    fun learn(ctx: Context, prev: String, shownFirst: String, picked: String): String? {
        if (!enabled(ctx)) return null
        val a = shownFirst.lowercase(); val b = picked.lowercase()
        if (a == b || a.isBlank() || b.isBlank()) return null
        ensureLoaded(ctx)
        // obrnut par postoji (tastatura je stavila picked->... prvi, a korisnik hoce drugo) -> ponisti ga
        val rev = map.remove(key(prev, b, a))
        if (rev != null) { save(ctx); return "ponisteno: posle [$prev] ${rev.from} -> ${rev.to}" }
        val k = key(prev, a, b)
        val e = map[k] ?: Entry(prev, a, picked, 0).also { map[k] = it }
        e.count++
        if (map.size > MAX_PAIRS) map.remove(map.keys.first())
        save(ctx)
        return "nauceno: posle [$prev] $a -> $picked (${e.count})"
    }

    /** indeks reci koju treba staviti prvu (u [words]), ili -1 */
    @Synchronized
    fun apply(ctx: Context, prev: String, words: List<String>): Pair<Int, String>? {
        if (words.size < 2 || !enabled(ctx)) return null
        ensureLoaded(ctx)
        val first = words[0].lowercase()
        val cands = map.values.filter { it.prev == prev && it.from == first && it.count >= MIN_COUNT }
            .sortedByDescending { it.count }
        for (e in cands) {
            val i = words.indexOfFirst { it.lowercase() == e.to.lowercase() }
            if (i > 0) return i to "primenjeno: posle [$prev] ${e.from} -> ${words[i]} (${e.count})"
        }
        return null
    }

    @Synchronized
    fun count(ctx: Context): Int { ensureLoaded(ctx); return map.size }

    @Synchronized
    fun clear(ctx: Context) { ensureLoaded(ctx); map.clear(); save(ctx) }

    @Synchronized
    fun exportToDownloads(ctx: Context): String {
        ensureLoaded(ctx)
        val text = map.values.joinToString("\n") { "${it.prev}|${it.from}|${it.to}|${it.count}" }
        val name = "swipe_parovi_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".txt"
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FUTO")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("MediaStore insert")
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FUTO")
            dir.mkdirs(); File(dir, name).writeText(text)
        }
        return "Download/FUTO/$name"
    }

    /** dodaje parove iz fajla (postojeci ostaju, brojac = veci od dva); vraca broj procitanih */
    @Synchronized
    fun importFrom(ctx: Context, uri: Uri): Int {
        ensureLoaded(ctx)
        var n = 0
        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
            lines.forEach { l ->
                val e = parseLine(l) ?: return@forEach
                val k = key(e.prev, e.from, e.to.lowercase())
                val old = map[k]
                if (old == null) map[k] = e else old.count = maxOf(old.count, e.count)
                n++
            }
        }
        save(ctx)
        return n
    }
}

@Composable
fun SwipePairsSetting() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val n = remember(tick) { SwipePairs.count(ctx) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val msg = try { ctx.getString(R.string.alex_swipe_pairs_imported, SwipePairs.importFrom(ctx, uri)) }
                      catch (e: Exception) { e.toString() }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            tick++
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.alex_swipe_pairs_count, n), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                val msg = try { SwipePairs.exportToDownloads(ctx) } catch (e: Exception) { e.toString() }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }) { Text(stringResource(R.string.alex_swipe_pairs_export)) }
            FilledTonalButton(onClick = { importLauncher.launch(arrayOf("text/*")) }) {
                Text(stringResource(R.string.alex_swipe_pairs_import))
            }
            FilledTonalButton(onClick = { SwipePairs.clear(ctx); tick++ }) {
                Text(stringResource(R.string.alex_swipe_pairs_clear))
            }
        }
    }
}
