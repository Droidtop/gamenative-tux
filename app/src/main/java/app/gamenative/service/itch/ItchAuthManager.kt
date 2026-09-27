package app.gamenative.service.itch

import android.content.Context
import app.gamenative.service.itch.api.ItchApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Stores and validates the user's own itch.io personal API key.
 *
 * There is no OAuth flow here on purpose: itch's API key page
 * (https://itch.io/user/settings, "API Keys") is the sanctioned way for a
 * user to hand a client their own credential without a password ever
 * passing through droidtop/gamenative, and it is what droidtop's settings
 * row asks the user to paste in themselves (never entered on the user's
 * behalf -- see docs/SPEC.md's PC stores section).
 */
object ItchAuthManager {
    private const val TAG = "ItchAuthManager"

    fun hasStoredCredentials(context: Context): Boolean =
        File(ItchConstants.getAuthConfigPath(context)).exists()

    fun getStoredApiKey(context: Context): String? {
        val file = File(ItchConstants.getAuthConfigPath(context))
        if (!file.exists()) return null
        return try {
            JSONObject(file.readText()).optString("api_key").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read stored itch.io key")
            null
        }
    }

    /**
     * Validates [apiKey] against itch.io, and stores it only on success --
     * a key that does not work is never written, so
     * [hasStoredCredentials] never lies about being signed in.
     */
    suspend fun signIn(context: Context, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) return@withContext Result.failure(Exception("Enter your itch.io API key."))
        val verified = ItchApiClient.verifyKey(trimmed)
        verified.onSuccess { username ->
            try {
                val file = File(ItchConstants.getAuthConfigPath(context))
                file.parentFile?.mkdirs()
                file.writeText(JSONObject().put("api_key", trimmed).put("username", username).toString(2))
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to persist itch.io key")
                return@withContext Result.failure(e)
            }
        }
        verified
    }

    fun signOut(context: Context): Boolean {
        val file = File(ItchConstants.getAuthConfigPath(context))
        return !file.exists() || file.delete()
    }
}
