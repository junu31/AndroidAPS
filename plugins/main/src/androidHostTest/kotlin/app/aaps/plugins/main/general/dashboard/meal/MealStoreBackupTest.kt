package app.aaps.plugins.main.general.dashboard.meal

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File

class MealStoreBackupTest {

    private fun store(dir: File) = MealStore(mock<Context> { on { filesDir } doReturn dir }, mock<AAPSLogger>())

    @Test
    fun `backup restores the records on another phone`(@TempDir a: File, @TempDir b: File) {
        val source = store(a)
        source.update { it.copy(foods = it.foods + MealFood("f1", "투움바 카츠", FoodType.FAT, true, 1L)) }

        val packed = source.exportBackup()
        assertThat(packed).startsWith("gz1:")

        val target = store(b)
        target.importBackup(packed!!)
        assertThat(target.data().foods.single().name).isEqualTo("투움바 카츠")
        // and it is on disk, not only in the cache
        assertThat(store(b).data().foods.single().id).isEqualTo("f1")
    }

    @Test
    fun `nothing stored exports nothing`(@TempDir a: File) {
        assertThat(store(a).exportBackup()).isNull()
    }

    @Test
    fun `foreign data is ignored`(@TempDir a: File) {
        val s = store(a)
        s.importBackup("not a backup")
        assertThat(s.data().foods).isEmpty()
    }
}
