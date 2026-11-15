package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.SettingSlider
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.xlm.AutocorrectThresholdSetting
import org.futo.inputmethod.latin.xlm.BinaryDictTransformerWeightSetting
import org.futo.inputmethod.latin.xlm.SwipeLmWeightSetting
import org.futo.inputmethod.latin.xlm.AllowTransformerOnNonQWERTYLayouts
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
import org.futo.inputmethod.latin.uix.alex.ShowNextWordRowSetting
import org.futo.inputmethod.engine.general.UseExpandableSuggestionsForGeneralIME

@Preview
@Composable
fun AdvancedParametersScreen(navController: NavHostController = rememberNavController()) {
    val resources = LocalResources.current
    ScrollableList {
        ScreenTitle(stringResource(R.string.prediction_settings_transformer_advanced_params), showBack = true, navController)

        Tip(stringResource(R.string.prediction_settings_transformer_advanced_params_experimental_notice))

        // alex: jacina transformera kod swipe-a
        SettingSlider(
            title = stringResource(R.string.alex_swipe_lm_weight),
            subtitle = stringResource(R.string.alex_swipe_lm_weight_subtitle),
            setting = SwipeLmWeightSetting,
            range = 0.0f .. 1.0f,
            transform = { it },
            indicator = {
                if(it <= 0.01f) resources.getString(R.string.alex_swipe_lm_weight_off)
                else String.format("%.2f", it)
            },
            steps = 19
        )

        SettingToggleDataStore(
            title = stringResource(R.string.alex_next_word_row),
            subtitle = stringResource(R.string.alex_next_word_row_subtitle),
            setting = ShowNextWordRowSetting
        )

        SettingToggleDataStore(
            title = stringResource(R.string.alex_indicator_swipe),
            setting = org.futo.inputmethod.latin.uix.alex.ShowSwipeIndicatorSetting
        )

        SettingToggleDataStore(
            title = stringResource(R.string.alex_indicator_transformer),
            setting = org.futo.inputmethod.latin.uix.alex.ShowTransformerIndicatorSetting
        )

        // alex: dijagnosticki swipe log
        SettingToggleDataStore(
            title = stringResource(R.string.alex_swipe_log),
            subtitle = stringResource(R.string.alex_swipe_log_subtitle),
            setting = org.futo.inputmethod.latin.uix.alex.SwipeLogSetting
        )
        val ctx = LocalContext.current
        var logKb by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(org.futo.inputmethod.latin.uix.alex.SwipeLog.sizeKb(ctx)) }
        org.futo.inputmethod.latin.uix.settings.SettingItem(
            title = stringResource(R.string.alex_swipe_log_clear),
            subtitle = "$logKb KB",
            onClick = {
                org.futo.inputmethod.latin.uix.alex.SwipeLog.clear(ctx)
                logKb = org.futo.inputmethod.latin.uix.alex.SwipeLog.sizeKb(ctx)
            }
        ) { }
        org.futo.inputmethod.latin.uix.settings.SettingItem(
            title = stringResource(R.string.alex_swipe_log_save),
            subtitle = "Download/FUTO/",
            onClick = {
                val msg = try {
                    org.futo.inputmethod.latin.uix.alex.ShareSwipeLogActivity.saveToDownloads(ctx)
                        ?.let { ctx.getString(R.string.alex_swipe_log_saved, it) }
                        ?: ctx.getString(R.string.alex_swipe_log_empty)
                } catch (e: Exception) { e.toString() }
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        ) { }
        org.futo.inputmethod.latin.uix.settings.SettingItem(
            title = stringResource(R.string.alex_swipe_log_share),
            onClick = {
                ctx.startActivity(android.content.Intent(ctx, org.futo.inputmethod.latin.uix.alex.ShareSwipeLogActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        ) { }
        org.futo.inputmethod.latin.uix.settings.SettingItem(
            title = stringResource(R.string.alex_swipe_log_shortcut),
            subtitle = stringResource(R.string.alex_swipe_log_shortcut_subtitle),
            onClick = {
                val i = android.content.Intent(ctx, org.futo.inputmethod.latin.uix.alex.ShareSwipeLogActivity::class.java)
                    .setAction(org.futo.inputmethod.latin.uix.alex.ShareSwipeLogActivity.ACTION_SAVE)
                val sc = androidx.core.content.pm.ShortcutInfoCompat.Builder(ctx, "alex_swipe_log")
                    .setShortLabel(ctx.getString(R.string.alex_swipe_log_shortcut_label))
                    .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
                    .setIntent(i)
                    .build()
                if (androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
                    androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(ctx, sc, null)
                } else {
                    android.widget.Toast.makeText(ctx, R.string.alex_swipe_log_shortcut_unsupported, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        ) { }

        SettingToggleDataStore(
            title = stringResource(R.string.alex_scrollable_suggestions),
            subtitle = stringResource(R.string.alex_scrollable_suggestions_subtitle),
            setting = UseExpandableSuggestionsForGeneralIME
        )

        SettingToggleDataStore(
            title = stringResource(R.string.alex_transformer_non_qwerty),
            subtitle = stringResource(R.string.alex_transformer_non_qwerty_subtitle),
            setting = AllowTransformerOnNonQWERTYLayouts
        )

        SettingSlider(
            title = stringResource(R.string.prediction_settings_transformer_advanced_params_transformer_lm_strength),
            subtitle = stringResource(R.string.prediction_settings_transformer_advanced_params_transformer_lm_strength_subtitle, "${BinaryDictTransformerWeightSetting.default}"),
            setting = BinaryDictTransformerWeightSetting,
            range = 0.0f .. 100.0f,
            transform = {
                if(it > 99.9f) {
                    Float.POSITIVE_INFINITY
                } else if(it < 0.0001f) {
                    Float.NEGATIVE_INFINITY
                } else {
                    it
                }
            },
            indicator = {
                when {
                    it == Float.POSITIVE_INFINITY -> {
                        resources.getString(R.string.prediction_settings_transformer_advanced_params_transformer_lm_strength_always_transformer)
                    }
                    it == Float.NEGATIVE_INFINITY -> {
                        resources.getString(R.string.prediction_settings_transformer_advanced_params_transformer_lm_strength_always_binarydictionary)
                    }
                    (it > 0.1f) -> {
                        "a = ${String.format("%.1f", it)}"
                    }
                    else -> {
                        "a = $it"
                    }
                }
            },
            power = 2.5f
        )

        SettingSlider(
            title = stringResource(R.string.prediction_settings_transformer_advanced_params_autocorrect_threshold),
            subtitle = stringResource(
                R.string.prediction_settings_transformer_advanced_params_autocorrect_threshold_subtitle,
                AutocorrectThresholdSetting.default
            ),
            setting = AutocorrectThresholdSetting,
            range = 0.0f .. 25.0f,
            hardRange = 0.0f .. 25.0f,
            transform = {
                it
            },
            indicator = {
                "T = ${String.format("%.1f", it)}"
            },
            power = 2.5f
        )

    }
}