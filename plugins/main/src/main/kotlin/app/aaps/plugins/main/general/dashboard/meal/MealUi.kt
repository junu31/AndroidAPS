package app.aaps.plugins.main.general.dashboard.meal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashColors

/** Personal-fork: small UI parts shared by the calculator and the meal records screen. */
internal object MealUi {

    fun gradeColor(g: MealGrade?): Color = when (g) {
        MealGrade.OK       -> DashColors.InRange
        MealGrade.UNDER    -> DashColors.High
        MealGrade.OVER     -> DashColors.Iob
        MealGrade.TIMING   -> DashColors.Low
        else               -> DashColors.Sub
    }

    fun gradeIcon(g: MealGrade?): String = when (g) {
        MealGrade.OK     -> "✅"
        MealGrade.UNDER  -> "🔺"
        MealGrade.OVER   -> "🔻"
        MealGrade.TIMING -> "⏱"
        else             -> "⚪"
    }

    /** "고지방 5h" */
    fun typeLabel(rh: ResourceHelper, t: FoodType) = MealTracker.typeText(rh, t) + " " + rh.gs(R.string.meal_hours_short, t.hours)

    /** the memo tags shown as quick chips; the ⚠ ones offer to exclude the meal */
    val TAGS = listOf(
        R.string.meal_tag_big, R.string.meal_tag_small, R.string.meal_tag_slow, R.string.meal_tag_late_bolus,
        R.string.meal_tag_exercise, R.string.meal_tag_sick, R.string.meal_tag_sensor
    )
    val WARN_TAGS = setOf(R.string.meal_tag_exercise, R.string.meal_tag_sick, R.string.meal_tag_sensor)

    fun similar(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || (x.length >= 2 && y.contains(x)) || (y.length >= 2 && x.contains(y)))
    }

    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}

@Composable
internal fun GradeChip(rh: ResourceHelper, g: MealGrade?, pending: Boolean = false) {
    val c = if (pending) DashColors.Sub else MealUi.gradeColor(g)
    Text(
        if (pending) "⏳ " + rh.gs(R.string.meal_pending) else MealUi.gradeIcon(g) + " " + MealTracker.gradeText(rh, g ?: MealGrade.EXCLUDED),
        color = c, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
internal fun MealChip(text: String, on: Boolean, color: Color = DashColors.Accent, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, fontSize = 11.5.sp, maxLines = 1,
        color = if (on) color else DashColors.Sub, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, if (on) color.copy(alpha = 0.6f) else DashColors.Line, RoundedCornerShape(999.dp))
            .background(if (on) color.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
internal fun MealSwitch(on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 38.dp, height = 21.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) DashColors.Accent else DashColors.Grid)
            .clickable(onClick = onClick)
            .padding(2.5.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(if (on) DashColors.Bg else DashColors.Sub)
        )
    }
}
