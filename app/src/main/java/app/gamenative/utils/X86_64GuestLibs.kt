package app.gamenative.utils

import android.content.Context
import app.gamenative.service.SteamService
import com.winlator.core.AppUtils
import com.winlator.core.FileUtils
import com.winlator.core.TarCompressorUtils
import com.winlator.core.envvars.EnvVars
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import timber.log.Timber

/**
 * What an x86_64 device needs to run the bionic x86_64 Wine directly, with no
 * box64 in between.
 *
 * The Wine builds GameNative installs (`proton-9.0-x86_64` and friends) are
 * x86_64 Android executables. On arm64 they run under box64, which maps their
 * X11, freetype and fontconfig calls onto the aarch64 libraries in the base
 * system (`imagefs/usr/lib`). On an x86_64 device they run as they are, so
 * those libraries have to exist for x86_64. This is that set: built by
 * `tools/x86_64-guest-libs` in this repository's CI, published as a release
 * asset, downloaded once and kept beside the image in [root]. The aarch64 image
 * stays installed for its data (fonts, shared files, the Windows-side extras);
 * an x86_64 launch never puts its library directory on the search path.
 *
 * The same release carries `libexec-redirect.so`, the x86_64 counterpart of the
 * closed aarch64 `libredirect-bionic-wx.so` for the one job Wine needs from it
 * (starting its own children where Android refuses exec() of app files), and
 * `libandroid-sysvshm.so`.
 */
object X86_64GuestLibs {
    /** Release of Droidtop/gamenative-tux that carries the asset; see .github/workflows/x86_64-guest-libs.yml. */
    const val RELEASE_TAG = "x86_64-guest-libs-20261002-12f9f4d8"
    const val ASSET = "x86_64-guest-libs.tzst"
    const val SHA256 = "c1ebd33b812455b831e855a8f9d10aa0396e04c986ffd0ad4a8c0e9f3bb32c73"
    private const val URL = "https://github.com/Droidtop/gamenative-tux/releases/download/$RELEASE_TAG/$ASSET"
    private const val STAMP = ".release"

    /** True on a device whose primary ABI is x86_64: Wine runs there without box64. */
    @JvmStatic
    fun isX86_64Host(): Boolean = AppUtils.getArchName() == "x86_64"

    @JvmStatic
    fun root(context: Context): File = File(context.filesDir, "x86_64-guest-libs")

    @JvmStatic
    fun libDir(context: Context): File = File(root(context), "usr/lib")

    @JvmStatic
    fun isInstalled(context: Context): Boolean =
        File(root(context), STAMP).let { it.isFile && it.readText().trim() == RELEASE_TAG }

    /**
     * Downloads and unpacks the release named by [RELEASE_TAG] unless it is
     * already in place. Does nothing on arm64. Throws with a message a person
     * can act on when the download or the check fails.
     */
    suspend fun ensureInstalled(context: Context, onProgress: (Float) -> Unit) {
        if (!isX86_64Host() || isInstalled(context)) return
        val archive = File(context.filesDir, ASSET)
        if (!archive.isFile || sha256(archive) != SHA256) {
            archive.delete()
            SteamService.fetchFile(URL, archive, onProgress)
        }
        val actual = sha256(archive)
        if (actual != SHA256) {
            archive.delete()
            error("the x86_64 Windows libraries did not match their checksum; retry the setup")
        }
        val dest = root(context)
        val staging = File(context.filesDir, "x86_64-guest-libs.tmp")
        FileUtils.delete(staging)
        staging.mkdirs()
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, staging)) {
            FileUtils.delete(staging)
            error("couldn't unpack the x86_64 Windows libraries")
        }
        FileUtils.delete(dest)
        if (!staging.renameTo(dest)) {
            FileUtils.delete(staging)
            error("couldn't install the x86_64 Windows libraries")
        }
        File(dest, STAMP).writeText(RELEASE_TAG)
        archive.delete()
        Timber.i("X86_64GuestLibs: installed %s", RELEASE_TAG)
    }

    /**
     * Rewrites a guest environment built for the arm64 path into the x86_64
     * one. Called by BionicProgramLauncherComponent after it has put
     * everything else in place, so these values win.
     *
     *  - Library search: these libraries, then the system's, then the app's
     *    own natives (libpulse for winepulse, libevshim), then Wine's. Never
     *    `imagefs/usr/lib`, which is aarch64.
     *  - Preloads: sysvshm and exec-redirect from here, evshim from the app.
     *    The aarch64 libredirect is never preloaded.
     *  - X11: these libraries are stock builds that would look for
     *    /tmp/.X11-unix, which an app cannot reach; DISPLAY names the server's
     *    socket by its absolute path instead (libxcb takes a path there).
     *  - fontconfig reads a config written here that points at the image's own
     *    font directory, whatever this app's package is.
     */
    @JvmStatic
    fun applyLaunchEnv(context: Context, imageFs: ImageFs, envVars: EnvVars) {
        val lib = libDir(context)
        val rootPath = imageFs.rootDir.path
        val nativeDir = context.applicationInfo.nativeLibraryDir
        envVars.put("LD_LIBRARY_PATH", listOf(lib.path, "/system/lib64", nativeDir, imageFs.winePath + "/lib").joinToString(":"))
        envVars.put(
            "LD_PRELOAD",
            listOf(File(lib, "libandroid-sysvshm.so"), File(nativeDir, "libevshim.so"), File(lib, "libexec-redirect.so"))
                .filter { it.isFile }
                .joinToString(":") { it.path },
        )
        envVars.put("DISPLAY", rootPath + UnixSocketConfig.XSERVER_PATH)
        envVars.put("XLOCALEDIR", File(root(context), "usr/share/X11/locale").path)
        envVars.put("FONTCONFIG_FILE", writeFontsConf(context, imageFs).path)
        envVars.remove("FONTCONFIG_PATH")
        // There is no GLX on the app's X server, and the aarch64 Vulkan layers
        // in the image cannot load into an x86_64 process.
        envVars.remove("WINE_X11FORCEGLX")
        envVars.remove("VK_LAYER_PATH")
        envVars.remove("ENABLE_UTIL_LAYER")
        ensureVulkanLoaderLink(lib)
    }

    private fun writeFontsConf(context: Context, imageFs: ImageFs): File {
        val dir = File(root(context), "etc/fonts").apply { mkdirs() }
        val cache = File(root(context), "cache/fontconfig").apply { mkdirs() }
        val file = File(dir, "fonts.conf")
        val text = """
            <?xml version="1.0"?>
            <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
            <fontconfig>
              <dir>${File(imageFs.rootDir, "usr/share/fonts").path}</dir>
              <dir>/system/fonts</dir>
              <cachedir>${cache.path}</cachedir>
            </fontconfig>
        """.trimIndent() + "\n"
        if (!file.isFile || file.readText() != text) file.writeText(text)
        return file
    }

    /** Wine opens libvulkan.so.1; Android's loader is /system/lib64/libvulkan.so with no .1 name. */
    private fun ensureVulkanLoaderLink(lib: File) {
        val system = File("/system/lib64/libvulkan.so")
        val link = File(lib, "libvulkan.so.1")
        if (!system.isFile || Files.isSymbolicLink(link.toPath()) || link.exists()) return
        runCatching { Files.createSymbolicLink(link.toPath(), system.toPath()) }
            .onFailure { Timber.w(it, "X86_64GuestLibs: no libvulkan.so.1 link") }
    }

    private fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
