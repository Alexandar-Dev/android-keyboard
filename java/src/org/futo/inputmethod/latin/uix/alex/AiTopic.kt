package org.futo.inputmethod.latin.uix.alex

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.actions.aicheck.AiCheckApiKey
import org.futo.inputmethod.latin.uix.actions.aicheck.AiCheckClient
import org.futo.inputmethod.latin.uix.getSetting

// alex: AI tema. Dok kucas/svajpujes, AI (u pozadini, bez cekanja) procita tekst i vrati reci
// koje verovatno slede (tema razgovora). Te reci se odmah lokalno pojacavaju kod swipe-a i kucanja.
val AiTopicEnabledSetting = SettingsKey(booleanPreferencesKey("alex_ai_topic_enabled"), false)
val AiTopicModelSetting = SettingsKey(stringPreferencesKey("alex_ai_topic_model"), "claude-haiku-5-5")
val AiTopicStrengthSetting = SettingsKey(floatPreferencesKey("alex_ai_topic_strength"), 0.3f)

object AiTopic {
    private const val TAG = "AiTopic"
    private const val MIN_NEW_WORDS = 6          // novi zahtev posle bar 6 novih reci
    private const val MIN_INTERVAL_MS = 8_000L   // i ne cesce od 8 s
    private const val EXPIRE_MS = 15 * 60_000L   // tema zastari posle 15 min
    private const val MAX_TEXT = 1500            // koliko teksta pre kursora ide AI-ju

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var words: Set<String> = emptySet()
    @Volatile var wordList: List<String> = emptyList(); private set
    @Volatile private var wordsAt = 0L
    @Volatile private var lastSentText = ""
    @Volatile private var lastRequestAt = 0L
    @Volatile private var inFlight = false
    @Volatile var lastError: String? = null; private set
    @Volatile var requestsToday = 0; private set
    private var requestsDay = -1L

    private fun wordCount(s: String) = s.split(' ', '\n').count { it.any(Char::isLetter) }

    /** Poziva se posle swipe-a / reci; sam odluci da li treba novi zahtev. allowed=false u lozinkama/inkognito. */
    fun update(ctx: Context, textBefore: String?, allowed: Boolean) {
        if (!allowed || textBefore == null) return
        val enabled = try { ctx.getSetting(AiTopicEnabledSetting) } catch (_: Exception) { false }
        if (!enabled) return
        val text = textBefore.takeLast(MAX_TEXT).trim()
        val now = SystemClock.elapsedRealtime()

        // drugo polje / obrisan tekst -> stara tema ne vazi
        val sameThread = lastSentText.isNotEmpty() && text.contains(lastSentText.takeLast(40))
        if (!sameThread && words.isNotEmpty() && now - wordsAt > 60_000L) clear()

        if (inFlight || now - lastRequestAt < MIN_INTERVAL_MS) return
        val n = wordCount(text)
        if (n < 4) return
        val newWords = if (sameThread) n - wordCount(lastSentText) else n
        if (newWords < MIN_NEW_WORDS && words.isNotEmpty()) return

        val key = try { ctx.getSetting(AiCheckApiKey) } catch (_: Exception) { "" }
        if (key.isBlank()) { lastError = "nema API ključa"; return }
        val model = try { ctx.getSetting(AiTopicModelSetting) } catch (_: Exception) { "claude-haiku-5-5" }

        inFlight = true
        lastRequestAt = now
        lastSentText = text
        val day = System.currentTimeMillis() / 86_400_000L
        if (day != requestsDay) { requestsDay = day; requestsToday = 0 }
        requestsToday++
        scope.launch {
            try {
                val list = AiCheckClient.topicWords(key, model, text)
                    .map { it.trim() }.filter { w -> w.isNotEmpty() && w.any { it.isLetter() } && !w.contains(' ') }
                    .distinct().take(60)
                wordList = list
                words = list.map { it.lowercase() }.toSet()
                wordsAt = SystemClock.elapsedRealtime()
                lastError = null
            } catch (e: AiCheckClient.AiCheckException) {
                lastError = "${e.kind}: ${e.message ?: ""}".take(120)
                Log.w(TAG, "topic failed: ${e.kind}")
            } catch (e: Exception) {
                lastError = e.toString().take(120)
            } finally {
                inFlight = false
            }
        }
    }

    fun clear() { words = emptySet(); wordList = emptyList(); wordsAt = 0L }

    private fun fresh() = words.isNotEmpty() && SystemClock.elapsedRealtime() - wordsAt < EXPIRE_MS

    fun has(word: String): Boolean = fresh() && word.lowercase() in words

    /** reci teme (za dodavanje medju swipe kandidate), prazno ako je tema zastarela */
    fun current(): List<String> = if (fresh()) wordList else emptyList()

    fun strength(ctx: Context): Float = try {
        if (!ctx.getSetting(AiTopicEnabledSetting)) 0f else ctx.getSetting(AiTopicStrengthSetting)
    } catch (_: Exception) { 0f }

    /** jedan red za swipe log */
    fun logLine(): String {
        val age = if (wordsAt == 0L) -1 else (SystemClock.elapsedRealtime() - wordsAt) / 1000
        val req = " | zahteva danas $requestsToday"
        return if (!fresh()) "ai tema: nema" + (lastError?.let { " ($it)" } ?: "") + req
        else "ai tema: ${wordList.size} reci, pre ${age}s" + req + ": " + wordList.take(12).joinToString(" ")
    }
}
