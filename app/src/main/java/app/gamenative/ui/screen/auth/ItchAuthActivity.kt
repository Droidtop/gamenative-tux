package app.gamenative.ui.screen.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextFieldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.R
import app.gamenative.service.itch.ItchAuthManager
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * itch.io API Key Entry Activity.
 *
 * Unlike GOG/Epic/Amazon, itch.io uses a simple personal API key (Bearer token)
 * instead of OAuth. The user generates their own key at https://itch.io/user/settings
 * under "API Keys" and pastes it here. We verify the key against /profile and store
 * it locally on success.
 */
class ItchAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_ERROR = "error"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PluviaTheme {
                ItchAuthScreen(
                    onDismiss = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onKeySubmitted = { apiKey ->
                        val resultIntent = Intent().apply { putExtra(EXTRA_API_KEY, apiKey) }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ItchAuthScreen(
    onDismiss: () -> Unit,
    onKeySubmitted: (String) -> Unit
) {
    val context = LocalContext.current
    val apiKey by remember { mutableStateOf("") }
    val isLoading by remember { mutableStateOf(false) }
    val errorMessage by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentSize(Alignment.End),
            contentDescription = stringResource(R.string.cancel)
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.itch_settings_login_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )

            Text(
                text = stringResource(R.string.itch_settings_login_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.TextAlign.Center
            )

            Text(
                text = stringResource(R.string.itch_api_key_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.TextAlign.Center
            )
        }

        androidx.compose.material3.TextField(
            value = TextFieldValue(text = apiKey),
            onValueChange = { apiKey = it.text },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.itch_enter_api_key_hint)) },
            leadingIcon = {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (apiKey.isNotEmpty()) {
                    IconButton(
                        onClick = { apiKey = "" },
                        modifier = Modifier.size(40.dp),
                        contentDescription = stringResource(R.string.clear_search)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.clear_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
            visualTransformation = PasswordVisualTransformation(),
            isError = errorMessage != null,
            supportingText = {
                if (errorMessage != null) {
                    Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            },
            colors = TextFieldDefaults.textFieldColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        )

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Button(
                onClick = {
                    val trimmed = apiKey.trim()
                    if (trimmed.isEmpty()) {
                        errorMessage = "Please enter your API key"
                        return@Button
                    }
                    errorMessage = null
                    focusRequester.requestFocus()
                    verifyAndSubmit(trimmed)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = apiKey.isNotBlank() && !isLoading,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(
                    text = stringResource(R.string.continue_action),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
        }
    }

    private fun verifyAndSubmit(apiKey: String) {
        isLoading = true
        CoroutineScope(Dispatchers.IO).launch {
            val result = ItchAuthManager.signIn(context, apiKey)
            withContext(Dispatchers.Main) {
                isLoading = false
                result.onSuccess { username ->
                    Timber.i("itch.io auth successful for user: $username")
                    onKeySubmitted(apiKey)
                }.onFailure { error ->
                    errorMessage = error.message ?: "Authentication failed"
                    Timber.e(error, "itch.io auth failed")
                }
            }
        }
    }
}