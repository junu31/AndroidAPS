package app.aaps.plugins.main.general.dashboard.meal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.utils.MidnightUtils
import app.aaps.plugins.main.R
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Personal-fork: registers meals from the Dashboard calculator, evaluates them N hours later
 * (on every new BG, so no extra scheduler) and posts a notification with the result.
 */
@Singleton
class MealTracker @Inject constructor(
    private val context: Context,
    private val store: MealStore,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val iobCobCalculator: IobCobCalculator,
    private val rxBus: RxBus,
    private val aapsSchedulers: AapsSchedulers,
    private val dateUtil: DateUtil,
    private val rh: ResourceHelper,
    private val uiInteraction: UiInteraction,
    private val aapsLogger: AAPSLogger
) {

    private val disposable = CompositeDisposable()

    /** emits whenever the stored data changes (dashboard badge, open screens) */
    val changes: BehaviorSubject<MealData> = BehaviorSubject.create()

    fun start() {
        disposable.clear()
        disposable += Observable.merge(rxBus.toObservable(EventNewBG::class.java).map { }, Observable.timer(1, TimeUnit.MINUTES).map { })
            .throttleLatest(1, TimeUnit.MINUTES)
            .observeOn(aapsSchedulers.io)
            .subscribe({ checkDue() }, { aapsLogger.error(LTag.CORE, "Meal tracking", it) })
    }

    fun stop() = disposable.clear()

    fun data(): MealData = store.data()

    fun update(change: (MealData) -> MealData) {
        changes.onNext(store.update(change))
    }

    /** foods by last meal, newest first */
    fun recentFoods(): List<MealFood> {
        val data = data()
        val last = data.meals.filter { !it.legacy }.groupBy { it.foodId }.mapValues { e -> e.value.maxOf { it.time } }
        return data.foods.sortedByDescending { last[it.id] ?: it.created }
    }

    fun mealsOf(foodId: String): List<MealRecord> = data().meals.filter { it.foodId == foodId && !it.legacy }.sortedByDescending { it.time }

    /** Called right before the wizard confirmation; kept only if the bolus is really given (see [confirmPending]). */
    fun register(
        food: MealFood, tracked: Boolean, carbs: Double, fat: Double?, protein: Double?,
        carbTimeMin: Int, bolus: Double, historyAdjust: Double
    ) {
        val now = dateUtil.now()
        val time = now + T.mins(carbTimeMin.toLong()).msecs()
        val profile = profileFunction.getProfile(time) ?: profileFunction.getProfile()
        val sec = MidnightUtils.secondsFromMidnight(time)
        // state at entry: last BG and its change over 15 min, IOB (bolus + basal), COB
        val last = persistenceLayer.getLastGlucoseValue()?.takeIf { now - it.timestamp < T.mins(15).msecs() }
        val before = last?.let { l ->
            persistenceLayer.getBgReadingsDataFromTimeToTime(l.timestamp - T.mins(20).msecs(), l.timestamp - T.mins(10).msecs(), true)
                .minByOrNull { abs(it.timestamp - (l.timestamp - T.mins(15).msecs())) }
        }
        val iob = iobCobCalculator.calculateIobFromBolus().iob + iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().basaliob
        val record = MealRecord(
            id = MealStore.newId(), foodId = food.id, foodName = food.name, time = time, deliverTime = now, carbs = carbs, fat = fat, protein = protein,
            type = food.type, tracked = tracked, bolus = bolus, historyAdjust = historyAdjust,
            profileName = profileFunction.getProfileName(), ic = profile?.getIcTimeFromMidnight(sec) ?: 0.0,
            isfMgdl = profile?.getIsfMgdlTimeFromMidnight(sec) ?: 0.0, percentage = profile?.percentage ?: 100,
            evalAt = time + T.hours(food.type.hours.toLong()).msecs(),
            entryBg = last?.value?.roundToInt(), entryDelta15 = if (last != null && before != null) (last.value - before.value).roundToInt() else null,
            entryIob = (round(iob * 100) / 100), entryCob = iobCobCalculator.getCobInfo("Meal tracking").displayCob
        )
        update { it.copy(meals = it.meals + record) }
    }

    @Synchronized
    fun checkDue() {
        confirmPending()
        val now = dateUtil.now()
        data().meals.filter { it.confirmed && it.tracked && !it.legacy && it.eval == null && it.evalAt <= now }.forEach { meal ->
            val evaluated = try {
                evaluate(meal)
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Meal tracking: evaluation failed", e)
                null
            } ?: return@forEach
            update { d -> d.copy(meals = d.meals.map { if (it.id == meal.id) evaluated else it }) }
            notify(evaluated)
        }
    }

    /**
     * The wizard stores a bolus calculator result when the user confirms; when insulin was planned the meal bolus
     * must also be delivered (a suspended loop / LGS or a pump error stores no bolus). Nothing within 20 min = cancelled.
     */
    private fun confirmPending() {
        val now = dateUtil.now()
        val pending = data().meals.filter { !it.confirmed && !it.legacy }
        if (pending.isEmpty()) return
        val results = persistenceLayer.getBolusCalculatorResultsFromTime(pending.minOf { it.deliverTime } - T.mins(1).msecs(), true).blockingGet()
        pending.forEach { meal ->
            val window = meal.deliverTime - T.mins(1).msecs()..meal.deliverTime + T.mins(20).msecs()
            val calc = results.any { it.isValid && it.timestamp in window && abs(it.carbs - meal.carbs) < 0.5 }
            val delivered = persistenceLayer.getBolusesFromTimeToTime(window.first, window.last, true)
                .filter { it.isValid && it.type == BS.Type.NORMAL && it.amount > 0 }.sumOf { it.amount }
            val found = calc && (meal.bolus <= 0.0 || delivered > 0)
            when {
                // a new food is stored only once its first meal is confirmed (a cancelled confirmation leaves nothing)
                found                                                -> update { d ->
                    val foods = if (d.foods.any { it.id == meal.foodId })
                        d.foods.map { if (it.id == meal.foodId) it.copy(name = meal.foodName, tracked = meal.tracked, type = meal.type) else it }
                    else d.foods + MealFood(meal.foodId, meal.foodName, meal.type, meal.tracked, meal.deliverTime)
                    // an untracked meal only keeps the food in the recent list
                    val meals = if (meal.tracked) d.meals.map { if (it.id == meal.id) it.copy(confirmed = true, bolus = if (delivered > 0) delivered else it.bolus) else it }
                    else d.meals.filter { it.id != meal.id }
                    d.copy(foods = foods, meals = meals)
                }

                now - meal.deliverTime > T.mins(20).msecs()           -> update { d -> d.copy(meals = d.meals.filter { it.id != meal.id }) }
            }
        }
    }

    /** Re-run the evaluation (e.g. after the user changed the exclusion). Returns the new record. */
    fun evaluate(meal: MealRecord): MealRecord? {
        val start = meal.time
        val end = meal.time + T.hours(meal.type.hours.toLong()).msecs()
        val step = T.mins(MealMath.STEP_MIN.toLong()).msecs()
        val atMeal = profileFunction.getProfile(start) ?: return null
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(start - T.mins(10).msecs(), end + T.mins(5).msecs(), true)
        val times = (0..((end - start) / step).toInt()).map { start + it * step }
        val bg = times.map { t ->
            readings.filter { abs(it.timestamp - t) <= T.mins(3).msecs() }.minByOrNull { abs(it.timestamp - t) }?.value?.roundToInt()
        }
        var basalExtra = 0.0
        val activity = times.map { t ->
            val profile: Profile = profileFunction.getProfile(t) ?: atMeal
            iobCobCalculator.getBasalData(profile, t).let { if (it.isTempBasalRunning) basalExtra += (it.tempBasalAbsolute - it.basal) * MealMath.STEP_MIN / 60.0 }
            iobCobCalculator.calculateFromTreatmentsAndTemps(t, profile).activity
        }
        val boluses = persistenceLayer.getBolusesFromTimeToTime(meal.deliverTime - T.mins(1).msecs(), end, true)
            .filter { it.isValid && it.type != BS.Type.PRIMING }
        val mealWindow = meal.deliverTime - T.mins(1).msecs()..meal.deliverTime + T.mins(15).msecs()
        val mealBoluses = boluses.filter { it.type == BS.Type.NORMAL && it.timestamp in mealWindow }
        val smbs = boluses.filter { it.type == BS.Type.SMB }
        val manual = boluses.filter { it.type == BS.Type.NORMAL && it.timestamp !in mealWindow }
        fun minOf(ts: Long) = ((ts - start) / 60000L).toInt()
        // the meal's own carbs entry is the first one with the same amount close to the carbs time
        val carbs = persistenceLayer.getCarbsFromTimeToTimeExpanded(start - T.mins(15).msecs(), end, true).filter { it.isValid && it.amount > 0 }
        val own = carbs.filter { abs(it.timestamp - start) <= T.mins(15).msecs() && abs(it.amount - meal.carbs) < 0.5 }.minByOrNull { abs(it.timestamp - start) }
        val other = carbs.filter { it !== own && it.timestamp >= start }.map { c ->
            val near = readings.filter { abs(it.timestamp - c.timestamp) <= T.mins(15).msecs() }.minOfOrNull { it.value }
            MealMath.OtherCarbs(minOf(c.timestamp), c.amount, near?.roundToInt())
        }
        val activityTarget = persistenceLayer.getTemporaryTargetDataFromTime(start - T.hours(2).msecs(), true).blockingGet()
            .any { it.isValid && it.reason == TT.Reason.ACTIVITY && it.timestamp < end && it.timestamp + it.duration > start }
        val profileSwitch = persistenceLayer.getEffectiveProfileSwitchesFromTimeToTime(start + 1, end, true).isNotEmpty()
        // COB from the autosens data: before the meal (earlier food) and at the end (not absorbed yet)
        val cobBefore = iobCobCalculator.ads.getAutosensDataAtTime(start - T.mins(5).msecs())?.cob ?: 0.0
        val cobAfter = iobCobCalculator.ads.getAutosensDataAtTime(end)?.cob ?: 0.0
        // deviations in the 30 min before the meal: how calm the start was
        val startDeviations = (1..6).mapNotNull { k ->
            iobCobCalculator.ads.getAutosensDataAtTime(start - k * step)?.takeIf { it.validDeviation }?.deviation
        }
        val eval = MealMath.evaluate(
            MealMath.EvalInput(
                carbs = meal.carbs, ic = meal.ic.takeIf { it > 0 } ?: atMeal.getIc(start), isfMgdl = meal.isfMgdl.takeIf { it > 0 } ?: atMeal.getIsfMgdl("Meal"),
                bg = bg, activity = activity, mealBolus = mealBoluses.sumOf { it.amount }, smb = smbs.sumOf { it.amount }, basalExtra = basalExtra,
                manualExtra = manual.sumOf { it.amount }, otherCarbs = other, cobBefore = cobBefore, cobAfter = cobAfter, startDeviations = startDeviations,
                notDelivered = meal.bolus > 0 && mealBoluses.isEmpty(), activityTarget = activityTarget, profileSwitch = profileSwitch
            )
        )
        val events = mealBoluses.map { MealEvent(minOf(it.timestamp), MealEvent.MEAL_BOLUS, it.amount) } +
            smbs.map { MealEvent(minOf(it.timestamp), MealEvent.SMB, it.amount) } +
            manual.map { MealEvent(minOf(it.timestamp), MealEvent.BOLUS, it.amount) } +
            other.map { MealEvent(it.min, MealEvent.CARBS, it.grams) }
        return meal.copy(eval = eval, curve = bg.map { it ?: 0 }, events = events, seen = false)
    }

    private fun notify(meal: MealRecord) {
        val e = meal.eval ?: return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, rh.gs(R.string.meal_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val title = if (e.grade == MealGrade.EXCLUDED) rh.gs(R.string.meal_notif_excluded, meal.foodName)
        else rh.gs(R.string.meal_notif_done, meal.foodName, gradeText(rh, e.grade))
        val text = if (e.grade == MealGrade.EXCLUDED) excludeText(rh, e.exclude)
        else rh.gs(R.string.meal_notif_text, e.tirPct, e.maxBg, e.smb + e.basalExtra + e.manualExtra, e.neededInsulin, signed(e.ratioPct))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, uiInteraction.mainActivity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(app.aaps.core.ui.R.drawable.notif_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFICATION_BASE + (meal.id.hashCode() and 0xFFFF), n)
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.CORE, "Meal tracking: notification not allowed", e)
        }
    }

    /** current profile IC at the given time */
    fun currentIc(time: Long = dateUtil.now()): Double? = profileFunction.getProfile()?.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight(time))

    companion object {

        private const val CHANNEL_ID = "AAPS-MealTracking"
        private const val NOTIFICATION_BASE = 0x4D000

        fun signed(v: Int) = if (v > 0) "+$v" else "$v"

        fun gradeText(rh: ResourceHelper, g: MealGrade): String = rh.gs(
            when (g) {
                MealGrade.OK       -> R.string.meal_grade_ok
                MealGrade.UNDER    -> R.string.meal_grade_under
                MealGrade.OVER     -> R.string.meal_grade_over
                MealGrade.TIMING   -> R.string.meal_grade_timing
                MealGrade.EXCLUDED -> R.string.meal_grade_excluded
            }
        )

        fun excludeText(rh: ResourceHelper, r: ExcludeReason): String = rh.gs(
            when (r) {
                ExcludeReason.OTHER_MEAL     -> R.string.meal_ex_other_meal
                ExcludeReason.SENSOR_GAP     -> R.string.meal_ex_sensor
                ExcludeReason.ACTIVITY       -> R.string.meal_ex_activity
                ExcludeReason.PROFILE_SWITCH -> R.string.meal_ex_profile
                ExcludeReason.NO_INSULIN     -> R.string.meal_ex_no_insulin
                ExcludeReason.NOT_DELIVERED  -> R.string.meal_ex_not_delivered
                ExcludeReason.USER, ExcludeReason.NONE -> R.string.meal_ex_user
            }
        )

        fun reliabilityText(rh: ResourceHelper, r: Reliability): String = rh.gs(
            when (r) {
                Reliability.HIGH    -> R.string.meal_rel_high
                Reliability.MEDIUM  -> R.string.meal_rel_medium
                Reliability.LOW     -> R.string.meal_rel_low
                Reliability.UNKNOWN -> R.string.meal_rel_unknown
            }
        )

        fun typeText(rh: ResourceHelper, t: FoodType): String = rh.gs(
            when (t) {
                FoodType.FAST        -> R.string.meal_type_fast
                FoodType.MIXED       -> R.string.meal_type_mixed
                FoodType.FAT         -> R.string.meal_type_fat
                FoodType.FAT_PROTEIN -> R.string.meal_type_fat_protein
            }
        )
    }
}
