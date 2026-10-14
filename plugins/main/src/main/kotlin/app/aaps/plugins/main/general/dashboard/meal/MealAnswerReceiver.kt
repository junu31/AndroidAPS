package app.aaps.plugins.main.general.dashboard.meal

import android.content.Context
import android.content.Intent
import dagger.android.DaggerBroadcastReceiver
import javax.inject.Inject

/** Personal-fork: answer buttons of the "what was this bolus?" notification during a meal evaluation. */
class MealAnswerReceiver : DaggerBroadcastReceiver() {

    @Inject lateinit var mealTracker: MealTracker

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val mealId = intent.getStringExtra(EXTRA_MEAL) ?: return
        val time = intent.getLongExtra(EXTRA_TIME, 0L)
        val answer = intent.getStringExtra(EXTRA_ANSWER) ?: return
        val pending = goAsync()
        Thread {
            try {
                mealTracker.answer(mealId, time, answer)
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
