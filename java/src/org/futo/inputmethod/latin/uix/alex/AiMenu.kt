package org.futo.inputmethod.latin.uix.alex

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.actions.aicheck.ApiKeySetting
import org.futo.inputmethod.latin.uix.actions.aicheck.ModelSetting
import org.futo.inputmethod.latin.uix.settings.SettingSlider
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore

// alex: glavni meni "AI" (ispod Text prediction): kljuc, model za proveru, AI tema.
// Isti meni otvara i zupcanik akcije AI provera.
private val TOPIC_MODELS = listOf(
    "claude-haiku-5-5" to R.string.ai_check_model_haiku,
    "claude-sonnet-5-5" to R.string.ai_check_model_sonnet,
)

// pod-podesavanje prekidaca: uvuceno, zatamnjeno i neaktivno dok prekidac nije ukljucen
@Composable
internal fun DisabledUnless(setting: org.futo.inputmethod.latin.uix.SettingsKey<Boolean>, content: @Composable () -> Unit) {
    val on = useDataStore(setting).value
    // uvuceno da se poravna sa tekstom prekidaca iznad (SettingItem: 4+16+48+12 = 80dp, a sadrzaj ima svojih 16dp)
    Box(Modifier.padding(start = 64.dp).alpha(if (on) 1f else 0.38f)) {
        content()
        if (!on) {
            // presretne dodire, da se ne menja dok je iskljuceno
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            })
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 4.dp))
}

@Composable
private fun ComposePlacementSetting() {
    val (v, setV) = useDataStore(AiComposePlacementSetting)
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text(stringResource(R.string.alex_ai_compose_where), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        listOf(0 to R.string.alex_ai_compose_where_row, 1 to R.string.alex_ai_compose_where_strip).forEach { (id, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = v == id, onClick = { setV(id) }).padding(vertical = 4.dp).offset(x = (-12).dp)
            ) {
                RadioButton(selected = v == id, onClick = { setV(id) })
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun TopicModelSetting() {
    val (model, setModel) = useDataStore(AiTopicModelSetting)
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text(stringResource(R.string.alex_ai_topic_model), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        TOPIC_MODELS.forEach { (id, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = model == id, onClick = { setModel(id) }).padding(vertical = 4.dp).offset(x = (-12).dp)
            ) {
                RadioButton(selected = model == id, onClick = { setModel(id) })
                Text(stringResource(label))
            }
        }
    }
}


@Composable
private fun UsageStatus() {
    val ctx = LocalContext.current
    // osvezava se svake 2 s dok je ekran otvoren
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { AiUsage.init(ctx); while (true) { delay(2000); tick++ } }
    val st = remember(tick) { AiUsage.stats(ctx) }
    // uokvireno: blaga podloga i polutransparentan okvir u boji akcenta
    val acc = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(acc.copy(alpha = 0.06f))
        .border(1.dp, acc.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
        .padding(14.dp, 10.dp)) {
        Text(stringResource(R.string.alex_ai_usage_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.alex_ai_usage_today, st.dayCount, AiUsage.money(st.dayCost)))
        Text(stringResource(R.string.alex_ai_usage_month, st.monthCount, AiUsage.money(st.monthCost)),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.alex_ai_usage_estimate, AiUsage.money(AiUsage.monthlyEstimate(st))),
            style = MaterialTheme.typography.bodySmall)
        // Reset ispod (dva tapa: prvi pita "Sigurno?", drugi brise)
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
        TextButton(onClick = {
            if (armed) { AiUsage.reset(ctx); armed = false; tick++ } else armed = true
        }, modifier = Modifier.offset(x = (-12).dp)) {
            Text(stringResource(if (armed) R.string.alex_ai_usage_reset_confirm else R.string.alex_ai_usage_reset),
                color = if (armed) MaterialTheme.colorScheme.error else acc)
        }
    }
}

val AiSettingsMenu = UserSettingsMenu(
    title = R.string.alex_ai_menu_title,
    navPath = "ai",
    registerNavPath = true,
    settings = listOf(
        UserSetting(name = R.string.ai_check_key_title, component = { ApiKeySetting() }),
        UserSetting(name = R.string.alex_ai_usage_title, component = { UsageStatus() }),
        userSettingDecorationOnly { SectionTitle(stringResource(R.string.ai_check_settings_title)) },
        UserSetting(name = R.string.ai_check_model_title, component = { ModelSetting() }),
        userSettingDecorationOnly { SectionTitle(stringResource(R.string.alex_ai_topic_title)) },
        userSettingToggleDataStore(
            title = R.string.alex_ai_topic_enable,
            subtitle = R.string.alex_ai_topic_enable_subtitle,
            setting = AiTopicEnabledSetting
        ),
        UserSetting(name = R.string.alex_ai_topic_strength) {
            DisabledUnless(AiTopicEnabledSetting) { SettingSlider(
                title = stringResource(R.string.alex_ai_topic_strength),
                subtitle = stringResource(R.string.alex_ai_topic_strength_subtitle),
                setting = AiTopicStrengthSetting,
                range = 0.1f..1.0f,
                transform = { it },
                indicator = { String.format("%.1f", it) },
                steps = 8
            ) }
        },
        UserSetting(name = R.string.alex_ai_topic_model, component = {
            DisabledUnless(AiTopicEnabledSetting) { TopicModelSetting() }
        }),
        userSettingDecorationOnly { SectionTitle(stringResource(R.string.alex_ai_compose_title)) },
        userSettingToggleDataStore(
            title = R.string.alex_ai_compose_enable,
            subtitle = R.string.alex_ai_compose_enable_subtitle,
            setting = AiComposeEnabledSetting
        ),
        UserSetting(name = R.string.alex_ai_compose_where, component = {
            DisabledUnless(AiComposeEnabledSetting) { ComposePlacementSetting() }
        }),
    )
)
