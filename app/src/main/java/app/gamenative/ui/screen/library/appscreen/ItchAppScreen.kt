package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gamenative.data.ItchGame
import app.gamenative.data.ItchUpload
import app.gamenative.data.LibraryItem
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.utils.ContainerUtils
import com.winlator.container.ContainerData
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * itch.io implementation of [BaseAppScreen].
 *
 * Simpler than GOG/Epic/Amazon on purpose: itch has no manifest/depot
 * protocol, no DRM handshake and no per-language builds -- an owned game
 * is a download key plus a flat list of uploads (one file per platform),
 * so "install" here is "download one file, extract it if it is an
 * archive" ([app.gamenative.service.itch.ItchDownloadManager]). Where a
 * game offers more than one runnable upload (say, both a Windows and a
 * Linux build) this screen asks which one, the same "Runs with" choice
 * every other PC source treats as first-class rather than picking for
 * the user silently.
 */
class ItchAppScreen : BaseAppScreen() {
    companion object {
        private const val TAG = "ItchAppScreen"
        private val uninstallDialogAppIds = mutableStateListOf<String>()
        private val uploadPickerAppIds = mutableStateListOf<String>()
        private val uploadOptionsByAppId = mutableMapOf<String, List<ItchUpload>>()
        var showDeletingDialog by mutableStateOf(false)
        var showFetchingUploadsDialog by mutableStateOf(false)

        fun showUninstallDialog(appId: String) {
            if (!uninstallDialogAppIds.contains(appId)) uninstallDialogAppIds.add(appId)
        }

        fun hideUninstallDialog(appId: String) {
            uninstallDialogAppIds.remove(appId)
        }

        fun shouldShowUninstallDialog(appId: String): Boolean = uninstallDialogAppIds.contains(appId)

        fun showUploadPicker(appId: String, uploads: List<ItchUpload>) {
            uploadOptionsByAppId[appId] = uploads
            if (!uploadPickerAppIds.contains(appId)) uploadPickerAppIds.add(appId)
        }

        fun hideUploadPicker(appId: String) {
            uploadPickerAppIds.remove(appId)
        }

        fun shouldShowUploadPicker(appId: String): Boolean = uploadPickerAppIds.contains(appId)

        fun uploadOptionsFor(appId: String): List<ItchUpload> = uploadOptionsByAppId[appId].orEmpty()

        private fun formatBytes(bytes: Long): String {
            val kb = 1024.0
            val mb = kb * 1024
            val gb = mb * 1024
            return when {
                bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
                bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
                bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
                else -> "$bytes B"
            }
        }

        /** Uploads worth offering at all: a real Windows/Linux/Android build, not a soundtrack or demo. */
        internal fun runnableUploads(uploads: List<ItchUpload>): List<ItchUpload> =
            uploads.filter { (it.platformWindows || it.platformLinux || it.platformAndroid) && !it.isDemo }
    }

    private fun gameId(libraryItem: LibraryItem): String = libraryItem.gameId.toString()

    @Composable
    override fun getGameDisplayInfo(context: Context, libraryItem: LibraryItem): GameDisplayInfo {
        var game by remember(libraryItem.appId) { mutableStateOf<ItchGame?>(null) }
        LaunchedEffect(libraryItem.appId) {
            game = app.gamenative.service.itch.ItchService.getItchGameOf(context, gameId(libraryItem))
        }
        val sizeOnDisk = game?.takeIf { it.isInstalled && it.sizeBytes > 0 }?.let { formatBytes(it.sizeBytes) }
        return GameDisplayInfo(
            name = game?.title ?: libraryItem.name,
            developer = "",
            releaseDate = 0L,
            heroImageUrl = game?.coverUrl?.ifEmpty { null } ?: libraryItem.iconHash,
            iconUrl = game?.coverUrl?.ifEmpty { null } ?: libraryItem.iconHash,
            gameId = libraryItem.gameId,
            appId = libraryItem.appId,
            installLocation = game?.installPath?.takeIf { it.isNotEmpty() },
            sizeOnDisk = sizeOnDisk,
            sizeFromStore = null,
        )
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean =
        app.gamenative.service.itch.ItchService.isGameInstalled(gameId(libraryItem))

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean =
        !isInstalled(context, libraryItem) && !isDownloading(context, libraryItem)

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean =
        app.gamenative.service.itch.ItchService.getDownloadInfo(gameId(libraryItem))?.isActive() == true

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float =
        app.gamenative.service.itch.ItchService.getDownloadInfo(gameId(libraryItem))?.getProgress() ?: 0f

    override fun onDownloadInstallClick(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        val id = gameId(libraryItem)
        when {
            isDownloading(context, libraryItem) -> {
                app.gamenative.service.itch.ItchService.cancelDownload(id)
            }
            isInstalled(context, libraryItem) -> onClickPlay(false)
            else -> beginInstall(context, libraryItem)
        }
    }

    private fun beginInstall(context: Context, libraryItem: LibraryItem) {
        val id = gameId(libraryItem)
        showFetchingUploadsDialog = true
        CoroutineScope(Dispatchers.IO).launch {
            val uploads = app.gamenative.service.itch.ItchService.listUploads(context, id)
                .getOrElse {
                    Timber.tag(TAG).e(it, "Failed to list itch.io uploads for $id")
                    showFetchingUploadsDialog = false
                    app.gamenative.ui.util.SnackbarManager.show("Could not load downloads: ${it.message}")
                    return@launch
                }
            showFetchingUploadsDialog = false
            val candidates = runnableUploads(uploads)
            when {
                candidates.isEmpty() -> app.gamenative.ui.util.SnackbarManager.show(
                    "itch.io lists no Windows, Linux or Android build for ${libraryItem.name}",
                )
                candidates.size == 1 -> startDownload(context, libraryItem, candidates.first())
                else -> showUploadPicker(libraryItem.appId, candidates)
            }
        }
    }

    private fun startDownload(context: Context, libraryItem: LibraryItem, upload: ItchUpload) {
        val id = gameId(libraryItem)
        val game = kotlinx.coroutines.runBlocking {
            app.gamenative.service.itch.ItchService.getItchGameOf(context, id)
        }
        val installPath = game?.installPath?.takeIf { it.isNotEmpty() }
            ?: app.gamenative.service.itch.ItchConstants.getGameInstallPath(libraryItem.name)
        val result = app.gamenative.service.itch.ItchService.downloadGame(context, id, upload, installPath)
        result.exceptionOrNull()?.let {
            app.gamenative.ui.util.SnackbarManager.show("Could not start download: ${it.message}")
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        val id = gameId(libraryItem)
        if (isDownloading(context, libraryItem)) {
            app.gamenative.service.itch.ItchService.cancelDownload(id)
        } else {
            beginInstall(context, libraryItem)
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        val id = gameId(libraryItem)
        if (isDownloading(context, libraryItem)) {
            app.gamenative.service.itch.ItchService.cancelDownload(id)
        } else if (isInstalled(context, libraryItem)) {
            showUninstallDialog(libraryItem.appId)
        }
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        // itch has no version-diffing API for a purchased build; re-running the same
        // upload choice is the closest equivalent to "update" and is what the resume
        // path already does.
        CoroutineScope(Dispatchers.IO).launch {
            app.gamenative.service.itch.ItchService.resumeOrStartDownload(context, gameId(libraryItem))
        }
    }

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? =
        app.gamenative.service.itch.ItchService.getInstallPath(gameId(libraryItem)).takeIf { it.isNotEmpty() }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        return ContainerUtils.toContainerData(container)
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        ContainerUtils.applyToContainer(context, libraryItem.appId, config)
    }

    override fun supportsContainerConfig(): Boolean = true

    override fun getExportFileExtension(): String = ".itch"

    @Composable
    override fun getResetContainerOption(context: Context, libraryItem: LibraryItem): AppMenuOption {
        var showResetConfirmDialog by remember { mutableStateOf(false) }
        if (showResetConfirmDialog) {
            ResetConfirmDialog(
                onConfirm = {
                    showResetConfirmDialog = false
                    resetContainerToDefaults(context, libraryItem)
                },
                onDismiss = { showResetConfirmDialog = false },
            )
        }
        return AppMenuOption(
            optionType = app.gamenative.ui.enums.AppOptionMenuType.ResetToDefaults,
            onClick = { showResetConfirmDialog = true },
        )
    }

    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
    ) {
        val context = LocalContext.current
        val appId = libraryItem.appId

        if (showFetchingUploadsDialog) {
            LoadingDialog(visible = true, progress = -1f, message = "Looking up downloads...")
        }
        if (showDeletingDialog) {
            LoadingDialog(visible = true, progress = -1f, message = "Deleting...")
        }

        var showPicker by remember(appId) { mutableStateOf(shouldShowUploadPicker(appId)) }
        LaunchedEffect(appId) {
            snapshotFlow { shouldShowUploadPicker(appId) }.collect { showPicker = it }
        }
        if (showPicker) {
            val options = uploadOptionsFor(appId)
            AlertDialog(
                onDismissRequest = { hideUploadPicker(appId) },
                title = { Text("Choose a download") },
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(options) { upload ->
                            TextButton(
                                onClick = {
                                    hideUploadPicker(appId)
                                    startDownload(context, libraryItem, upload)
                                },
                                modifier = Modifier.padding(vertical = 2.dp),
                            ) {
                                val platform = when {
                                    upload.platformWindows -> "Windows"
                                    upload.platformLinux -> "Linux"
                                    upload.platformAndroid -> "Android"
                                    else -> "Other"
                                }
                                Text(
                                    "${upload.displayName ?: upload.filename} ($platform, ${formatBytes(upload.sizeBytes)})",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { hideUploadPicker(appId) }) { Text("Cancel") }
                },
            )
        }

        var showUninstall by remember(appId) { mutableStateOf(shouldShowUninstallDialog(appId)) }
        LaunchedEffect(appId) {
            snapshotFlow { shouldShowUninstallDialog(appId) }.collect { showUninstall = it }
        }
        if (showUninstall) {
            AlertDialog(
                onDismissRequest = { hideUninstallDialog(appId) },
                title = { Text("Uninstall ${libraryItem.name}?") },
                text = { Text("This deletes the downloaded files. You can reinstall it from itch.io again later.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideUninstallDialog(appId)
                            showDeletingDialog = true
                            CoroutineScope(Dispatchers.IO).launch {
                                val id = gameId(libraryItem)
                                val result = app.gamenative.service.itch.ItchService.uninstall(context, id)
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    showDeletingDialog = false
                                    result.exceptionOrNull()?.let {
                                        app.gamenative.ui.util.SnackbarManager.show("Failed to uninstall: ${it.message}")
                                    }
                                }
                            }
                        },
                    ) { Text("Uninstall") }
                },
                dismissButton = {
                    TextButton(onClick = { hideUninstallDialog(appId) }) { Text("Cancel") }
                },
            )
        }
    }
}
