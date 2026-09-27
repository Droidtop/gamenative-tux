package app.gamenative.service.itch

import app.gamenative.data.DownloadInfo
import app.gamenative.data.ItchUpload
import app.gamenative.mods.ModArchiveExtractor
import app.gamenative.service.itch.api.ItchApiClient
import app.gamenative.utils.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Downloads one itch.io upload and installs it -- one file, unlike GOG,
 * Epic and Amazon's manifest-driven, many-file installs, because itch
 * uploads are exactly that: one archive or one executable, with no
 * per-file patch protocol.
 *
 * Extraction reuses [ModArchiveExtractor], the same archive-bomb-guarded
 * zip/7z/rar/exe extractor the mods pipeline already uses -- one
 * mechanism for "unpack an untrusted archive into a game folder",
 * not a second one written for itch.
 */
object ItchDownloadManager {
    private const val TAG = "ItchDownloadManager"
    private val httpClient = Net.http

    /**
     * Downloads [upload] to a temp file, then extracts it into
     * [installPath]. Reports progress and honours cancellation through
     * [downloadInfo], the same shared progress/cancel object every other
     * store service here uses.
     */
    suspend fun downloadAndInstall(
        apiKey: String,
        downloadKeyId: String,
        upload: ItchUpload,
        installPath: String,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            downloadInfo.updateStatusMessage("Resolving download...")
            val url = ItchApiClient.resolveDownloadUrl(apiKey, upload.id, downloadKeyId)
                .getOrElse { return@withContext Result.failure(it) }

            val installDir = File(installPath)
            installDir.mkdirs()
            val tempFile = File(installDir.parentFile ?: installDir, "${installDir.name}.download.tmp")
            tempFile.parentFile?.mkdirs()

            downloadInfo.setTotalExpectedBytes(upload.sizeBytes)
            downloadInfo.updateStatusMessage("Downloading ${upload.filename}...")

            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Download failed: HTTP ${response.code}"))
                }
                val body = response.body ?: return@withContext Result.failure(Exception("Empty download response"))
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesSinceEmit = 0L
                        while (true) {
                            if (!downloadInfo.isActive()) {
                                throw CancellationException("Download cancelled")
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadInfo.updateBytesDownloaded(read.toLong())
                            bytesSinceEmit += read
                            if (bytesSinceEmit >= 512 * 1024L) {
                                bytesSinceEmit = 0L
                                downloadInfo.emitProgressChange()
                                downloadInfo.persistProgressSnapshot()
                            }
                        }
                    }
                }
            }

            downloadInfo.updateStatusMessage("Installing...")
            installDownloadedFile(tempFile, upload, installDir)
            tempFile.delete()

            downloadInfo.setProgress(1f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()
            Result.success(Unit)
        } catch (e: CancellationException) {
            downloadInfo.setActive(false)
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "itch download/install failed")
            downloadInfo.updateStatusMessage("Failed: ${e.message}")
            downloadInfo.setActive(false)
            Result.failure(e)
        }
    }

    /**
     * Extracts a real archive (zip/7z/rar) into [installDir]; a plain
     * executable or APK upload is copied in as-is, since there is
     * nothing to unpack. The extension, not the upload's declared
     * platform, decides which -- an upload can be a single .exe with no
     * archive around it at all.
     */
    private suspend fun installDownloadedFile(downloaded: File, upload: ItchUpload, installDir: File) {
        val extension = upload.filename.substringAfterLast('.', "").lowercase()
        when (extension) {
            "zip", "7z", "rar" -> ModArchiveExtractor.extract(downloaded, installDir)
            else -> {
                // A bare executable, .apk, or another single-file upload:
                // keep its own filename inside the install directory so
                // the runner's own executable search still finds it.
                installDir.mkdirs()
                val target = File(installDir, upload.filename.ifBlank { downloaded.name })
                downloaded.copyTo(target, overwrite = true)
            }
        }
    }

    /** Progress listeners a screen can attach to, shared across concurrent downloads. */
    private val activeDownloads = CopyOnWriteArrayList<Pair<String, DownloadInfo>>()

    fun track(gameId: String, downloadInfo: DownloadInfo) {
        activeDownloads.removeAll { it.first == gameId }
        activeDownloads.add(gameId to downloadInfo)
    }

    fun untrack(gameId: String) {
        activeDownloads.removeAll { it.first == gameId }
    }

    fun get(gameId: String): DownloadInfo? = activeDownloads.firstOrNull { it.first == gameId }?.second
}
