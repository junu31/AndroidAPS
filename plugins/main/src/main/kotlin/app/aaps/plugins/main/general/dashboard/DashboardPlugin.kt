package app.aaps.plugins.main.general.dashboard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.validators.preferences.AdaptiveClickPreference
import app.aaps.core.validators.preferences.AdaptiveListPreference
import java.io.File
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.main.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal-fork: Compose based alternative home screen shown as an extra tab.
 * Uses the same data as Overview; Overview itself stays untouched.
 */
@Singleton
class DashboardPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val uiInteraction: UiInteraction,
    private val preferences: Preferences
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(DashboardFragment::class.qualifiedName)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_home)
        .pluginName(R.string.dashboard)
        .shortName(R.string.dashboard_shortname)
        .enableByDefault(true)
        .visibleByDefault(true)
        .simpleModePosition(PluginDescription.Position.TAB)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .description(R.string.description_dashboard),
    aapsLogger, rh
) {

    // kept so the listener is not garbage collected (SharedPreferences holds listeners weakly)
    private var modelPathListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "dashboard_settings"
            title = rh.gs(R.string.dashboard)
            initialExpandedChildrenCount = 0
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = BooleanKey.DashboardBgCardBackground,
                    title = R.string.dashboard_bg_card_background, summary = R.string.dashboard_bg_card_background_summary
                )
            )
            // AI explanations (weekly review, loop decision): Gemini or a local model file; photo carbs always use Gemini
            addPreference(
                AdaptiveListPreference(
                    ctx = context, stringKey = StringKey.AiTextEngine, title = R.string.dashboard_ai_engine, summary = R.string.dashboard_ai_engine_summary,
                    entries = arrayOf("Gemini", rh.gs(R.string.dashboard_ai_engine_local)), entryValues = arrayOf("gemini", "local")
                )
            )
            addPreference(
                AdaptiveClickPreference(
                    ctx = context, stringKey = StringKey.AiLocalModelPath, title = R.string.dashboard_ai_model_file,
                    onPreferenceClickListener = {
                        context.startActivity(Intent(context, uiInteraction.localModelPickerActivity))
                        true
                    }
                ).apply {
                    // the picker is a separate screen: refresh the file name when the stored path changes
                    summaryProvider = Preference.SummaryProvider<Preference> {
                        val file = File(preferences.get(StringKey.AiLocalModelPath))
                        if (file.canRead()) "${file.name} · ${"%.1f".format(file.length() / 1e9)} GB" else rh.gs(R.string.dashboard_ai_model_none)
                    }
                    val pref = this
                    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
                        // re-setting the provider redraws the summary
                        if (changed == StringKey.AiLocalModelPath.key) pref.summaryProvider = pref.summaryProvider
                    }
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(listener)
                    modelPathListener = listener
                }
            )
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.AiLocalFallbackGemini, title = R.string.dashboard_ai_fallback, summary = R.string.dashboard_ai_fallback_summary))
        }
    }
}
