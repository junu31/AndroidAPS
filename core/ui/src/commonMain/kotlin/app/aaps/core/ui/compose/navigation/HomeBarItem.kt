package app.aaps.core.ui.compose.navigation

import androidx.compose.ui.graphics.vector.ImageVector

/** Personal-fork: one entry of the later pages of the Dashboard's bottom bar (what the 4.0 navigation bar and toolbar offer). */
data class HomeBarItem(val label: String, val icon: ImageVector, val badge: String? = null, val enabled: Boolean = true, val onClick: () -> Unit)
