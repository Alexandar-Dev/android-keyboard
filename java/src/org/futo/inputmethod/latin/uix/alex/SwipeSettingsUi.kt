package org.futo.inputmethod.latin.uix.alex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.settings.SettingSlider
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
import org.futo.inputmethod.latin.xlm.SwipeLmWeightSetting

// alex: nase swipe opcije, premestene iz Advanced Parameters u Swipe meni
// ("Transformer swipe" i "Diagnostics")

@Composable
fun SwipeTransformerWeightSetting() {
    val resources = LocalResources.current
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
}

@Composable
fun SwipeCandidatesSliderSetting() {
        // alex: broj swipe kandidata koje transformer vidi
        SettingSlider(
            title = stringResource(R.string.alex_swipe_candidates),
            subtitle = stringResource(R.string.alex_swipe_candidates_subtitle),
            setting = org.futo.inputmethod.latin.xlm.SwipeCandidatesSetting,
            range = 4.0f .. 16.0f,
            hardRange = 4.0f .. 16.0f,
            transform = { kotlin.math.round(it) },
            indicator = { "${kotlin.math.round(it).toInt()}" },
            steps = 11
        )
}

@Composable
fun SwipeLineSettings() {
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
}

@Composable
fun SwipeLogSettings() {
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
}
