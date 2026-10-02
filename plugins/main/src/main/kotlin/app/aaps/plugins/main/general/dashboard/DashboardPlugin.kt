package app.aaps.plugins.main.general.dashboard

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanKey
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
    rh: ResourceHelper
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
        }
    }
}
