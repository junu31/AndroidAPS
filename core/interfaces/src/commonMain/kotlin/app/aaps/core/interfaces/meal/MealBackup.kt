package app.aaps.core.interfaces.meal

/**
 * Personal-fork: the meal tracking records travel inside the settings export as one string entry ([PREF_KEY]),
 * so they move to a new phone together with the settings. Apps that do not know the key just keep it as an unused setting.
 */
interface MealBackup {

    /** everything stored, packed into one string (null when there is nothing to export) */
    fun exportBackup(): String?

    /** replace the stored records with the packed [data] from [exportBackup] */
    fun importBackup(data: String)

    companion object {

        const val PREF_KEY = "personal_meal_tracking_backup"
    }
}
