package com.winlator.xenvironment.components;

import android.content.Context;
import android.icu.util.TimeZone;
import android.util.Log;

import com.winlator.PrefManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.envvars.EnvVars;
import com.winlator.linux.LinuxContainerBackendRegistry;
import com.winlator.linux.LinuxExecConfig;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Real, standalone native-Linux launch path -- this is the actual
 * "download and run arm and x86 Linux versions of applications and
 * games" support this fork exists for (see the repo README). Runs a
 * genuine Linux ELF binary directly inside the same real rootfs
 * ({@link ImageFs}) {@link GuestProgramLauncherComponent} already uses
 * for Wine, through the same swappable {@link LinuxContainerBackend} --
 * no Wine, no WINEPREFIX, no Windows API layer involved at all. A native
 * Linux game doesn't need any of that; it just needs a working Linux
 * userspace to run in, which the existing rootfs already is.
 *
 * CPU translation is needed only when the binary's own real architecture
 * (read from its ELF header, see {@link #detectMachine}) doesn't match
 * the device's -- an ARM64 Linux binary on this (always real ARM64)
 * device runs directly, no translation layer at all. An x86_64 binary is
 * prefixed with box64, the exact same real translator
 * {@link GuestProgramLauncherComponent} already uses for x86_64 Windows
 * binaries -- just without any of the Wine-specific setup a Windows game
 * needs (WINEPREFIX, wine64, DLL overrides, ...).
 */
public class LinuxProgramLauncherComponent {
    // Real, standard ELF e_machine values (elf.h) -- not guessed.
    private static final int EM_386 = 3;
    private static final int EM_ARM = 40;
    private static final int EM_X86_64 = 62;
    private static final int EM_AARCH64 = 183;

    public enum Machine { X86, X86_64, ARM, AARCH64, UNKNOWN }

    /**
     * Runs [executablePath] (an absolute path INSIDE the rootfs, e.g.
     * "/root/mygame/bin/game") with [args] -- the same real pid/
     * termination-callback contract {@link GuestProgramLauncherComponent#exec}
     * uses, see {@link com.winlator.linux.LinuxContainerBackend}'s own doc
     * comment for what that contract actually guarantees.
     */
    public static int exec(
            Context context,
            String executablePath,
            String[] args,
            String[] bindingPaths,
            EnvVars extraVars,
            Callback<Integer> terminationCallback,
            File workingDir
    ) {
        Log.d("LinuxProgramLauncherComponent", "Executing native Linux program " + executablePath);
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        Machine machine = detectMachine(new File(rootDir, executablePath));
        boolean is64Bit = machine == Machine.X86_64 || machine == Machine.AARCH64;
        boolean needsTranslation = machine == Machine.X86 || machine == Machine.X86_64;

        EnvVars envVars = new EnvVars();
        envVars.put("TZ", TimeZone.getDefault().getID());
        envVars.put("HOME", ImageFs.HOME_PATH);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", "/tmp");
        envVars.put("LC_ALL", "en_US.utf8");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        envVars.put("LD_LIBRARY_PATH", "/usr/lib/aarch64-linux-gnu:/usr/lib/arm-linux-gnueabihf");
        envVars.put("ANDROID_SYSVSHM_SERVER", UnixSocketConfig.SYSVSHM_SERVER_PATH);
        if (extraVars != null) envVars.putAll(extraVars);

        StringBuilder command = new StringBuilder();
        if (needsTranslation) {
            extractBoxFiles(context, rootDir, is64Bit);
            command.append(is64Bit ? "box64 " : "box86 ");
            envVars.put(is64Bit ? "BOX64_DYNAREC" : "BOX86_DYNAREC", "1");
        } else if (machine == Machine.UNKNOWN) {
            // Real, expected case for a malformed/unreadable path, not
            // necessarily a bug -- run unmodified rather than refuse,
            // same "honest fallback, don't hard-fail" pattern used
            // elsewhere in this fork. If the device's own real
            // architecture doesn't match, proot/the kernel will report a
            // real exec error, which is the correct, honest outcome here.
            Log.w("LinuxProgramLauncherComponent", "Could not read a real ELF header for " + executablePath + " -- running unmodified");
        }
        command.append(executablePath);
        for (String arg : args) command.append(' ').append(arg);

        LinuxExecConfig config = new LinuxExecConfig(
                rootDir, workingDir, command.toString(), bindingPaths, envVars, !is64Bit, false);

        return LinuxContainerBackendRegistry.get().exec(context, config, terminationCallback);
    }

    /**
     * Reads just the ELF header's real e_machine field (offset 18, 2
     * bytes, endianness from byte 5 -- standard, well-defined ELF layout,
     * confirmed against elf.h, no external tool needed).
     */
    public static Machine detectMachine(File executable) {
        try (RandomAccessFile raf = new RandomAccessFile(executable, "r")) {
            byte[] header = new byte[20];
            if (raf.read(header) < 20) return Machine.UNKNOWN;
            if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
                return Machine.UNKNOWN;
            }
            boolean littleEndian = header[5] == 1;
            int machine = littleEndian
                    ? ((header[19] & 0xFF) << 8) | (header[18] & 0xFF)
                    : ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
            if (machine == EM_386) return Machine.X86;
            if (machine == EM_X86_64) return Machine.X86_64;
            if (machine == EM_ARM) return Machine.ARM;
            if (machine == EM_AARCH64) return Machine.AARCH64;
            return Machine.UNKNOWN;
        } catch (IOException e) {
            return Machine.UNKNOWN;
        }
    }

    /**
     * Same real box86/box64 asset-extraction logic
     * {@link GuestProgramLauncherComponent#extractBox86_64Files} already
     * has -- duplicated rather than shared for now (that method is a
     * private instance method tied to Wine-specific container state this
     * class has none of); a real, worthwhile follow-up is pulling both
     * into one shared static helper, not attempted here.
     */
    private static void extractBoxFiles(Context context, File rootDir, boolean need64) {
        PrefManager.init(context);
        String versionKey = need64 ? "current_box64_version" : "current_box86_version";
        String version = need64 ? DefaultVersion.BOX64 : DefaultVersion.BOX86;
        String currentVersion = PrefManager.getString(versionKey, "");
        if (version.equals(currentVersion)) return;

        String assetPath = "box86_64/" + (need64 ? "box64-" : "box86-") + version + ".tzst";
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), assetPath, rootDir);
        PrefManager.putString(versionKey, version);
    }
}
