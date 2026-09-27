package app.gamenative.service.itch

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.ItchGame
import app.gamenative.data.ItchUpload
import app.gamenative.db.dao.ItchGameDao
import app.gamenative.service.itch.api.ItchApiClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Orchestrates the itch.io source: library sync, install lifecycle and
 * download tracking, in the same shape [app.gamenative.service.gog.GOGService]
 * gives its own screen -- the model this was built to follow ("using the
 * same shape") so [ItchAppScreen] and any future consumer read this
 * exactly like the other three stores.
 *
 * A plain object, not an Android [android.app.Service] the way GOG/Epic/
 * Amazon are: those run a background service for periodic polling sync,
 * which itch does not need (no live entitlement or friends state to
 * watch) -- a library refresh happens on sign-in and when the settings
 * row asks for one. The DAO is reached through Hilt's
 * [EntryPointAccessors], the same pattern [app.gamenative.mods.NexusModManager]
 * already uses for a plain object that still needs a Room DAO.
 */
object ItchService {
    private const val TAG = "ItchService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ItchDaoEntryPoint {
        fun itchGameDao(): ItchGameDao
    }

    private fun dao(context: Context): ItchGameDao =
        EntryPointAccessors.fromApplication(context.applicationContext, ItchDaoEntryPoint::class.java).itchGameDao()

    fun hasStoredCredentials(context: Context): Boolean = ItchAuthManager.hasStoredCredentials(context)

    suspend fun signIn(context: Context, apiKey: String): Result<String> = ItchAuthManager.signIn(context, apiKey)

    fun signOut(context: Context): Boolean = ItchAuthManager.signOut(context)

    /**
     * Refreshes the owned-games table from `/profile/owned-keys`. Install
     * status, path and chosen upload survive the refresh
     * ([ItchGameDao.upsertPreservingInstallStatus]), the same reason
     * [app.gamenative.service.gog.GOGService]'s own sync preserves them.
     */
    suspend fun syncLibrary(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        val apiKey = ItchAuthManager.getStoredApiKey(context)
            ?: return@withContext Result.failure(Exception("Not signed in to itch.io"))
        val owned = ItchApiClient.ownedKeys(apiKey).getOrElse { return@withContext Result.failure(it) }
        val games = owned.map { key ->
            ItchGame(
                id = key.gameId,
                title = key.gameTitle,
                downloadKeyId = key.downloadKeyId,
                coverUrl = key.coverUrl,
                url = key.gameUrl,
            )
        }
        dao(context).upsertPreservingInstallStatus(games)
        refreshInstalledCache(context)
        Timber.tag(TAG).i("Synced ${games.size} itch.io games")
        Result.success(games.size)
    }

    suspend fun getItchGameOf(context: Context, gameId: String): ItchGame? = dao(context).getById(gameId)

    suspend fun getAllOwnedGames(context: Context): List<ItchGame> = dao(context).getAllAsList()

    // A small synchronous cache so BaseAppScreen's non-suspend isInstalled() can answer
    // without blocking a UI thread on Room -- refreshed after every sync and install/uninstall.
    private val installedCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val installPathCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val titleCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val coverUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun isGameInstalled(gameId: String): Boolean = installedCache[gameId] ?: false

    /** Synchronous, cache-backed -- mirrors [app.gamenative.service.gog.GOGService.getInstallPath]'s shape for callers off the main thread that cannot suspend. */
    fun getInstallPath(gameId: String): String = installPathCache[gameId].orEmpty()

    fun getCachedTitle(gameId: String): String? = titleCache[gameId]

    fun getCachedCoverUrl(gameId: String): String? = coverUrlCache[gameId]

    suspend fun refreshInstalledCache(context: Context) {
        dao(context).getAllAsList().forEach { game ->
            installedCache[game.id] = game.isInstalled
            if (game.installPath.isNotBlank()) installPathCache[game.id] = game.installPath
            titleCache[game.id] = game.title
            coverUrlCache[game.id] = game.coverUrl
        }
    }

    /** `GET /games/{gameId}/uploads`; the screen decides which platforms it can actually run. */
    suspend fun listUploads(context: Context, gameId: String): Result<List<ItchUpload>> {
        val apiKey = ItchAuthManager.getStoredApiKey(context)
            ?: return Result.failure(Exception("Not signed in to itch.io"))
        val game = dao(context).getById(gameId) ?: return Result.failure(Exception("Unknown itch.io game: $gameId"))
        return ItchApiClient.uploads(apiKey, gameId, game.downloadKeyId)
    }

    private val downloadInfos = CopyOnWriteArrayList<Pair<String, DownloadInfo>>()

    fun getDownloadInfo(gameId: String): DownloadInfo? = downloadInfos.firstOrNull { it.first == gameId }?.second

    fun hasPartialDownload(installPath: String): Boolean {
        val parent = File(installPath).parentFile ?: return false
        return File(parent, "${File(installPath).name}.download.tmp").exists()
    }

    fun cleanupDownload(gameId: String) {
        downloadInfos.removeAll { it.first == gameId }
    }

    fun cancelDownload(gameId: String) {
        getDownloadInfo(gameId)?.cancel()
    }

    /**
     * Updates the recorded install path for a game whose files were
     * found somewhere other than where Room says -- the same "legacy
     * path" repair [app.gamenative.service.gog.GOGService.updateInstallPath]
     * and Epic's/Amazon's equivalents perform.
     */
    fun updateInstallPath(context: Context, gameId: String, newPath: String) {
        runBlocking(Dispatchers.IO) {
            val game = dao(context).getById(gameId) ?: return@runBlocking
            if (game.installPath != newPath) dao(context).update(game.copy(installPath = newPath))
            installPathCache[gameId] = newPath
        }
    }

    /**
     * Starts (or restarts) a download for [gameId] by re-resolving its
     * uploads and picking the same one it last installed, or a runnable
     * default -- used when the downloads screen only has an appId and
     * gameSource to work with (pause/resume/retry), unlike the settings
     * screen which already has a specific [ItchUpload] the user picked.
     */
    suspend fun resumeOrStartDownload(context: Context, gameId: String): Result<Unit> {
        val game = dao(context).getById(gameId) ?: return Result.failure(Exception("Unknown itch.io game: $gameId"))
        val uploads = listUploads(context, gameId).getOrElse { return Result.failure(it) }
        if (uploads.isEmpty()) return Result.failure(Exception("itch.io lists no uploads for ${game.title}"))
        val upload = uploads.firstOrNull { it.id == game.installedUploadId }
            ?: uploads.firstOrNull { (it.platformWindows || it.platformLinux) && !it.isDemo }
            ?: uploads.first()
        val installPath = game.installPath.ifBlank { ItchConstants.getGameInstallPath(game.title) }
        return downloadGame(context, gameId, upload, installPath)
    }

    /**
     * Downloads [upload] and installs it under [installPath], then marks
     * the game installed in Room. Runs on [scope], the same
     * fire-and-forget shape the other services' downloads use, so a
     * Compose click handler is never blocked on the network.
     */
    fun downloadGame(context: Context, gameId: String, upload: ItchUpload, installPath: String): Result<Unit> {
        val apiKey = ItchAuthManager.getStoredApiKey(context)
            ?: return Result.failure(Exception("Not signed in to itch.io"))
        val game = runBlocking { dao(context).getById(gameId) }
            ?: return Result.failure(Exception("Unknown itch.io game: $gameId"))

        val downloadInfo = DownloadInfo(jobCount = 1, gameId = gameId.toIntOrNull() ?: 0, downloadingAppIds = CopyOnWriteArrayList())
        downloadInfo.setActive(true)
        downloadInfos.removeAll { it.first == gameId }
        downloadInfos.add(gameId to downloadInfo)

        val job = scope.launch {
            val result = ItchDownloadManager.downloadAndInstall(
                apiKey = apiKey,
                downloadKeyId = game.downloadKeyId,
                upload = upload,
                installPath = installPath,
                downloadInfo = downloadInfo,
            )
            result.onSuccess {
                dao(context).update(
                    game.copy(
                        isInstalled = true,
                        installPath = installPath,
                        installedUploadId = upload.id,
                        sizeBytes = upload.sizeBytes,
                    ),
                )
                installedCache[gameId] = true
                installPathCache[gameId] = installPath
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "itch.io install failed for $gameId")
            }
        }
        downloadInfo.setDownloadJob(job)
        return Result.success(Unit)
    }

    suspend fun uninstall(context: Context, gameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val game = dao(context).getById(gameId) ?: return@withContext Result.failure(Exception("Unknown itch.io game: $gameId"))
        try {
            if (game.installPath.isNotBlank()) {
                File(game.installPath).deleteRecursively()
            }
            dao(context).update(game.copy(isInstalled = false, installPath = "", installedUploadId = 0))
            installedCache[gameId] = false
            installPathCache.remove(gameId)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to uninstall itch.io game $gameId")
            Result.failure(e)
        }
    }
}
