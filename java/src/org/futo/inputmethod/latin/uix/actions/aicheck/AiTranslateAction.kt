package org.futo.inputmethod.latin.uix.actions.aicheck

// alex: prevod teksta iz polja (srpski -> engleski, engleski -> srpski) preko Claude API-ja.
// Koristi isti kljuc i model kao AI provera.

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.getSetting

private const val MAX_CHARS_T = 4000

private sealed class TrState {
    object Empty : TrState()
    object NoKey : TrState()
    object Loading : TrState()
    data class Error(val kind: AiCheckClient.ErrorKind, val message: String?) : TrState()
    data class Done(val text: String) : TrState()
}

private class AiTranslateWindow(private val manager: KeyboardManagerForAction) : ActionWindow() {
    private var state by mutableStateOf<TrState>(TrState.Loading)

    init { start() }

    private fun readFieldText(): String? {
        val ic = manager.getLatinIMEForDebug().currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(MAX_CHARS_T, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(MAX_CHARS_T, 0)?.toString() ?: ""
        return before + after
    }

    private fun start() {
        val text = readFieldText()
        if (text.isNullOrBlank()) { state = TrState.Empty; return }
        val ctx = manager.getContext()
        val key = ctx.getSetting(AiCheckApiKey)
        if (key.isBlank()) { state = TrState.NoKey; return }
        val model = ctx.getSetting(AiCheckModel)
        state = TrState.Loading
        manager.getLifecycleScope().launch {
            state = try {
                TrState.Done(withContext(Dispatchers.IO) { AiCheckClient.translate(key, model, text) })
            } catch (e: AiCheckClient.AiCheckException) {
                Log.e("AiTranslate", "translate failed: ${e.kind}", e)
                TrState.Error(e.kind, e.message)
            }
        }
    }

    private fun replaceField(newText: String) {
        val ic = manager.getLatinIMEForDebug().currentInputConnection ?: return
        val txn = try { manager.createInputTransaction() } catch (e: Exception) { null }
        try {
            ic.beginBatchEdit()
            ic.finishComposingText()
            val b = ic.getTextBeforeCursor(MAX_CHARS_T, 0)?.length ?: 0
            val a = ic.getTextAfterCursor(MAX_CHARS_T, 0)?.length ?: 0
            ic.deleteSurroundingText(b, a)
            ic.commitText(newText, 1)
        } finally {
            ic.endBatchEdit()
            txn?.commit("")
        }
    }

    @Composable
    override fun windowName(): String = stringResource(R.string.ai_translate_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp, 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (val s = state) {
                TrState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.ai_translate_loading))
                }
                TrState.Empty -> Text(stringResource(R.string.ai_check_empty))
                TrState.NoKey -> Text(stringResource(R.string.ai_check_no_key))
                is TrState.Error -> {
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
                is TrState.Done -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(s.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { replaceField(s.text); manager.closeActionWindow() },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.ai_translate_replace)) }
                        FilledTonalButton(
                            onClick = { manager.closeActionWindow() },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.ai_check_done)) }
                    }
                }
            }
        }
    }
}

val AiTranslateAction = Action(
    icon = R.drawable.ai_translate,
    name = R.string.ai_translate_title,
    simplePressImpl = null,
    windowImpl = { manager, _ -> AiTranslateWindow(manager) },
)
