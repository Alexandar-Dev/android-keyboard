package org.futo.inputmethod.latin.uix.alex

// alex: dodatni red ispod predloga koji posle swipe-a nudi SLEDECU rec (transformer, prema recenici).
// Klizni red (prevuci ulevo za jos predloga). Ukljucuje se u podesavanjima (Text prediction -> Advanced parameters).

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import org.futo.inputmethod.latin.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue

val ShowNextWordRowSetting = SettingsKey(booleanPreferencesKey("alex_next_word_row"), false)

const val NEXT_WORD_ROW_MAX = 8
val NEXT_WORD_ROW_HEIGHT = 40.dp

object NextWordRowState {
    val words = mutableStateOf<List<String>>(emptyList())

    // Postavlja GeneralIME: trazi predloge za dati tekst pre kursora
    @Volatile var requester: ((String) -> Unit)? = null

    fun clear() {
        if (words.value.isNotEmpty()) words.value = emptyList()
    }
}

@Composable
fun NextWordRow(manager: KeyboardManagerForAction) {
    if (!useDataStoreValue(ShowNextWordRowSetting)) return
    val view = LocalView.current
    val words = NextWordRowState.words.value
    val sepColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val lineIcon = painterResource(id = R.drawable.transformer_suggestion)

    LazyRow(
        modifier = Modifier.fillMaxWidth().height(NEXT_WORD_ROW_HEIGHT).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(words) { i, w ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxHeight()) {
                if (i > 0) Box(Modifier.width(1.dp).height(18.dp).background(sepColor))
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 64.dp)
                        .drawBehind {
                            // ravna linija ispod = predlog od transformera (kao u FUTO-u)
                            with(lineIcon) {
                                val sz = lineIcon.intrinsicSize
                                translate(left = (size.width - sz.width) / 2f, top = size.height - sz.height * 2f) {
                                    draw(sz, colorFilter = ColorFilter.tint(textColor.copy(alpha = 0.7f)))
                                }
                            }
                        }
                        .clickable {
                            manager.performHapticAndAudioFeedback(Constants.CODE_TAB, view)
                            NextWordRowState.clear()
                            manager.typeText(w)
                            val ic = manager.getLatinIMEForDebug().currentInputConnection
                            val before = ic?.getTextBeforeCursor(500, 0)?.toString()
                            if (before != null) NextWordRowState.requester?.invoke(before)
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        w,
                        fontSize = 17.sp,
                        fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (i == 0) textColor else textColor.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
