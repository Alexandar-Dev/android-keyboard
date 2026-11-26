package org.futo.inputmethod.latin.uix.alex

import androidx.compose.foundation.layout.Column
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

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 4.dp))
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
                modifier = Modifier.fillMaxWidth().selectable(selected = model == id, onClick = { setModel(id) }).padding(vertical = 4.dp)
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
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text(stringResource(R.string.alex_ai_usage_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.alex_ai_usage_today, st.dayCount, AiUsage.money(st.dayCost)))
        Text(stringResource(R.string.alex_ai_usage_month, st.monthCount, AiUsage.money(st.monthCost)),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.alex_ai_usage_estimate, AiUsage.money(AiUsage.monthlyEstimate(st))),
            style = MaterialTheme.typography.bodySmall)
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
            SettingSlider(
                title = stringResource(R.string.alex_ai_topic_strength),
                subtitle = stringResource(R.string.alex_ai_topic_strength_subtitle),
                setting = AiTopicStrengthSetting,
                range = 0.1f..1.0f,
                transform = { it },
                indicator = { String.format("%.1f", it) },
                steps = 8
            )
        },
        UserSetting(name = R.string.alex_ai_topic_model, component = { TopicModelSetting() }),
    )
)
