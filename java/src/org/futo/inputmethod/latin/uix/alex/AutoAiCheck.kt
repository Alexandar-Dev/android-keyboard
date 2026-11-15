package org.futo.inputmethod.latin.uix.alex

// alex: automatska AI provera na pauzu u kucanju.
// Na drzanje razmaka (bez pomeranja) tekst iz polja ide Claude-u (isti klijent kao rucno AI dugme).
// Nadjene greske: oznaka u traci ("2 greške", tap = ispravi sve); kad je kursor u pogresnoj reci,
// ispravka je prvi predlog u traci (tap = ispravi samo tu rec).

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.Job
import org.futo.inputmethod.latin.uix.SettingsKey

val AutoAiCheckSetting = SettingsKey(booleanPreferencesKey("alex_ai_auto"), false)

const val AUTO_AI_DELAY_MS = 2500L
const val AUTO_AI_MIN_WORDS = 3

data class AutoIssue(val original: String, val replacement: String)

sealed class AutoAiStatus {
    object Idle : AutoAiStatus()
    object Checking : AutoAiStatus()
    data class Errors(val n: Int) : AutoAiStatus()
    object Ok : AutoAiStatus()
    object Failed : AutoAiStatus()   // AI nije odgovorio (mreza, kljuc...)
}

object AutoAiCheck {
    val status = mutableStateOf<AutoAiStatus>(AutoAiStatus.Idle)
    @Volatile var issues: List<AutoIssue> = emptyList()
    @Volatile var lastCheckedText: String? = null
    // reci koje su trenutno oznacene crvenom linijom u polju
    @Volatile var underlined: List<String> = emptyList()
    var job: Job? = null
    var hideJob: Job? = null

    // postavlja GeneralIME: ispravi sve nadjene greske u polju
    @Volatile var fixAllHandler: (() -> Unit)? = null

    fun replacementFor(word: String): String? {
        if (word.isEmpty()) return null
        val iss = issues.firstOrNull { it.original.equals(word, ignoreCase = true) } ?: return null
        // zadrzi veliko pocetno slovo ako ga rec ima
        val r = iss.replacement
        return if (word[0].isUpperCase() && r.isNotEmpty() && r[0].isLowerCase()) r.replaceFirstChar { it.uppercase() } else r
    }

    fun removeIssue(original: String) {
        issues = issues.filterNot { it.original.equals(original, ignoreCase = true) }
        status.value = if (issues.isEmpty()) AutoAiStatus.Ok else AutoAiStatus.Errors(issues.size)
    }

    fun reset() {
        job?.cancel(); hideJob?.cancel()
        issues = emptyList()
        underlined = emptyList()
        lastCheckedText = null
        status.value = AutoAiStatus.Idle
    }
}

private val ChipRed = Color(0xFFFFB3B3)
private val ChipRedBg = Color(0x40E2585A)
private val ChipGreen = Color(0xFFA6E3C0)
private val ChipGreenBg = Color(0x333FAE73)
private val ChipGrey = Color(0xFFC3C8D2)
private val ChipGreyBg = Color(0x33FFFFFF)

@Composable
fun AutoAiChip() {
    val s = AutoAiCheck.status.value
    if (s == AutoAiStatus.Checking) {
        // dok proverava: samo krug koji se vrti
        Box(Modifier.fillMaxHeight().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(16.dp), color = ChipGrey, strokeWidth = 2.dp)
        }
        return
    }
    val (text, fg, bg) = when (s) {
        AutoAiStatus.Idle -> return
        AutoAiStatus.Checking -> return
        is AutoAiStatus.Errors -> Triple(
            if (s.n == 1) "1 greška" else if (s.n in 2..4) "${s.n} greške" else "${s.n} grešaka",
            ChipRed, ChipRedBg
        )
        AutoAiStatus.Ok -> Triple("✓ OK", ChipGreen, ChipGreenBg)
        AutoAiStatus.Failed -> Triple("AI nije odgovorio", ChipGrey, ChipGreyBg)
    }
    Box(Modifier.fillMaxHeight().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .clickable(enabled = s is AutoAiStatus.Errors) { AutoAiCheck.fixAllHandler?.invoke() }
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
