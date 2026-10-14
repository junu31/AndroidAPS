package app.aaps.plugins.main.general.dashboard.meal

import android.content.Context
import android.util.Base64
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.meal.AiEstimateLog
import app.aaps.core.interfaces.meal.MealBackup
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal-fork: meal tracking storage. One JSON file in the app storage (not the AAPS database, so no migrations);
 * it is packed into the settings export through [MealBackup].
 */
@Singleton
class MealStore @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) : MealBackup, AiEstimateLog {

    private val gson = Gson()
    private val file get() = File(context.filesDir, FILE_NAME)
    private var cache: MealData? = null

    @Synchronized
    fun data(): MealData {
        cache?.let { return it }
        val loaded = try {
            if (file.exists()) gson.fromJson(file.readText(), MealData::class.java) ?: MealData() else MealData()
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Meal tracking: cannot read $FILE_NAME", e)
            MealData()
        }
        // the first import kept too little (no image / text, applied carbs): replace it once
        val data = if (loaded.aiHistoryV2) loaded else importAiHistory(loaded.copy(meals = loaded.meals.filter { !it.legacy }))
        cache = data
        if (data !== loaded) write(data)
        return data
    }

    @Synchronized
    fun update(change: (MealData) -> MealData): MealData {
        val next = change(data())
        cache = next
        write(next)
        return next
    }

    private fun write(data: MealData) {
        try {
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(gson.toJson(data))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Meal tracking: cannot write $FILE_NAME", e)
        }
    }

    /** The old AI carbs history (ui module, SharedPreferences) is copied once as "before tracking" records. */
    private fun importAiHistory(data: MealData): MealData {
        val legacy = try {
            val json = context.getSharedPreferences("ai_carbs_history", Context.MODE_PRIVATE).getString("entries", null)
            if (json == null) emptyList()
            else JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
                val o = el.asJsonObject
                fun d(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asDouble
                val time = o.get("timestampMs")?.asLong ?: return@mapNotNull null
                val text = o.get("foodDescription")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                MealRecord(
                    id = newId(), foodName = text.lineSequence().firstOrNull().orEmpty().take(40),
                    time = time, deliverTime = time, carbs = d("totalCarbsG") ?: 0.0, appliedCarbs = d("appliedCarbsG"),
                    fat = d("fatG"), protein = d("proteinG"), confirmed = true, tracked = false, seen = true,
                    legacy = true, legacyText = o.get("inputStrategy")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                    aiDescription = text, aiHadImage = o.get("hadImage")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    aiConfidence = o.get("confidence")?.takeIf { !it.isJsonNull }?.asString
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Meal tracking: cannot import the AI carbs history", e)
            emptyList()
        }
        return data.copy(meals = data.meals + legacy, aiHistoryImported = true, aiHistoryV2 = true)
    }

    override fun exportBackup(): String? {
        val data = data()
        if (data.foods.isEmpty() && data.meals.isEmpty()) return null
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(gson.toJson(data).toByteArray(Charsets.UTF_8)) }
        return BACKUP_PREFIX + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @Synchronized
    override fun importBackup(data: String) {
        try {
            if (!data.startsWith(BACKUP_PREFIX)) return
            val bytes = Base64.decode(data.removePrefix(BACKUP_PREFIX), Base64.NO_WRAP)
            val json = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
            val restored = gson.fromJson(json, MealData::class.java) ?: return
            cache = restored.copy(aiHistoryImported = true, aiHistoryV2 = true)
            write(cache!!)
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Meal tracking: cannot import the backup", e)
        }
    }

    override fun addAiEstimate(
        name: String, description: String?, hadImage: Boolean, items: List<Pair<String, Double>>,
        totalCarbs: Double, fat: Double?, protein: Double?, confidence: String?, note: String?
    ): String {
        val now = System.currentTimeMillis()
        val record = MealRecord(
            id = newId(), foodName = name.ifBlank { description.orEmpty().lineSequence().firstOrNull().orEmpty().take(40) },
            time = now, deliverTime = now, carbs = totalCarbs, fat = fat, protein = protein, confirmed = true, tracked = false, seen = true,
            legacy = true, legacyText = note.orEmpty(), aiDescription = description.orEmpty(), aiHadImage = hadImage,
            aiItems = items.joinToString(" · ") { (n, g) -> "$n ${if (g % 1.0 == 0.0) g.toInt().toString() else "%.1f".format(g)} g" },
            aiConfidence = confidence
        )
        update { it.copy(meals = it.meals + record) }
        return record.id
    }

    override fun setApplied(id: String, carbs: Int) {
        update { d -> d.copy(meals = d.meals.map { if (it.id == id) it.copy(appliedCarbs = carbs.toDouble()) else it }) }
    }

    companion object {

        private const val FILE_NAME = "meal_tracking.json"
        private const val BACKUP_PREFIX = "gz1:"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
