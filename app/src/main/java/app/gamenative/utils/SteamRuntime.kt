package app.gamenative.utils

import android.content.Context
import android.system.Os
import app.gamenative.PrefManager
import app.gamenative.utils.downloader.ContainerFilesDownloader
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Valve's real Steam Runtime platforms, installed as alternative rootfs
 * options for native Linux games. Steam's own Linux games are built
 * against (and QA'd inside) these exact runtimes, so running them in one
 * instead of the generic Wine [ImageFs] gives them the library set they
 * were actually linked against. Every variant ships as the same
 * flatpak-style Platform tarball on Valve's own repo (amd64/i386 -- the
 * same x86_64 binaries the games themselves are, translated by the same
 * box64 the Wine path already uses on this device):
 *
 * - scout   (Steam Runtime 1, Ubuntu 12.04 era): what classic native
 *           Linux ports from the 2013-2019 catalog target.
 * - soldier (Steam Runtime 2, Debian 10): mostly a Proton host runtime
 *           upstream, but a valid glibc environment in its own right.
 * - sniper  (Steam Runtime 3, Debian 11): the current default -- what
 *           new native Linux builds target and what Steam itself runs
 *           its client in nowadays.
 *
 * (Steam Runtime 4 / "medic" exists in Valve's tooling but publishes no
 * platform tarball yet -- checked for real; add it to the manifest when
 * it does.)
 *
 * These runtimes are deliberately immutable environments -- no package
 * manager, by design (Valve's pressure-vessel treats them as read-only
 * images too). A game that needs extra system packages is exactly what a
 * full distro container (droidtop's own container system on the same
 * [com.winlator.linux.LinuxContainerBackend] seam) is for; that split is
 * the same one SteamOS itself makes (immutable runtime for Steam games,
 * Flatpak/distrobox for everything with extra needs).
 *
 * The download itself goes through [ContainerFilesDownloader] like every
 * other container component (manifest ids "steamrt_<variant>", marked
 * external/alwaysDownload since they come from repo.steampowered.com and
 * are far too large to bundle); this object only owns turning the
 * extracted flatpak-layout tarball ("files/" = /usr, plus metadata) into
 * a usable rootfs and answering "which rootfs should a native Linux game
 * use right now".
 */
object SteamRuntime {
    /** Real published variants, oldest to newest. */
    @JvmStatic
    val VARIANTS = listOf("scout", "soldier", "sniper")

    const val DEFAULT_VARIANT = "sniper"

    private const val MARKER_NAME = ".steamrt-installed"
    private val installMutex = Mutex()
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var installRunning = false

    @JvmStatic
    fun selectedVariant(): String =
        PrefManager.steamRuntimeVariant.takeIf { it in VARIANTS } ?: DEFAULT_VARIANT

    @JvmStatic
    fun rootDir(context: Context, variant: String = selectedVariant()): File =
        File(context.filesDir, "steamrt/$variant")

    @JvmStatic
    fun isInstalled(context: Context, variant: String = selectedVariant()): Boolean =
        File(rootDir(context, variant), MARKER_NAME).isFile

    /**
     * The rootfs a native Linux game should run in right now: the selected
     * Steam Runtime when the user has the feature enabled AND that variant
     * is actually installed, else null (caller falls back to [ImageFs]).
     * Never blocks on a download -- see [kickOffInstallIfWanted].
     */
    @JvmStatic
    fun preferredRootDir(context: Context): File? {
        if (!PrefManager.useSteamRuntime) return null
        val variant = selectedVariant()
        return if (isInstalled(context, variant)) rootDir(context, variant) else null
    }

    /**
     * Fire-and-forget install for launch paths that must not block: if the
     * user wants the runtime and the selected variant isn't installed yet,
     * start the download/extract in the background (deduplicated) so the
     * NEXT launch gets it. The current launch proceeds on whatever rootfs
     * [preferredRootDir] already answered.
     */
    @JvmStatic
    fun kickOffInstallIfWanted(context: Context) {
        if (!PrefManager.useSteamRuntime || isInstalled(context) || installRunning) return
        val appContext = context.applicationContext
        installScope.launch {
            try {
                ensureInstalled(appContext) { }
            } catch (e: Exception) {
                Timber.e(e, "Background Steam Runtime install failed; native Linux games keep using ImageFs")
            }
        }
    }

    /**
     * Downloads (via [ContainerFilesDownloader], cached) and installs the
     * variant. Safe to call repeatedly; concurrent callers coalesce.
     */
    suspend fun ensureInstalled(
        context: Context,
        variant: String = selectedVariant(),
        onProgress: (Float) -> Unit,
    ) {
        require(variant in VARIANTS) { "Unknown Steam Runtime variant: $variant" }
        installMutex.withLock {
            if (isInstalled(context, variant)) return
            installRunning = true
            try {
                val tarball = ContainerFilesDownloader.ensureContainerFileAvailable(context, "steamrt_$variant", onProgress)
                    ?: error("Steam Runtime component resolved to bundled assets, which cannot happen for an alwaysDownload component")
                install(context, variant, tarball)
                // The ~300MB compressed tarball has served its purpose; the
                // generic cache would otherwise keep it forever (it only
                // prunes .tzst files).
                tarball.delete()
            } finally {
                installRunning = false
            }
        }
    }

    private fun install(context: Context, variant: String, tarball: File) {
        val root = rootDir(context, variant)
        if (root.exists()) root.deleteRecursively()
        val staging = File(root.parentFile, ".staging-$variant")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        Timber.i("Extracting Steam Runtime $variant from ${tarball.name}")
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.GZIP, tarball, staging)) {
            staging.deleteRecursively()
            error("Failed to extract Steam Runtime tarball ${tarball.absolutePath}")
        }

        // Valve's runtime tarball is flatpak-layout: "files/" is the
        // runtime's /usr, plus a "metadata" keyfile. Assemble a normal
        // usr-merge rootfs around it. If a future snapshot ships a plain
        // rootfs (top-level usr/) instead, use it directly.
        val filesDir = File(staging, "files")
        root.parentFile?.mkdirs()
        if (filesDir.isDirectory) {
            root.mkdirs()
            if (!filesDir.renameTo(File(root, "usr"))) {
                staging.deleteRecursively()
                error("Failed to move extracted runtime into place at ${root.absolutePath}")
            }
            staging.deleteRecursively()
        } else if (File(staging, "usr").isDirectory) {
            if (!staging.renameTo(root)) {
                error("Failed to move extracted runtime into place at ${root.absolutePath}")
            }
        } else {
            staging.deleteRecursively()
            error("Extracted Steam Runtime tarball has neither files/ nor usr/ at its top level")
        }

        // usr-merge symlinks + the guest dirs proot expects to exist.
        for (name in arrayOf("bin", "sbin", "lib", "lib32", "lib64")) {
            val link = File(root, name)
            if (!link.exists() && File(root, "usr/$name").exists()) {
                Os.symlink("usr/$name", link.absolutePath)
            }
        }
        val etc = File(root, "etc")
        if (!etc.exists() && File(root, "usr/etc").exists()) {
            Os.symlink("usr/etc", etc.absolutePath)
        }
        for (name in arrayOf(ImageFs.HOME_PATH.trimStart('/'), "root", "tmp", "var", "opt", "dev", "proc", "sys")) {
            File(root, name).mkdirs()
        }

        File(root, MARKER_NAME).writeText(tarball.name)
        Timber.i("Steam Runtime $variant installed at ${root.absolutePath}")
    }

    /** Frees a variant's disk space again; launches fall back to ImageFs. */
    fun uninstall(context: Context, variant: String = selectedVariant()) {
        rootDir(context, variant).deleteRecursively()
    }
}
