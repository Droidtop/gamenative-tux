package app.gamenative.service.itch

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.gamenative.PrefManager
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class ItchConstantsTest {
    @Before
    fun setUp() {
        val mockDataStore = Mockito.mock(DataStore::class.java) as DataStore<Preferences>
        Mockito.`when`(mockDataStore.data).thenReturn(flowOf(emptyPreferences()))

        val dataStoreField = PrefManager::class.java.getDeclaredField("dataStore")
        dataStoreField.isAccessible = true
        dataStoreField.set(PrefManager, mockDataStore)

        val context = Mockito.mock(Context::class.java)
        val filesDir = File("/tmp/itch-internal")
        filesDir.mkdirs()
        Mockito.`when`(context.filesDir).thenReturn(filesDir)
        Mockito.`when`(context.dataDir).thenReturn(filesDir)
        Mockito.`when`(context.applicationContext).thenReturn(context)

        PrefManager.init(context)
        ItchConstants.init(context)
    }

    @Test
    fun getGameInstallPath_pathStructure() {
        val path = ItchConstants.getGameInstallPath("A Neat Little Game")
        assertEquals("/tmp/itch-internal/Itch/games/common/A Neat Little Game", path)
    }

    @Test
    fun getGameInstallPath_sanitizesSpecialChars() {
        val path = ItchConstants.getGameInstallPath("G%ame@With^Special*Chars")
        assertEquals("/tmp/itch-internal/Itch/games/common/GameWithSpecialChars", path)
    }

    @Test
    fun getGameInstallPath_blankTitleFallsBackToPlaceholder() {
        val path = ItchConstants.getGameInstallPath("!!!")
        assertEquals("/tmp/itch-internal/Itch/games/common/itch-game", path)
    }

    @Test
    fun getAuthConfigPath_isUnderFilesDir() {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.filesDir).thenReturn(File("/tmp/itch-internal"))
        assertEquals("/tmp/itch-internal/itch_auth.json", ItchConstants.getAuthConfigPath(context))
    }
}
