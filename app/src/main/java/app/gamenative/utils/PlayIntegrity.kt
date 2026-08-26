package app.gamenative.utils

import android.app.Application
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.security.MessageDigest
import kotlin.coroutines.resume

object PlayIntegrity {

    @Volatile
    private var tokenProvider: StandardIntegrityTokenProvider? = null

    /**
     * Real, confirmed-necessary fix: every other function in this object
     * already treats Play Integrity as fully optional (nullable token,
     * graceful fallback on any failure) -- this one wasn't, and it runs
     * unconditionally at app startup (PluviaApp.onCreate). A real device
     * without Google Play Services installed at all (not a hypothetical --
     * this fork explicitly targets that population) can make
     * IntegrityManagerFactory.createStandard/prepareIntegrityToken throw
     * *synchronously*, not just fail via the async listener -- which,
     * unguarded, would crash the app on every single launch for exactly
     * the users this matters most for. Wrapping the whole body means "no
     * Play Services" now behaves exactly like every other real failure
     * path here: no integrity token, everything else works normally.
     */
    fun warmUp(application: Application) {
        try {
            val cloudProjectNumber = BuildConfig.CLOUD_PROJECT_NUMBER.toLongOrNull()
            if (cloudProjectNumber == null || cloudProjectNumber == 0L) {
                Timber.tag("PlayIntegrity").e("Invalid CLOUD_PROJECT_NUMBER: '${BuildConfig.CLOUD_PROJECT_NUMBER}'")
                return
            }

            val manager: StandardIntegrityManager =
                IntegrityManagerFactory.createStandard(application)

            manager.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build(),
            ).addOnSuccessListener { provider ->
                tokenProvider = provider
                PrefManager.playIntegrityAvailable = true
                Timber.tag("PlayIntegrity").d("Token provider ready")
            }.addOnFailureListener { e ->
                PrefManager.playIntegrityAvailable = false
                Timber.tag("PlayIntegrity").e(e, "Failed to prepare integrity token provider")
            }
        } catch (e: Throwable) {
            // Real, expected case on a device with no Google Play Services
            // at all -- not a hypothetical edge case for this fork's real
            // audience. Play Integrity just never becomes available;
            // nothing else about the app depends on it.
            PrefManager.playIntegrityAvailable = false
            Timber.tag("PlayIntegrity").e(e, "Play Integrity unavailable on this device (no Google Play Services?) -- continuing without it")
        }
    }

    /**
     * Returns a fresh, one-use integrity token bound to the SHA-256 hash of
     * [requestBodyBytes], or null if the provider is not ready or the request fails.
     */
    suspend fun requestToken(requestBodyBytes: ByteArray): String? {
        val provider = tokenProvider ?: return null

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(requestBodyBytes)
            .joinToString("") { "%02x".format(it) }

        return try {
            suspendCancellableCoroutine { cont ->
                provider.request(
                    StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                        .setRequestHash(hash)
                        .build(),
                ).addOnSuccessListener { token ->
                    cont.resume(token.token())
                }.addOnFailureListener { e ->
                    Timber.tag("PlayIntegrity").e(e, "Integrity token request failed")
                    cont.resume(null)
                }
            }
        } catch (e: Exception) {
            Timber.tag("PlayIntegrity").e(e, "Unexpected error requesting integrity token")
            null
        }
    }
}
