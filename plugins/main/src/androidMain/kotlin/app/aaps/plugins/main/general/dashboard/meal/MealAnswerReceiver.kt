package app.aaps.plugins.main.general.dashboard.meal

import android.content.Context
import android.content.Intent
import app.aaps.core.objects.workflow.MetroBroadcastReceiver
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.runBlocking

/** Personal-fork: answer buttons of the "what was this bolus?" notification during a meal evaluation. */
class MealAnswerReceiver : MetroBroadcastReceiver() {

    @Inject lateinit var mealTracker: MealTracker

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val mealId = intent.getStringExtra(EXTRA_MEAL) ?: return
        val time = intent.getLongExtra(EXTRA_TIME, 0L)
        val answer = intent.getStringExtra(EXTRA_ANSWER) ?: return
        val pending = goAsync()
        Thread {
            try {
                runBlocking { mealTracker.answer(mealId, time, answer) }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {

        const val EXTRA_MEAL = "meal"
        const val EXTRA_TIME = "time"
        const val EXTRA_ANSWER = "answer"
    }
}
