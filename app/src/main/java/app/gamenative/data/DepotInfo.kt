package app.gamenative.data

import app.gamenative.db.serializers.OsEnumSetSerializer
import app.gamenative.db.serializers.SteamRealmSerializer
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.enums.SteamRealm
import app.gamenative.service.SteamService
import java.util.EnumSet
import kotlinx.serialization.Serializable

@Serializable
data class DepotInfo(
    val depotId: Int,
    val dlcAppId: Int,
    val optionalDlcId: Int = SteamService.INVALID_APP_ID,
    val depotFromApp: Int,
    val sharedInstall: Boolean,
    @Serializable(with = OsEnumSetSerializer::class)
    val osList: EnumSet<OS>,
    val osArch: OSArch,
    val manifests: Map<String, ManifestInfo>,
    val encryptedManifests: Map<String, ManifestInfo>,
    val language: String = "",
    @Serializable(with = SteamRealmSerializer::class)
    val realm: SteamRealm = SteamRealm.Unknown,
    val systemDefined: Boolean = false,
    val steamDeck: Boolean = false,
) {
    /** Windows or OS-untagged (neither Linux nor macOS) */
    val isWindowsCompatible: Boolean
        get() = osList.contains(OS.windows) ||
            (!osList.contains(OS.linux) && !osList.contains(OS.macos))

    /**
     * Real Linux depot, explicitly tagged -- unlike [isWindowsCompatible]
     * this is never true by omission, since an OS-untagged depot means
     * "assume Windows" (Steam's own real convention), not "assume Linux."
     * A genuinely native Linux depot needs no Wine/box64 translation for
     * an ARM64-native binary at all -- see `LinuxProgramLauncherComponent`
     * (`com.winlator.xenvironment.components`), the real launch path this
     * fork exists for (see the repo README).
     */
    val isLinuxCompatible: Boolean
        get() = osList.contains(OS.linux)
}
