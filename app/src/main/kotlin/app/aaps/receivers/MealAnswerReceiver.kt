package app.aaps.receivers

import android.content.Context
import android.content.Intent
import app.aaps.core.objects.workflow.MetroBroadcastReceiver
import app.aaps.plugins.main.general.dashboard.meal.MealTracker
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.runBlocking

/**
 * Personal-fork: answer buttons of the "what was this bolus?" notification during a meal evaluation.
 * In :app like the other receivers, because member injection is generated here.
 */
class MealAnswerReceiver : MetroBroadcastReceiver() {

    @Inject lateinit var mealTracker: MealTracker

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val mealId = intent.getStringExtra(MealTracker.EXTRA_MEAL) ?: return
        val time = intent.getLongExtra(MealTracker.EXTRA_TIME, 0L)
        val answer = intent.getStringExtra(MealTracker.EXTRA_ANSWER) ?: return
        val pending = goAsync()
        Thread {
            try {
                runBlocking { mealTracker.answer(mealId, time, answer) }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
