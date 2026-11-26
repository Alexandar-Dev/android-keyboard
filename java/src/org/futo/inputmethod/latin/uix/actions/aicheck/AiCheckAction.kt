package org.futo.inputmethod.latin.uix.actions.aicheck

// alex: AI provera pravopisa i gramatike (Claude API).
// Klik na AI: tekst iz polja ide Claude-u, greske se prikazu crveno u prozoru umesto tastature.
// Klik na crvenu rec: ispravka + objasnjenje, "Ispravi" menja samo tu rec. "Ispravi sve" menja sve.

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore

val AiCheckApiKey = SettingsKey(stringPreferencesKey("alex_ai_check_api_key"), "")
val AiCheckModel = SettingsKey(stringPreferencesKey("alex_ai_check_model"), AiCheckClient.DEFAULT_MODEL)

private val AI_MODELS = listOf(
    "claude-opus-5-5" to R.string.ai_check_model_opus,
    "claude-sonnet-5-5" to R.string.ai_check_model_sonnet,
    "claude-haiku-5-5" to R.string.ai_check_model_haiku,
)

private const val TAG = "AiCheck"
private const val MAX_CHARS = 4000

private val ErrRed = Color(0xFFFF6B6F)
private val OkGreen = Color(0xFF5FD39C)

// Deo teksta: obican tekst ili greska (sa indeksom u listi gresaka)
private data class Segment(val text: String, val issue: Int = -1)

private fun buildSegments(text: String, issues: List<AiCheckResult.Issue>): Pair<List<Segment>, List<AiCheckResult.Issue>> {
    val segs = mutableListOf<Segment>()
    val found = mutableListOf<AiCheckResult.Issue>()
    var pos = 0
    for (iss in issues) {
        val orig = iss.original ?: continue
        if (orig.isEmpty() || iss.replacement == null || iss.replacement == orig) continue
        val at = text.indexOf(orig, pos)
        if (at < 0) continue
        if (at > pos) segs.add(Segment(text.substring(pos, at)))
        segs.add(Segment(orig, found.size))
        found.add(iss)
        pos = at + orig.length
    }
    if (pos < text.length) segs.add(Segment(text.substring(pos)))
    return segs to found
}

private sealed class UiState {
    object Empty : UiState()
    object NoKey : UiState()
    object Loading : UiState()
    data class Error(val kind: AiCheckClient.ErrorKind, val message: String?) : UiState()
    data class Result(val segments: List<Segment>, val issues: List<AiCheckResult.Issue>) : UiState()
}

private class AiCheckWindow(private val manager: KeyboardManagerForAction) : ActionWindow() {
    private var state by mutableStateOf<UiState>(UiState.Loading)
    private var fixed by mutableStateOf(setOf<Int>())
    private var selected by mutableStateOf(-1)

    init {
        start()
    }

    private fun readFieldText(): String? {
        val ic = manager.getLatinIMEForDebug().currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(MAX_CHARS, 0)?.toString() ?: ""
        return before + after
    }

    private fun start() {
        val text = readFieldText()
        if (text.isNullOrBlank()) { state = UiState.Empty; return }
        val ctx = manager.getContext()
        val key = ctx.getSetting(AiCheckApiKey)
        if (key.isBlank()) { state = UiState.NoKey; return }
        val model = ctx.getSetting(AiCheckModel)
        state = UiState.Loading
        fixed = setOf(); selected = -1
        manager.getLifecycleScope().launch {
            state = try {
                val result = withContext(Dispatchers.IO) { AiCheckClient.check(key, model, text) }
                val (segs, found) = buildSegments(text, result.issues)
                UiState.Result(segs, found)
            } catch (e: AiCheckClient.AiCheckException) {
                Log.e(TAG, "AI check failed: ${e.kind}", e)
                UiState.Error(e.kind, e.message)
            }
        }
    }

    // Zameni ceo tekst u polju (sa vec primenjenim ispravkama)
    private fun applyToField(segments: List<Segment>, fixedSet: Set<Int>, issues: List<AiCheckResult.Issue>) {
        val newText = segments.joinToString("") {
            if (it.issue >= 0 && it.issue in fixedSet) issues[it.issue].replacement else it.text
        }
        val ic = manager.getLatinIMEForDebug().currentInputConnection ?: return
        val txn = try { manager.createInputTransaction() } catch (e: Exception) { null }
        try {
            ic.beginBatchEdit()
            ic.finishComposingText()
            val b = ic.getTextBeforeCursor(MAX_CHARS, 0)?.length ?: 0
            val a = ic.getTextAfterCursor(MAX_CHARS, 0)?.length ?: 0
            ic.deleteSurroundingText(b, a)
            ic.commitText(newText, 1)
        } finally {
            ic.endBatchEdit()
            txn?.commit("")
        }
    }

    private fun fix(indices: Collection<Int>) {
        val s = state as? UiState.Result ?: return
        fixed = fixed + indices
        selected = -1
        applyToField(s.segments, fixed, s.issues)
    }

    @Composable
    override fun windowName(): String = stringResource(R.string.ai_check_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp, 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (val s = state) {
                UiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.ai_check_loading))
                }
                UiState.Empty -> Text(stringResource(R.string.ai_check_empty))
                UiState.NoKey -> Text(stringResource(R.string.ai_check_no_key))
                is UiState.Error -> {
                    Text(stringResource(when (s.kind) {
                        AiCheckClient.ErrorKind.BAD_KEY -> R.string.ai_check_err_key
                        AiCheckClient.ErrorKind.RATE_LIMIT -> R.string.ai_check_err_rate
                        AiCheckClient.ErrorKind.NETWORK -> R.string.ai_check_err_network
                        AiCheckClient.ErrorKind.REFUSED -> R.string.ai_check_err_refused
                        AiCheckClient.ErrorKind.NO_KEY -> R.string.ai_check_no_key
                        AiCheckClient.ErrorKind.OTHER -> R.string.ai_check_err_other
                    }))
                    if (s.kind == AiCheckClient.ErrorKind.OTHER && !s.message.isNullOrBlank()) {
                        Text(s.message.take(200), style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick = { start() }) { Text(stringResource(R.string.ai_check_retry)) }
                }
                is UiState.Result -> ResultView(s)
            }
        }
    }

    @Composable
    private fun ColumnScope.ResultView(s: UiState.Result) {
        val left = s.issues.indices.count { it !in fixed }
        Text(
            if (s.issues.isEmpty()) stringResource(R.string.ai_check_no_mistakes)
            else if (left == 0) stringResource(R.string.ai_check_all_fixed)
            else stringResource(R.string.ai_check_count, left),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val onSurface = MaterialTheme.colorScheme.onSurface
        val annotated = buildAnnotatedString {
            s.segments.forEach { seg ->
                if (seg.issue < 0) {
                    withStyle(SpanStyle(color = onSurface)) { append(seg.text) }
                } else if (seg.issue in fixed) {
                    withStyle(SpanStyle(color = OkGreen)) { append(s.issues[seg.issue].replacement) }
                } else {
                    pushStringAnnotation("issue", seg.issue.toString())
                    withStyle(SpanStyle(
                        color = ErrRed,
                        textDecoration = TextDecoration.Underline,
                        background = if (seg.issue == selected) ErrRed.copy(alpha = 0.25f) else ErrRed.copy(alpha = 0.12f)
                    )) { append(seg.text) }
                    pop()
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            @Suppress("DEPRECATION")
            ClickableText(
                text = annotated,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp),
                onClick = { offset ->
                    annotated.getStringAnnotations("issue", offset, offset).firstOrNull()?.let {
                        selected = it.item.toInt()
                    }
                }
            )
        }

        val sel = selected
        if (sel >= 0 && sel < s.issues.size && sel !in fixed) {
            val iss = s.issues[sel]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("${iss.original}  →  ${iss.replacement}", style = MaterialTheme.typography.titleMedium)
                if (!iss.explanation.isNullOrBlank()) {
                    Text(iss.explanation, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { fix(listOf(sel)) }) { Text(stringResource(R.string.ai_check_fix)) }
                    FilledTonalButton(onClick = { selected = -1 }) { Text(stringResource(R.string.ai_check_ignore)) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { fix(s.issues.indices.toList()) },
                enabled = left > 0,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.ai_check_fix_all)) }
            FilledTonalButton(
                onClick = { manager.closeActionWindow() },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.ai_check_done)) }
        }
    }
}

@Composable
internal fun ApiKeySetting() {
    val (key, setKey) = useDataStore(AiCheckApiKey)
    var text by remember { mutableStateOf(key) }
    var status by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = useDataStore(AiCheckModel).value
    val okText = stringResource(R.string.ai_check_key_ok)
    val testing = stringResource(R.string.ai_check_key_testing)

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_check_key_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; setKey(it.trim()); status = null },
            singleLine = true,
            placeholder = { Text("sk-ant-...") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Text(stringResource(R.string.ai_check_key_hint), style = MaterialTheme.typography.bodySmall)
        FilledTonalButton(onClick = {
            status = testing
            scope.launch {
                status = try {
                    withContext(Dispatchers.IO) { AiCheckClient.testKey(text.trim(), model) }
                    okText
                } catch (e: AiCheckClient.AiCheckException) {
                    context.getString(when (e.kind) {
                        AiCheckClient.ErrorKind.BAD_KEY -> R.string.ai_check_err_key
                        AiCheckClient.ErrorKind.NO_KEY -> R.string.ai_check_no_key
                        AiCheckClient.ErrorKind.RATE_LIMIT -> R.string.ai_check_err_rate
                        AiCheckClient.ErrorKind.NETWORK -> R.string.ai_check_err_network
                        else -> R.string.ai_check_err_other
                    }) + (if (e.kind == AiCheckClient.ErrorKind.OTHER) " ${e.message ?: ""}".take(200) else "")
                }
            }
        }) { Text(stringResource(R.string.ai_check_key_test)) }
        status?.let { Text(it, color = if (it == okText) OkGreen else MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun ModelSetting() {
    val (model, setModel) = useDataStore(AiCheckModel)
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text(stringResource(R.string.ai_check_model_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        AI_MODELS.forEach { (id, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = model == id, onClick = { setModel(id) }).padding(vertical = 4.dp).offset(x = 52.dp)
            ) {
                RadioButton(selected = model == id, onClick = { setModel(id) })
                Text(stringResource(label))
            }
        }
        Text(stringResource(R.string.ai_check_privacy), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 64.dp, top = 4.dp)) // u liniji sa tekstom opcija (80dp)
    }
}

val AiCheckAction = Action(
    icon = R.drawable.ai_check,
    name = R.string.ai_check_title,
    simplePressImpl = null,
    windowImpl = { manager, _ -> AiCheckWindow(manager) },
    // alex: podesavanja su sada u glavnom meniju "AI" (isti meni)
    settingsMenu = org.futo.inputmethod.latin.uix.alex.AiSettingsMenu
)
