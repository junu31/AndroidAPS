package app.aaps.plugins.main.general.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Personal-fork: the calculator and meal records use the app theme like the other full screen windows. */
internal object ToolColors {

    var Bg = Color.Unspecified; private set
    var Card = Color.Unspecified; private set
    var Card2 = Color.Unspecified; private set
    var Line = Color.Unspecified; private set
    var Text = Color.Unspecified; private set
    var Sub = Color.Unspecified; private set
    var Dim = Color.Unspecified; private set
    var Accent = Color.Unspecified; private set
    var OnAccent = Color.Unspecified; private set

    /** Reads the current theme; called when a window opens. */
    @Composable
    fun Apply() {
        val c = MaterialTheme.colorScheme
        Bg = c.surface
        Card = c.surfaceContainer
        Card2 = c.surfaceContainerHigh
        Line = c.outlineVariant
        Text = c.onSurface
        Sub = c.onSurfaceVariant
        Dim = c.outline
        Accent = c.primary
        OnAccent = c.onPrimary
    }
}
