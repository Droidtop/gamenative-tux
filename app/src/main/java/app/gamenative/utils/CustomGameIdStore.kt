package app.gamenative.utils

import java.io.File

/**
 * Where [CustomGameScanner] keeps the numeric id of a Custom Game folder.
 *
 * The default, [FileCustomGameIdStore], is the app's own behaviour and is
 * unchanged: the id lives in a `.gamenative` file inside the game folder.
 * A host that embeds this code and must not write into the user's game
 * folders (droidtop) installs its own store with [CustomGameIdStores.install]
 * before the first scan.
 */
interface CustomGameIdStore {
    /** The id remembered for [folder], or null when there is none. */
    fun read(folder: File): Int?

    /** Remember [gameId] for [folder]. */
    fun write(folder: File, gameId: Int)
}

/** The standalone behaviour: the id is a `.gamenative` file in the game folder. */
object FileCustomGameIdStore : CustomGameIdStore {
    override fun read(folder: File): Int? = GameMetadataManager.getAppId(folder)

    override fun write(folder: File, gameId: Int) {
        val existing = GameMetadataManager.read(folder)
        val metadata = existing?.copy(appId = gameId) ?: GameMetadata(appId = gameId)
        GameMetadataManager.write(folder, metadata)
    }
}

object CustomGameIdStores {
    @Volatile
    var current: CustomGameIdStore = FileCustomGameIdStore
        private set

    fun install(store: CustomGameIdStore) {
        current = store
        CustomGameCache.invalidate()
    }
}
