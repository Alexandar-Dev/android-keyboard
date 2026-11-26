package org.futo.inputmethod.latin.uix.alex

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.actions.aicheck.AiCheckApiKey
import org.futo.inputmethod.latin.uix.actions.aicheck.AiCheckClient
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue

// alex: dopuna recenice. Kad korisnik zastane (~1 s), AI (Haiku) predlozi nastavak recenice;
// prikazuje se kao poseban red (drugi, ili treci ako je ukljucen red za sledecu rec) ili u traci
// predloga. Tap ubacuje ceo nastavak; svaki unos ga sklanja.
val AiComposeEnabledSetting = SettingsKey(booleanPreferencesKey("alex_ai_compose"), false)
// 0 = poseban red, 1 = u traci predloga
val AiComposePlacementSetting = SettingsKey(intPreferencesKey("alex_ai_compose_placement"), 0)

private const val PAUSE_MS = 1000L
private const val MIN_WORDS = 3
private const val MAX_TEXT = 1500
private const val MODEL = "claude-haiku-5-5"
val AI_COMPOSE_ROW_HEIGHT = 40.dp

object AiCompose {
    val suggestion = mutableStateOf<String?>(null)

    /** postavlja GeneralIME: trenutni tekst pre kursora (na glavnoj niti) */
    @Volatile var currentText: (() -> String?)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun clear() {
        job?.cancel(); job = null
        if (suggestion.value != null) suggestion.value = null
    }

    /**
     * poziva se posle svakog unosa (kucanje, swipe, pomeranje kursora). Sklanja stari predlog i,
     * ako je ukljuceno, posle pauze trazi novi. midTypedWord = korisnik je usred reci koju kuca.
     */
    fun onInput(ctx: Context, allowed: Boolean, midTypedWord: Boolean) {
        clear()
        if (!allowed || midTypedWord) return
        val on = try { ctx.getSetting(AiComposeEnabledSetting) } catch (_: Exception) { false }
        if (!on) return
        val app = ctx.applicationContext
        job = scope.launch {
            delay(PAUSE_MS)
            val text = currentText?.invoke()?.takeLast(MAX_TEXT) ?: return@launch
            if (text.isBlank() || text.trim().split(Regex("\\s+")).size < MIN_WORDS) return@launch
            val last = text.trimEnd().lastOrNull() ?: return@launch
            if (last == '.' || last == '!' || last == '?') return@launch   // recenica je zavrsena
            val key = try { app.getSetting(AiCheckApiKey) } catch (_: Exception) { "" }
            if (key.isBlank()) return@launch
            val cont = try {
                withContext(Dispatchers.IO) { AiCheckClient.complete(key, MODEL, text) }
            } catch (_: Exception) { null }
            val c = cont?.trim()?.trim('"', '„', '“')?.takeIf { it.isNotBlank() } ?: return@launch
            // tekst se u medjuvremenu promenio -> predlog vise ne vazi
            if (currentText?.invoke()?.takeLast(MAX_TEXT) != text) return@launch
            suggestion.value = c
            SwipeLog.event(app, true, "dopuna: ponudjeno \"$c\"")
        }
    }

    fun accept(manager: KeyboardManagerForAction) {
        val s = suggestion.value ?: return
        clear()
        val ime = manager.getLatinIMEForDebug()
        val prev = ime.currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
        val needSpace = prev.isNotEmpty() && !prev[0].isWhitespace() && s.first().isLetterOrDigit()
        manager.typeText(if (needSpace) " $s" else s)
        SwipeLog.event(ime, true, "dopuna: prihvaceno \"$s\"")
    }
}

@Composable
private fun ComposeChip(manager: KeyboardManagerForAction, text: String, modifier: Modifier, fontSize: Int) {
    val view = LocalView.current
    val acc = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(acc.copy(alpha = 0.12f))
            .clickable {
                manager.performHapticAndAudioFeedback(Constants.CODE_TAB, view)
                AiCompose.accept(manager)
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("AI →", color = acc, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = fontSize.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** poseban red (drugi ili treci) - prikazuje se samo kad postoji predlog */
@Composable
fun AiComposeRow(manager: KeyboardManagerForAction) {
    if (!useDataStoreValue(AiComposeEnabledSetting)) return
    if (useDataStoreValue(AiComposePlacementSetting) != 0) return
    val s = AiCompose.suggestion.value ?: return
    Box(Modifier.fillMaxWidth().height(AI_COMPOSE_ROW_HEIGHT).padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.CenterStart) {
        ComposeChip(manager, s, Modifier.fillMaxWidth().fillMaxHeight(), 15)
    }
}

/** u traci predloga, posle predloga (opcija "U traci predloga") */
@Composable
fun RowScope.AiComposeStripChip(manager: KeyboardManagerForAction?) {
    if (manager == null) return
    if (!useDataStoreValue(AiComposeEnabledSetting)) return
    if (useDataStoreValue(AiComposePlacementSetting) != 1) return
    val s = AiCompose.suggestion.value ?: return
    Box(Modifier.width(130.dp).fillMaxHeight().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        ComposeChip(manager, s, Modifier.fillMaxHeight(), 14)
    }
}

/** da li postoji red za dopunu (za racunanje visine) */
@Composable
fun aiComposeRowVisible(): Boolean =
    useDataStoreValue(AiComposeEnabledSetting) && useDataStoreValue(AiComposePlacementSetting) == 0 &&
        AiCompose.suggestion.value != null
