package app.gamenative.service.itch

import app.gamenative.data.GameSource
import app.gamenative.data.ItchGame
import app.gamenative.data.ItchUpload
import app.gamenative.data.LibraryItem

/**
 * itch.io LibraryItem mappings, mirroring Amazon's pattern:
 * [toLibraryItem], [libraryItemFor], [displayName].
 */
object ItchLibraryMapping {

    fun displayName(game: ItchGame): String = game.title.takeIf { it.isNotBlank() } ?: game.id

    fun displayName(upload: ItchUpload): String = upload.displayName ?: upload.filename

    fun toLibraryItem(game: ItchGame, index: Int): LibraryItem = LibraryItem(
        index = index,
        appId = "${GameSource.ITCH.name}_${game.id}",
        name = displayName(game),
        iconHash = game.coverUrl,
        capsuleImageUrl = game.coverUrl,
        headerImageUrl = game.coverUrl,
        heroImageUrl = game.coverUrl,
        isShared = false,
        gameSource = GameSource.ITCH,
        sizeBytes = game.sizeBytes,
    )

    fun libraryItemFor(libraryItem: LibraryItem): ItchGame? {
        // Lookup shape for future use; actual DB lookup is in ItchService/LibraryViewModel.
        return null
    }

    fun toGame(libraryItem: LibraryItem): String = libraryItem.gameId.toString()
}
