package app.aaps.ui.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken

/**
 * Personal-fork: minimal append-and-trim history of successful AI carb estimates.
 *
 * Stored as a single JSON array string under the [PREFS_NAME] / [KEY_ENTRIES] preference,
 * trimmed to [MAX_ENTRIES] newest-first. No background thread / DAO / migrations on
 * purpose — the dataset is tiny (~50 entries × ~600 bytes ≈ 30 KB worst case) and only
 * needs to survive app upgrades, which SharedPreferences already does.
 *
 * Writes happen on the AiCarbsDialog apply path; reads happen when the user opens the
 * history viewer. Errors are swallowed and treated as an empty history rather than
 * crashing the dialog flow.
 */
data class AiCarbsHistoryEntry(
    val timestampMs: Long,
    val foodDescription: String? = null,
    val hadImage: Boolean = false,
    val totalCarbsG: Double = 0.0,
    val fatG: Double? = null,
    val proteinG: Double? = null,
    val fpuCarbG: Double? = null,
    val durationH: Int? = null,
    val confidence: String? = null,
    val inputStrategy: String? = null,
    val appliedCarbsG: Int? = null
)

object AiCarbsHistoryStore {

    private const val PREFS_NAME = "ai_carbs_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 50

    private val gson = Gson()
    private val listType = object : TypeToken<List<AiCarbsHistoryEntry>>() {}.type

    fun read(context: Context): List<AiCarbsHistoryEntry> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = sp.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            gson.fromJson<List<AiCarbsHistoryEntry>>(json, listType) ?: emptyList()
        } catch (_: JsonSyntaxException) {
            emptyList()
        }
    }

    fun append(context: Context, entry: AiCarbsHistoryEntry) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = read(context).toMutableList()
        current.add(0, entry) // newest first
        while (current.size > MAX_ENTRIES) current.removeAt(current.size - 1)
        sp.edit().putString(KEY_ENTRIES, gson.toJson(current, listType)).apply()
    }
}
