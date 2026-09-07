package it.kapfer.bankteller

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import it.kapfer.bankteller.ui.components.BrandLogo
import it.kapfer.bankteller.ui.components.ScreenShell
import it.kapfer.bankteller.ui.components.ThemeToggleOverlay
import it.kapfer.bankteller.ui.theme.Dimens

/**
 * Login screen with username / password fields, a submit button, error display,
 * and rate-limit message.
 *
 * Corresponds to tasks 6.2 and 6.3 of the foundation spec.
 */
@Composable
fun LoginScreen(viewModel: AppViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val usernameFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    // Auto-focus the username field so the user can start typing immediately
    LaunchedEffect(Unit) {
        usernameFocusRequester.requestFocus()
    }

    // Viewport-fixed theme toggle in the top-right corner (task 9.1a): reachable
    // before authentication, overlays content, safe-area aware.
    ThemeToggleOverlay {
        ScreenShell(
            maxWidth = Dimens.formMaxWidth,
            verticalArrangement = Arrangement.Center,
        ) {
            // Brand lockup (task 16.4): 48dp theme-aware logo + wordmark in Fraunces headlineMedium.
            BrandLogo(size = Dimens.xxl)

            Spacer(modifier = Modifier.height(Dimens.lg))

            Text(
                text = "BankTeller",
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(modifier = Modifier.height(Dimens.xl))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(usernameFocusRequester),
            )

            Spacer(modifier = Modifier.height(Dimens.sm))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.login(username, password) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocusRequester),
            )

            Spacer(modifier = Modifier.height(Dimens.md))

            if (viewModel.isLoading) {
                CircularProgressIndicator()
            } else {
                Button(
                    onClick = { viewModel.login(username, password) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Login")
                }
            }

            // Non-rate-limited error
            viewModel.loginError?.let { error ->
                Spacer(modifier = Modifier.height(Dimens.sm))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Rate-limit message
            if (viewModel.isRateLimited) {
                Spacer(modifier = Modifier.height(Dimens.sm))
                Text(
                    text = "Too many login attempts. Please wait before retrying.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}