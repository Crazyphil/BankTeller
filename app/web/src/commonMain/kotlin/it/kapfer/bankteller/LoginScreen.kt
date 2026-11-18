package it.kapfer.bankteller

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.ui.components.ActionButton
import it.kapfer.bankteller.ui.components.BrandLogo
import it.kapfer.bankteller.ui.components.DecisionBox
import it.kapfer.bankteller.ui.components.ScreenShell
import it.kapfer.bankteller.ui.components.ThemeToggleOverlay
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Login screen with username / password fields, a submit button, error display,
 * and rate-limit message.
 *
 * Corresponds to tasks 6.2 and 6.3 of the foundation spec.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
            // Explicit (task 8.21): bespoke centered presentation — a scroll
            // zone would break bounded-height centering.
            scrollable = false,
        ) {
            // Brand lockup (task 16.4): 48dp theme-aware logo + wordmark in Fraunces headlineMedium.
            BrandLogo(size = Dimens.xxl)

            Spacer(modifier = Modifier.height(Dimens.lg))

            Text(
                text = "BankTeller",
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(modifier = Modifier.height(Dimens.xl))

            // Session-expiry panel (spec: session-management — session-timeout
            // feedback; spa-shell — login screen): a one-shot, dismissible
            // brass DecisionBox (Tier 3 — the remove-caveat panel's grammar:
            // leading 18dp outlined WarningAmber glyph, trailing dismiss
            // affordance). Rendered only here, between the brand lockup and
            // the credentials form; latched by the login gate when ANY
            // observed 401 carried `reason: session_expired`. Never rendered
            // after a deliberate logout, on a fresh visit, after dismissal,
            // or after a re-login.
            viewModel.sessionExpiredTtlMinutes?.let { ttl ->
                val brass = LocalBankTellerColors.current.brass
                // Calm fade-in (task 2.5): the panel materializes gently rather
                // than appearing abruptly alongside the form.
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                androidx.compose.animation.AnimatedVisibility(
                    visible = visible,
                    enter = androidx.compose.animation.fadeIn(),
                ) {
                    DecisionBox(tint = brass, borderColor = brass, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = brass,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Your session expired after $ttl ${if (ttl == 1L) "minute" else "minutes"} of inactivity. Please sign in again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        // Dismiss affordance: the §6.1.1 icon-action tier —
                        // 28dp footprint, indication-free, tooltip names the
                        // action (the §6.1.2 utility-control convention).
                        val dismissInteraction = remember { MutableInteractionSource() }
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                            tooltip = { PlainTooltip { Text("Dismiss") } },
                            state = rememberTooltipState(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable(
                                        interactionSource = dismissInteraction,
                                        indication = null,
                                    ) { viewModel.dismissSessionExpiredPanel() }
                                    .padding(6.dp),
                            )
                        }
                    }
                    }
                }
                Spacer(modifier = Modifier.height(Dimens.md))
            }

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

            // Stable submit button (design D6): stays mounted at full footprint
            // while the request is in flight — disabled via LocalActionBusy
            // instead of being replaced by a standalone spinner, so the form
            // does not jump during submission.
            ActionButton(
                onClick = { viewModel.login(username, password) },
                label = "Login",
                modifier = Modifier.fillMaxWidth(),
            )

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