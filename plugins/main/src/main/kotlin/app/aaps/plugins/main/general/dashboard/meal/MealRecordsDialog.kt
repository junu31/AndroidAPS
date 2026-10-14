package app.aaps.plugins.main.general.dashboard.meal

import android.app.Dialog
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.ai.AiTextEngine
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.utils.MidnightUtils
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashColors
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.round

/**
 * Personal-fork: 「식사 기록」 — tracked foods, the evaluation of every meal, memos and an optional AI pattern summary.
 * Grades and suggestions are calculated ([MealMath]); the AI only summarises the pattern over several meals.
 */
class MealRecordsDialog : DaggerDialogFragment() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var mealTracker: MealTracker
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var aiTextEngine: AiTextEngine
    @Inject lateinit var aapsSchedulers: AapsSchedulers

    private enum class Screen { LIST, FOOD, MEAL }
    private enum class Filter { ALL, ADJUST, PENDING, LEGACY }

    private val disposable = CompositeDisposable()
    private var data by mutableStateOf(MealData())
    private var screen by mutableStateOf(Screen.LIST)
    private var foodId by mutableStateOf("")
    private var mealId by mutableStateOf("")
    private var filter by mutableStateOf(Filter.ALL)
    private var query by mutableStateOf("")
    private var aiText by mutableStateOf<String?>(null)
    private var aiBusy by mutableStateOf(false)
    private var deleteAsk by mutableStateOf(false)
    private var deleteAiId by mutableStateOf("")
    private var renaming by mutableStateOf(false)
    private var renameText by mutableStateOf("")

    // memo being edited on the meal screen
    private var memoTags by mutableStateOf(listOf<String>())
    private var memoText by mutableStateOf("")
    private var memoExclude by mutableStateOf(false)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        ComponentDialog(requireContext(), android.R.style.Theme_Black_NoTitleBar).also { d ->
            d.onBackPressedDispatcher.addCallback(this) { if (!back()) dismiss() }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.setBackgroundDrawable(ColorDrawable(AndroidColor.parseColor("#0E1116")))
        data = mealTracker.data()
        disposable += mealTracker.changes.observeOn(aapsSchedulers.main).subscribe { data = it }
        // evaluate anything that is due (and drop cancelled confirmations) before showing
        disposable += io.reactivex.rxjava3.core.Completable.fromAction { mealTracker.checkDue() }
            .subscribeOn(aapsSchedulers.io).observeOn(aapsSchedulers.main).subscribe({ data = mealTracker.data() }, {})
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { Root() }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onDestroy() {
        disposable.clear()
        super.onDestroy()
    }

    private fun back(): Boolean = when (screen) {
        Screen.MEAL -> {
            screen = if (foodId.isEmpty()) Screen.LIST else Screen.FOOD; true
        }

        Screen.FOOD -> {
            screen = Screen.LIST; foodId = ""; aiText = null; deleteAsk = false; renaming = false; true
        }

        Screen.LIST -> false
    }

    private fun meals(id: String) = data.meals.filter { it.foodId == id && !it.legacy }.sortedByDescending { it.time }
    private fun food(id: String) = data.foods.firstOrNull { it.id == id }
    private fun icNow(time: Long) = profileFunction.getProfile()?.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight(time))
    private fun f1(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.getDefault(), "%.2f", v)
    private val day = SimpleDateFormat("M/d", Locale.getDefault())
    private val dayTime = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** like the calculator: the latest carbs amount against the current profile; percent of the meal insulin */
    private fun suggestionPct(id: String): Pair<MealMath.Suggestion, Int>? {
        val m = meals(id)
        val carbs = m.firstOrNull()?.carbs ?: return null
        val s = MealMath.suggest(m, carbs, icNow(m.first().time) ?: return null, 0.0) ?: return null
        return s to round((s.historyInsulin.coerceIn(s.currentInsulin * (1 - MealMath.CAP), s.currentInsulin * (1 + MealMath.CAP)) / s.currentInsulin - 1) * 100).toInt()
    }

    private fun openMeal(m: MealRecord) {
        mealId = m.id
        memoTags = m.memoTags
        memoText = m.memo
        memoExclude = m.excludedByUser
        screen = Screen.MEAL
        if (!m.seen && m.eval != null) mealTracker.update { d -> d.copy(meals = d.meals.map { if (it.id == m.id) it.copy(seen = true) else it }) }
    }

    // ---------- UI ----------

    @Composable
    private fun Root() {
        Column(
            Modifier
                .fillMaxSize()
                .background(DashColors.Bg)
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            when (screen) {
                Screen.LIST -> ListScreen()
                Screen.FOOD -> food(foodId)?.let { FoodScreen(it) } ?: run { screen = Screen.LIST }
                Screen.MEAL -> data.meals.firstOrNull { it.id == mealId }?.let { MealScreen(it) } ?: run { screen = Screen.LIST }
            }
        }
    }

    @Composable
    private fun Header(title: String, right: String = "", onBack: (() -> Unit)?, onRight: (() -> Unit)? = null) {
        Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (onBack != null) "‹" else "✕", color = DashColors.Text, fontSize = 22.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onBack?.invoke() ?: dismiss() }
                    .padding(horizontal = 8.dp)
            )
            Text(title, color = DashColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (right.isNotEmpty())
                Text(
                    right, color = if (onRight != null) DashColors.Accent else DashColors.Sub, fontSize = if (onRight != null) 13.sp else 11.5.sp,
                    modifier = if (onRight != null) Modifier
                        .clickable(onClick = onRight)
                        .padding(8.dp) else Modifier
                )
        }
    }

    @Composable
    private fun Card(modifier: Modifier = Modifier, border: Color = DashColors.Line, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
        Column(
            modifier
                .padding(bottom = 9.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DashColors.Card)
                .border(1.dp, border, RoundedCornerShape(14.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(12.dp)
        ) { content() }
    }

    @Composable
    private fun ListScreen() {
        val tracked = data.foods.filter { it.tracked }
        val pending = data.meals.count { it.confirmed && !it.legacy && it.eval == null }
        Header("🍽 " + rh.gs(R.string.meal_records), rh.gs(R.string.meal_tracked_foods, tracked.size), null)
        BasicTextField(
            query, { query = it }, singleLine = true,
            textStyle = TextStyle(color = DashColors.Text, fontSize = 14.sp), cursorBrush = SolidColor(DashColors.Accent),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("🔍 " + rh.gs(R.string.meal_search), color = DashColors.Dim, fontSize = 13.sp)
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DashColors.Card)
                .border(1.dp, DashColors.Line, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
        Row(
            Modifier
                .padding(vertical = 10.dp)
                .horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MealChip(rh.gs(R.string.meal_filter_all), filter == Filter.ALL) { filter = Filter.ALL }
            MealChip(rh.gs(R.string.meal_filter_adjust), filter == Filter.ADJUST) { filter = Filter.ADJUST }
            MealChip(rh.gs(R.string.meal_filter_pending, pending), filter == Filter.PENDING) { filter = Filter.PENDING }
            MealChip(rh.gs(R.string.meal_filter_legacy), filter == Filter.LEGACY) { filter = Filter.LEGACY }
        }
        if (filter == Filter.LEGACY) {
            LegacyList()
            return
        }
        // tracked foods; an untracked food is listed only while it still has records
        val foods = data.foods
            .filter { f -> f.tracked || meals(f.id).isNotEmpty() }
            .filter { query.isBlank() || MealUi.similar(it.name, query) }
            .sortedByDescending { f -> meals(f.id).firstOrNull()?.time ?: f.created }
            .filter { f ->
                when (filter) {
                    Filter.ADJUST  -> suggestionPct(f.id)?.let { abs(it.second) >= 5 } == true
                    Filter.PENDING -> meals(f.id).any { it.confirmed && it.eval == null }
                    else           -> true
                }
            }
        if (foods.isEmpty()) Text(rh.gs(R.string.meal_empty), color = DashColors.Sub, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 20.dp))
        foods.forEach { f -> FoodCard(f) }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(MealGrade.OK, MealGrade.UNDER, MealGrade.OVER, MealGrade.TIMING, MealGrade.EXCLUDED).forEach {
                Legend(MealUi.gradeColor(it), MealTracker.gradeText(rh, it))
            }
        }
    }

    @Composable
    private fun FoodCard(f: MealFood) {
        val m = meals(f.id)
        val evaluated = m.filter { it.eval != null }
        val unseen = m.count { it.eval != null && !it.seen }
        val pending = m.firstOrNull { it.confirmed && it.eval == null }
        Card(border = if (unseen > 0) DashColors.Accent.copy(alpha = 0.55f) else DashColors.Line, onClick = { foodId = f.id; aiText = null; screen = Screen.FOOD }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, color = DashColors.Text, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (unseen > 0) Text(
                            " $unseen ", color = DashColors.Bg, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(DashColors.Accent)
                        )
                    }
                    Text(
                        MealUi.typeLabel(rh, f.type) + " · " + rh.gs(R.string.meal_counts, m.count { it.usable }, evaluated.size) +
                            (m.firstOrNull()?.let { " · " + day.format(Date(it.time)) } ?: "") + if (!f.tracked) " · " + rh.gs(R.string.meal_untracked) else "",
                        color = DashColors.Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        m.take(8).forEach { r ->
                            val pend = r.eval == null
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(if (pend) Color.Transparent else MealUi.gradeColor(if (r.excludedByUser) MealGrade.EXCLUDED else r.eval?.grade))
                                    .border(1.dp, if (pend) DashColors.Sub else Color.Transparent, CircleShape)
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    val sp = suggestionPct(f.id)
                    val lastTiming = evaluated.firstOrNull()?.eval?.grade == MealGrade.TIMING
                    when {
                        pending != null && evaluated.isEmpty() -> {
                            Text(rh.gs(R.string.meal_pending), color = DashColors.Sub, fontSize = 11.sp)
                            Text("⏳ " + time.format(Date(pending.evalAt)), color = DashColors.Sub, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        lastTiming                             -> {
                            Text(MealTracker.gradeText(rh, MealGrade.TIMING), color = DashColors.Sub, fontSize = 11.sp)
                            Text(rh.gs(R.string.meal_split), color = DashColors.Low, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        sp == null                             -> {
                            Text(rh.gs(R.string.meal_not_enough), color = DashColors.Sub, fontSize = 11.sp)
                            Text("—", color = DashColors.Sub, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(rh.gs(R.string.meal_from_two, MealMath.MIN_RECORDS), color = DashColors.Dim, fontSize = 10.5.sp)
                        }

                        else                                   -> {
                            Text(rh.gs(R.string.meal_gpu, f1(sp.first.gramsPerUnit)), color = DashColors.Sub, fontSize = 11.sp)
                            if (abs(sp.second) < 5) Text(rh.gs(R.string.meal_grade_ok), color = DashColors.InRange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            else Text(MealTracker.signed(sp.second) + "%", color = if (sp.second > 0) DashColors.High else DashColors.Iob, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(rh.gs(R.string.meal_vs_ic, f1(icNow(m.first().time) ?: 0.0)), color = DashColors.Dim, fontSize = 10.5.sp)
                        }
                    }
                }
            }
        }
    }

    /** every AI carbs estimate, newest first; can be deleted one by one */
    @Composable
    private fun LegacyList() {
        val list = data.meals.filter { it.legacy }
            .filter { query.isBlank() || listOf(it.foodName, it.aiDescription, it.aiItems, it.legacyText).any { s -> s.contains(query, true) } }
            .sortedByDescending { it.time }
        if (list.isEmpty()) Text(rh.gs(R.string.meal_ai_empty), color = DashColors.Sub, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 20.dp))
        list.forEach { m ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val source = when {
                        m.aiHadImage && m.aiDescription.isNotBlank() -> "📷+✍ " + rh.gs(R.string.meal_ai_photo_text)
                        m.aiHadImage                                 -> "📷 " + rh.gs(R.string.meal_ai_photo)
                        else                                         -> "✍ " + rh.gs(R.string.meal_ai_text)
                    }
                    Text(
                        source, color = AiLilac, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AiPurple.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Text(
                        "  " + m.foodName.ifBlank { "—" }, color = DashColors.Text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(dayTime.format(Date(m.time)), color = DashColors.Sub, fontSize = 11.sp)
                }
                Text(
                    listOfNotNull(
                        rh.gs(R.string.meal_ai_carbs, m.carbs.toInt()),
                        m.appliedCarbs?.let { rh.gs(R.string.meal_ai_applied, it.toInt()) } ?: rh.gs(R.string.meal_ai_not_applied),
                        m.fat?.let { rh.gs(R.string.meal_fat, f1(it)) }, m.protein?.let { rh.gs(R.string.meal_protein, f1(it)) },
                        m.aiConfidence?.let {
                            rh.gs(
                                R.string.meal_ai_confidence, when (it.trim().lowercase()) {
                                    "high"   -> rh.gs(R.string.meal_conf_high)
                                    "medium" -> rh.gs(R.string.meal_conf_medium)
                                    "low"    -> rh.gs(R.string.meal_conf_low)
                                    else     -> it
                                }
                            )
                        }
                    ).joinToString(" · "), color = DashColors.Sub, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp)
                )
                if (m.aiItems.isNotBlank()) Text(m.aiItems, color = DashColors.Text, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                if (m.aiDescription.isNotBlank()) Text("✍ " + m.aiDescription, color = DashColors.Sub, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                if (m.legacyText.isNotBlank()) Text(m.legacyText, color = DashColors.Dim, fontSize = 11.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(
                    if (deleteAiId == m.id) rh.gs(R.string.meal_delete_confirm) else rh.gs(R.string.meal_ai_delete),
                    color = if (deleteAiId == m.id) DashColors.Low else DashColors.Dim, fontSize = 11.5.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (deleteAiId != m.id) deleteAiId = m.id
                            else {
                                mealTracker.update { d -> d.copy(meals = d.meals.filter { it.id != m.id }) }
                                deleteAiId = ""
                            }
                        }
                        .padding(top = 6.dp)
                )
            }
        }
    }

    @Composable
    private fun FoodScreen(f: MealFood) {
        val m = meals(f.id)
        Header(f.name, rh.gs(R.string.meal_rename_action), onBack = { back() }, onRight = { renameText = f.name; renaming = true })
        if (renaming)
            Card(border = DashColors.Accent.copy(alpha = 0.5f)) {
                Text(rh.gs(R.string.meal_name), color = DashColors.Sub, fontSize = 11.sp)
                BasicTextField(
                    renameText, { renameText = it }, singleLine = true,
                    textStyle = TextStyle(color = DashColors.Text, fontSize = 14.sp), cursorBrush = SolidColor(DashColors.Accent),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DashColors.Card2)
                        .border(1.dp, DashColors.Line, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(Modifier.weight(1f))
                    Text(rh.gs(app.aaps.core.ui.R.string.cancel), color = DashColors.Sub, fontSize = 13.sp, modifier = Modifier.clickable { renaming = false })
                    Text(
                        rh.gs(R.string.meal_memo_save), color = DashColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            val name = renameText.trim()
                            if (name.isNotEmpty())
                                mealTracker.update { d ->
                                    d.copy(
                                        foods = d.foods.map { if (it.id == f.id) it.copy(name = name) else it },
                                        meals = d.meals.map { if (it.foodId == f.id && !it.legacy) it.copy(foodName = name) else it }
                                    )
                                }
                            renaming = false
                        }
                    )
                }
            }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rh.gs(R.string.meal_food_summary, m.count { it.usable }, m.count { it.eval != null && !it.usable }, MealUi.typeLabel(rh, f.type)),
                    color = DashColors.Text, fontSize = 12.5.sp, modifier = Modifier.weight(1f)
                )
                Text(rh.gs(R.string.meal_track_short) + "  ", color = DashColors.Sub, fontSize = 11.5.sp)
                MealSwitch(f.tracked) { mealTracker.update { d -> d.copy(foods = d.foods.map { if (it.id == f.id) it.copy(tracked = !f.tracked) else it }) } }
            }
            val sp = suggestionPct(f.id)
            if (sp != null) {
                Text(
                    rh.gs(R.string.meal_food_suggest, f1(sp.first.gramsPerUnit), f1(icNow(m.first().time) ?: 0.0), MealTracker.signed(sp.second) + "%"),
                    color = DashColors.Text, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp)
                )
                Text(rh.gs(R.string.meal_food_suggest_note, (MealMath.CAP * 100).toInt()), color = DashColors.Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            } else Text(rh.gs(R.string.meal_suggest_need, MealMath.MIN_RECORDS, m.count { it.usable }), color = DashColors.Sub, fontSize = 11.5.sp, modifier = Modifier.padding(top = 8.dp))
            // evaluation time: change by hand or follow the advice from the last meals
            Text(rh.gs(R.string.meal_type_title), color = DashColors.Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            Row(
                Modifier
                    .padding(top = 4.dp)
                    .horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FoodType.entries.forEach { t -> MealChip(MealUi.typeLabel(rh, t), f.type == t) { setType(f, t) } }
            }
            MealMath.timeAdvice(m, f.type)?.let { t ->
                Text(
                    rh.gs(if (t.hours > f.type.hours) R.string.meal_time_longer else R.string.meal_time_shorter, MealUi.typeLabel(rh, t)),
                    color = DashColors.High, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable { setType(f, t) }
                )
            }
        }
        // AI: only the pattern over several meals, on demand
        val evaluated = m.filter { it.usable }
        Text(
            if (aiBusy) "✦ " + rh.gs(R.string.meal_ai_busy) else "✦ " + rh.gs(R.string.meal_ai_pattern),
            color = if (evaluated.size >= 2) AiPurple else DashColors.Dim, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .padding(bottom = 9.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AiPurple.copy(alpha = 0.08f))
                .border(1.dp, AiPurple.copy(alpha = if (evaluated.size >= 2) 0.6f else 0.2f), RoundedCornerShape(10.dp))
                .clickable(enabled = evaluated.size >= 2 && !aiBusy) { runAi(f, m) }
                .padding(vertical = 11.dp)
        )
        if (evaluated.size < 2 && aiText == null) Text(rh.gs(R.string.meal_ai_need), color = DashColors.Dim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
        aiText?.let {
            Text(
                "✦ AI · $it", color = DashColors.Sub, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier
                    .padding(bottom = 9.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AiPurple.copy(alpha = 0.08f))
                    .border(1.dp, AiPurple.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(11.dp)
            )
        }
        Text(rh.gs(R.string.meal_list_title), color = DashColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
        m.forEach { r ->
            val e = r.eval
            Card(border = if (e != null && !r.seen) DashColors.Accent.copy(alpha = 0.55f) else DashColors.Line, onClick = { openMeal(r) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GradeChip(rh, if (r.excludedByUser) MealGrade.EXCLUDED else e?.grade, pending = e == null, ask = e?.needsAnswer == true && e.grade != MealGrade.EXCLUDED)
                    Text("  " + dayTime.format(Date(r.time)), color = DashColors.Text, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                    Text(
                        if (e == null) rh.gs(R.string.meal_eval_at, time.format(Date(r.evalAt)))
                        else "${r.carbs.toInt()} g" + (if (e.neededInsulin > 0) " · " + MealTracker.signed(e.ratioPct) + "%" else "") + if (e.maxBg > 0) " · " + rh.gs(R.string.meal_max, e.maxBg) else "",
                        color = DashColors.Sub, fontSize = 11.5.sp
                    )
                }
                val memo = (r.memoTags + listOfNotNull(r.memo.takeIf { it.isNotBlank() })).joinToString(" · ")
                if (memo.isNotEmpty()) Text("📝 $memo", color = AiLilac, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        // remove a wrongly named food together with its records
        Text(
            if (deleteAsk) rh.gs(R.string.meal_delete_confirm) else rh.gs(R.string.meal_delete),
            color = if (deleteAsk) DashColors.Low else DashColors.Dim, fontSize = 12.sp,
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable {
                    if (!deleteAsk) deleteAsk = true
                    else {
                        mealTracker.update { d -> d.copy(foods = d.foods.filter { it.id != f.id }, meals = d.meals.filter { it.foodId != f.id }) }
                        deleteAsk = false
                        back()
                    }
                }
                .padding(8.dp)
        )
    }

    private fun setType(f: MealFood, t: FoodType) {
        // meals still waiting get the new evaluation time too
        mealTracker.update { d ->
            d.copy(
                foods = d.foods.map { if (it.id == f.id) it.copy(type = t) else it },
                meals = d.meals.map { if (it.foodId == f.id && it.eval == null && !it.legacy) it.copy(type = t, evalAt = it.time + t.hours * 3_600_000L) else it }
            )
        }
    }

    private fun runAi(f: MealFood, meals: List<MealRecord>) {
        aiBusy = true
        val text = buildString {
            append("음식: ${f.name} (${MealTracker.typeText(rh, f.type)}, 평가 ${f.type.hours}시간)\n")
            meals.filter { it.eval != null }.take(10).forEach { m ->
                val e = m.eval!!
                append("- ${dayTime.format(Date(m.time))}: 탄수 ${m.carbs.toInt()}g, 판정 ${MealTracker.gradeText(rh, if (m.excludedByUser) MealGrade.EXCLUDED else e.grade)}")
                if (e.grade == MealGrade.EXCLUDED || m.excludedByUser) append(" (${MealTracker.excludeText(rh, if (m.excludedByUser) ExcludeReason.USER else e.exclude)})")
                append(", 시작 ${e.startBg} → 최고 ${e.maxBg}(${e.maxAtMin}분) → 끝 ${e.endBg}, 최저 ${e.minBg}, 범위 ${e.tirPct}%")
                append(", 식사 볼러스 ${f2(e.mealBolus)}U, 루프 추가 ${f2(e.smb + e.basalExtra)}U, 직접 추가 ${f2(e.manualExtra)}U, 필요 대비 ${MealTracker.signed(e.ratioPct)}%")
                if (e.risingAtEnd) append(", 평가 끝에도 상승 중")
                // the curve every 30 min
                if (m.curve.isNotEmpty()) append(", 30분 간격 혈당 ${m.curve.filterIndexed { i, _ -> i % 6 == 0 }.joinToString("/")}")
                val memo = (m.memoTags + listOfNotNull(m.memo.takeIf { it.isNotBlank() })).joinToString(", ")
                if (memo.isNotEmpty()) append(", 메모: $memo")
                append("\n")
            }
        }
        disposable += aiTextEngine.generate(AI_SYSTEM_PROMPT, text)
            .observeOn(aapsSchedulers.main)
            .subscribe({ aiBusy = false; aiText = it.text.trim() }, { aiBusy = false; aiText = rh.gs(R.string.meal_ai_error, it.message ?: "") })
    }

    @Composable
    private fun MealScreen(m: MealRecord) {
        val e = m.eval
        Header(m.foodName + " · " + dayTime.format(Date(m.time)), if (e == null) rh.gs(R.string.meal_eval_at, time.format(Date(m.evalAt))) else rh.gs(R.string.meal_evaluated_at, time.format(Date(m.evalAt))), onBack = { back() })
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradeChip(rh, if (m.excludedByUser) MealGrade.EXCLUDED else e?.grade, pending = e == null, ask = e?.needsAnswer == true && e.grade != MealGrade.EXCLUDED)
                Text(
                    MealUi.typeLabel(rh, m.type), color = DashColors.Sub, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
            if (e == null) {
                Text(rh.gs(R.string.meal_wait_text, time.format(Date(m.evalAt))), color = DashColors.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                if (m.curve.any { it > 0 }) MealChart(m)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Kv(rh.gs(R.string.meal_kv_tir), "${e.tirPct} %", Modifier.weight(1f))
                    Kv(rh.gs(R.string.meal_kv_min), "${e.minBg}", Modifier.weight(1f), if (e.minBg in 1 until MealMath.LOW) DashColors.Low else DashColors.Text)
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Kv(rh.gs(R.string.meal_kv_bolus), "${f2(e.mealBolus)} U", Modifier.weight(1f))
                    Kv(rh.gs(R.string.meal_kv_loop), "+${f2(e.smb + e.basalExtra)} U", Modifier.weight(1f))
                }
                if (e.manualExtra > 0 || e.hypoCarbs > 0)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Kv(rh.gs(R.string.meal_kv_manual), "+${f2(e.manualExtra)} U", Modifier.weight(1f))
                        Kv(rh.gs(R.string.meal_kv_hypo), "${e.hypoCarbs.toInt()} g", Modifier.weight(1f))
                    }
                if (e.neededInsulin > 0)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Kv(rh.gs(R.string.meal_kv_needed), "${f2(e.neededInsulin)} U", Modifier.weight(1f))
                        Kv(rh.gs(R.string.meal_kv_ratio), MealTracker.signed(e.ratioPct) + " %", Modifier.weight(1f), if (abs(e.ratioPct) >= MealMath.RATIO_LIMIT) DashColors.High else DashColors.Text)
                    }
                Text(reasonText(m, e), color = DashColors.Sub, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        ManualCard(m)
        EntryCard(m)
        if (e != null && e.neededInsulin > 0) ProfileCompare(m, e)
        MemoCard(m)
    }

    /** boluses by hand during the window: for this meal (correction) or for other food */
    @Composable
    private fun ManualCard(m: MealRecord) {
        if (m.manualBoluses.isEmpty()) return
        Card(border = if (m.manualBoluses.any { it.answer.isEmpty() }) DashColors.High.copy(alpha = 0.6f) else DashColors.Line) {
            Text(rh.gs(R.string.meal_manual_title), color = DashColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(rh.gs(R.string.meal_manual_sub), color = DashColors.Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            m.manualBoluses.sortedBy { it.time }.forEach { b ->
                Text(
                    time.format(Date(b.time)) + " · " + f2(b.amount) + " U", color = DashColors.Text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MealChip(rh.gs(R.string.meal_ask_meal, m.foodName), b.answer == ManualBolus.MEAL) { answer(m, b, ManualBolus.MEAL) }
                    MealChip(rh.gs(R.string.meal_ask_other), b.answer == ManualBolus.OTHER, DashColors.High) { answer(m, b, ManualBolus.OTHER) }
                }
            }
        }
    }

    private fun answer(m: MealRecord, b: ManualBolus, answer: String) {
        disposable += io.reactivex.rxjava3.core.Completable.fromAction { mealTracker.answer(m.id, b.time, answer) }
            .subscribeOn(aapsSchedulers.io).observeOn(aapsSchedulers.main).subscribe({ data = mealTracker.data() }, {})
    }

    /** BG, IOB, COB and the carbs entered with the meal, and what the calculator gave */
    @Composable
    private fun EntryCard(m: MealRecord) {
        if (m.entryBg == null && m.entryIob == null) return
        Card {
            Text(rh.gs(R.string.meal_entry_title), color = DashColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Kv(rh.gs(R.string.meal_entry_bg), m.entryBg?.toString() ?: "—", Modifier.weight(1f))
                Kv(rh.gs(R.string.meal_entry_delta), m.entryDelta15?.let { MealTracker.signed(it) } ?: "—", Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Kv("IOB", m.entryIob?.let { "${f2(it)} U" } ?: "—", Modifier.weight(1f))
                Kv("COB", m.entryCob?.let { "${it.toInt()} g" } ?: "—", Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Kv(rh.gs(R.string.meal_entry_carbs), "${m.carbs.toInt()} g", Modifier.weight(1f))
                Kv(rh.gs(R.string.meal_entry_bolus), "${f2(m.bolus)} U", Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Kv(
                    rh.gs(R.string.meal_entry_fat_protein),
                    if (m.fat == null && m.protein == null) "—" else "${m.fat?.let { f1(it) } ?: "?"} / ${m.protein?.let { f1(it) } ?: "?"} g", Modifier.weight(1f)
                )
                Kv(rh.gs(R.string.meal_entry_history), if (m.historyAdjust != 0.0) (if (m.historyAdjust > 0) "+" else "") + f2(m.historyAdjust) + " U" else "—", Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun Kv(label: String, value: String, modifier: Modifier, color: Color = DashColors.Text) {
        Column(
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(DashColors.Card2)
                .padding(horizontal = 9.dp, vertical = 6.dp)
        ) {
            Text(label, color = DashColors.Sub, fontSize = 10.5.sp, maxLines = 1)
            Text(value, color = color, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
        }
    }

    private fun reasonText(m: MealRecord, e: MealEval): String {
        if (m.excludedByUser) return rh.gs(R.string.meal_ex_user)
        if (e.grade == MealGrade.EXCLUDED) return MealTracker.excludeText(rh, e.exclude)
        val parts = mutableListOf(
            rh.gs(R.string.meal_reason_main, e.tirPct, e.maxBg, f2(e.smb + e.basalExtra + e.manualExtra), MealTracker.signed(e.endBg - e.startBg), m.carbs.toInt(), f2(e.neededInsulin), f1(m.carbs / e.neededInsulin))
        )
        if (e.hypoCarbs > 0) parts += rh.gs(R.string.meal_reason_hypo_carbs, e.hypoCarbs.toInt())
        parts += e.startDeviation?.let { rh.gs(R.string.meal_reason_reliability, MealTracker.reliabilityText(rh, e.reliability), (if (it > 0) "+" else "") + f1(it)) }
            ?: MealTracker.reliabilityText(rh, e.reliability) + "."
        if (e.cobBefore >= 1) parts += rh.gs(R.string.meal_reason_cob_before, e.cobBefore.toInt())
        if (e.cobAfter >= 1) parts += rh.gs(R.string.meal_reason_cob_after, e.cobAfter.toInt())
        parts += if (e.minBg in 1 until MealMath.LOW) rh.gs(R.string.meal_reason_low, e.minBg) else rh.gs(R.string.meal_reason_no_low)
        if (e.grade == MealGrade.TIMING) parts += rh.gs(R.string.meal_reason_timing)
        if (e.risingAtEnd) parts += rh.gs(R.string.meal_reason_rising)
        return parts.joinToString(" ")
    }

    @Composable
    private fun ProfileCompare(m: MealRecord, e: MealEval) {
        val sec = MidnightUtils.secondsFromMidnight(m.time)
        val now = profileFunction.getProfile()
        val ic = now?.getIcTimeFromMidnight(sec) ?: return
        val isf = now.getIsfMgdlTimeFromMidnight(sec)
        Card {
            Text(rh.gs(R.string.meal_profile_title), color = DashColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 6.dp)) {
                Text("", modifier = Modifier.weight(1.3f))
                Text(day.format(Date(m.time)), color = DashColors.Dim, fontSize = 10.5.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text(rh.gs(R.string.meal_now), color = DashColors.Dim, fontSize = 10.5.sp, modifier = Modifier.weight(1.3f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            fun pct(old: Double, new: Double) = if (old > 0 && abs(new / old - 1) >= 0.005) "  " + MealTracker.signed(round((new / old - 1) * 100).toInt()) + "%" else ""
            CompareRow("IC (${time.format(Date(m.time))})", f1(m.ic), f1(ic) + pct(m.ic, ic))
            CompareRow("ISF", f1(m.isfMgdl), f1(isf) + pct(m.isfMgdl, isf))
            CompareRow(rh.gs(R.string.meal_profile_pct), "${m.percentage}", "${now.percentage}")
            val alone = e.neededInsulin - m.carbs / ic
            Text(rh.gs(R.string.meal_profile_alone, m.carbs.toInt(), (if (alone > 0) "+" else "") + f2(alone)), color = DashColors.Sub, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }

    @Composable
    private fun CompareRow(label: String, old: String, new: String) {
        Row(Modifier.padding(top = 4.dp)) {
            Text(label, color = DashColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1.3f))
            Text(old, color = DashColors.Sub, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Text(new, color = DashColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1.3f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }

    @Composable
    private fun MemoCard(m: MealRecord) {
        val changed = memoTags != m.memoTags || memoText != m.memo || memoExclude != m.excludedByUser
        Card {
            Text(rh.gs(R.string.meal_memo_title), color = DashColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(rh.gs(R.string.meal_memo_tags), color = DashColors.Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            MealUi.TAGS.chunked(4).forEach { row ->
                Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { id ->
                        val tag = rh.gs(id)
                        val warn = id in MealUi.WARN_TAGS
                        MealChip(tag, tag in memoTags, if (warn) DashColors.High else DashColors.Accent) {
                            memoTags = if (tag in memoTags) memoTags - tag else memoTags + tag
                        }
                    }
                }
            }
            Text(rh.gs(R.string.meal_memo_text), color = DashColors.Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            BasicTextField(
                memoText, { memoText = it },
                textStyle = TextStyle(color = DashColors.Text, fontSize = 13.sp), cursorBrush = SolidColor(DashColors.Accent),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DashColors.Card2)
                    .border(1.dp, DashColors.Line, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            )
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rh.gs(R.string.meal_memo_exclude), color = DashColors.Text, fontSize = 12.5.sp)
                    Text(rh.gs(R.string.meal_memo_exclude_sub), color = DashColors.Dim, fontSize = 10.5.sp)
                }
                MealSwitch(memoExclude) { memoExclude = !memoExclude }
            }
            // a ⚠ tag usually means the meal says little about the food itself
            val warnTag = MealUi.WARN_TAGS.map { rh.gs(it) }.any { it in memoTags }
            if (warnTag && !memoExclude)
                Text(
                    rh.gs(R.string.meal_memo_warn), color = DashColors.High, fontSize = 11.5.sp,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { memoExclude = true }
                )
            Text(
                rh.gs(R.string.meal_memo_save), color = if (changed) DashColors.Bg else DashColors.Dim, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (changed) DashColors.Accent else DashColors.Card2)
                    .clickable(enabled = changed) {
                        mealTracker.update { d ->
                            d.copy(meals = d.meals.map { if (it.id == m.id) it.copy(memoTags = memoTags, memo = memoText.trim(), excludedByUser = memoExclude, seen = true) else it })
                        }
                    }
                    .padding(vertical = 11.dp)
            )
        }
    }

    @Composable
    private fun MealChart(m: MealRecord) {
        val curve = m.curve
        val known = curve.filter { it > 0 }
        val top = maxOf(known.maxOrNull() ?: 200, 200) + 20
        val bottom = minOf(known.minOrNull() ?: 60, 60) - 10
        val max = known.maxOrNull() ?: 0
        Canvas(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(150.dp)
        ) {
            val w = size.width
            val h = size.height - 18.dp.toPx()
            val n = (curve.size - 1).coerceAtLeast(1)
            fun x(i: Float) = i / n * w
            fun y(v: Int) = h - (v - bottom).toFloat() / (top - bottom) * h
            drawRect(DashColors.InRange.copy(alpha = 0.07f), Offset(0f, y(MealMath.HIGH)), Size(w, y(MealMath.LOW) - y(MealMath.HIGH)))
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            drawLine(DashColors.InRange.copy(alpha = 0.35f), Offset(0f, y(MealMath.HIGH)), Offset(w, y(MealMath.HIGH)), 1.dp.toPx(), pathEffect = dash)
            drawLine(DashColors.InRange.copy(alpha = 0.35f), Offset(0f, y(MealMath.LOW)), Offset(w, y(MealMath.LOW)), 1.dp.toPx(), pathEffect = dash)
            // meal line
            drawLine(DashColors.Accent, Offset(1f, 0f), Offset(1f, size.height), 1.5.dp.toPx())
            val path = Path()
            var started = false
            curve.forEachIndexed { i, v ->
                if (v <= 0) {
                    started = false; return@forEachIndexed
                }
                if (!started) path.moveTo(x(i.toFloat()), y(v)) else path.lineTo(x(i.toFloat()), y(v))
                started = true
            }
            drawPath(path, DashColors.Text, style = Stroke(2.dp.toPx()))
            val mi = curve.indexOf(max)
            if (mi >= 0 && max > 0) drawCircle(DashColors.High, 4.dp.toPx(), Offset(x(mi.toFloat()), y(max)))
            curve.forEachIndexed { i, v -> if (v in 1 until MealMath.LOW) drawCircle(DashColors.Low, 3.dp.toPx(), Offset(x(i.toFloat()), y(v))) }
            // insulin / carbs below the curve
            m.events.forEach { ev ->
                val px = x(ev.min / MealMath.STEP_MIN.toFloat()).coerceIn(0f, w - 3f)
                val (c, len) = when (ev.kind) {
                    MealEvent.SMB        -> DashColors.Iob to (ev.amount * 20).toFloat().coerceIn(3f, 14f)
                    MealEvent.BOLUS      -> DashColors.Basal to 14f
                    MealEvent.MEAL_BOLUS -> DashColors.Accent to 14f
                    else                 -> DashColors.Cob to 14f
                }
                drawRect(c, Offset(px, size.height - len.dp.toPx()), Size(3.dp.toPx(), len.dp.toPx()))
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Legend(DashColors.Accent, rh.gs(R.string.meal_legend_meal, m.carbs.toInt(), f2(m.eval?.mealBolus ?: 0.0)))
            Legend(DashColors.Iob, "SMB " + f2(m.eval?.smb ?: 0.0) + " U")
            if (max > 0) Legend(DashColors.High, rh.gs(R.string.meal_max, max) + " (" + rh.gs(R.string.meal_after_min, m.eval?.maxAtMin ?: 0) + ")")
        }
    }

    @Composable
    private fun Legend(c: Color, text: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(c)
            )
            Spacer(Modifier.width(4.dp))
            Text(text, color = DashColors.Sub, fontSize = 10.5.sp, maxLines = 1)
        }
    }

    companion object {

        private val AiPurple = Color(0xFFA78BFA)
        private val AiLilac = Color(0xFFC4B5FD)

        private const val AI_SYSTEM_PROMPT = """너는 1형 당뇨인의 식사 기록을 보는 도우미야. 한 음식에 대한 여러 번의 식후 평가(혈당 흐름, 인슐린, 메모)가 주어진다.
판정과 숫자는 이미 앱이 계산해서 보여주고 있으니 숫자를 다시 나열하지 말고, 여러 번의 기록에서 보이는 흐름과 패턴만 한국어로 3~4문장으로 짧게 정리해.
예: 최고점이 늘 늦게 오는지, 초반에 떨어졌다가 나중에 오르는지, 메모(양, 운동 등)와 결과가 관련 있어 보이는지.
인슐린 용량이나 설정 값을 지시하지 말고, 볼러스 시점이나 분할 입력 같은 일반적인 고려 사항만 조심스럽게 언급해. 인사말이나 서론 없이 바로 써."""
    }
}
