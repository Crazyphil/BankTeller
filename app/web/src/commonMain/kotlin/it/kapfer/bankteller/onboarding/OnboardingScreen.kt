package it.kapfer.bankteller.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.AppViewModel
import it.kapfer.bankteller.OnboardingStep
import it.kapfer.bankteller.ProductionFieldOverrides
import it.kapfer.bankteller.openUrlInNewTab
import it.kapfer.bankteller.robotoMonoFamily

/**
 * Root composable for the Enable Banking onboarding flow.
 *
 * Dispatches to the appropriate step composable based on [viewModel.onboardingStep].
 * Tasks 7.3 – 7.7.
 */
@Composable
fun OnboardingScreen(viewModel: AppViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        when (viewModel.onboardingStep) {
            OnboardingStep.EmailEntry -> EmailEntryStep(viewModel)
            OnboardingStep.WaitingForAuthentication -> WaitingStep(viewModel)
            OnboardingStep.RegistrationReview -> RegistrationReviewStep(viewModel)
            OnboardingStep.Verifying -> VerifyingStep(viewModel)
            OnboardingStep.ActivationGuide -> ActivationGuideStep(viewModel)
        }
    }
}

// ---------------------------------------------------------------
// EmailEntryStep (task 7.4)
// ---------------------------------------------------------------

/**
 * First onboarding step: collect the user's Enable Banking email.
 * Fetches the server-derived redirect URL for informational display.
 */
@Composable
private fun EmailEntryStep(viewModel: AppViewModel) {
    // Fetch the derived redirect URL on first composition
    LaunchedEffect(Unit) {
        viewModel.loadDerivedRedirectUrl()
    }

    val emailFocusRequester = remember { FocusRequester() }
    // Auto-focus the email field so the user can start typing immediately
    LaunchedEffect(Unit) {
        emailFocusRequester.requestFocus()
    }

    var email by remember { mutableStateOf("") }
    var emailError by remember { mutableStateOf<String?>(null) }

    val submit = {
        if (email.isBlank() || !email.contains("@")) {
            emailError = "Please enter a valid email address"
        } else {
            viewModel.startOnboarding(email.trim())
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Enable Banking setup",
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Enter your Enable Banking email to begin. BankTeller will send a login link to that address.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    emailError = null
                },
                label = { Text("Email") },
                singleLine = true,
                isError = emailError != null,
                supportingText = emailError?.let { err -> { Text(err) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(emailFocusRequester),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Display the derived redirect URL as informational text
            val derivedUrl = viewModel.onboardingDerivedRedirectUrl
            if (derivedUrl != null) {
                Text(
                    text = buildAnnotatedString {
                        append("BankTeller will use ")
                        withStyle(SpanStyle(fontFamily = robotoMonoFamily())) {
                            append(derivedUrl)
                        }
                        append(" for the email-link redirect — fix your reverse proxy config if this looks wrong.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Loading redirect URL…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send login email")
            }

            // Show error from server
            viewModel.onboardingError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------
// WaitingStep (task 7.5)
// ---------------------------------------------------------------

/**
 * Waiting-for-authentication step: tells the user to check their email
 * and shows a loading indicator while polling for completion.
 */
@Composable
private fun WaitingStep(viewModel: AppViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Check your email",
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = buildAnnotatedString {
                append("We sent a login email to ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(viewModel.onboardingEmail)
                }
                append(". Click the link in that email to continue. You can click the link on another device — this tab will auto-advance.")
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(16.dp))

        val derivedUrl = viewModel.onboardingDerivedRedirectUrl
        if (derivedUrl != null) {
            Text(
                text = buildAnnotatedString {
                    append("Redirect URL in use: ")
                    withStyle(SpanStyle(fontFamily = robotoMonoFamily())) {
                        append(derivedUrl)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "If the email doesn't arrive: check the address for typos and your spam " +
                        "folder. Delivery through Google Identity Toolkit can take a minute or two.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "If the email arrives but clicking the link shows a browser error: check " +
                        "that the redirect URL above is reachable from your browser (a " +
                        "misconfigured reverse proxy is the usual cause).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        CircularProgressIndicator()

        Spacer(modifier = Modifier.height(24.dp))

        TextButton(onClick = { viewModel.cancelOnboarding() }) {
            Text("Start over")
        }
    }
}

// ---------------------------------------------------------------
// RegistrationReviewStep (task 7.5a, D13)
// ---------------------------------------------------------------

/**
 * Registration review step: environment selection, redirect URL (editable),
 * and production field overrides.
 */
@Composable
private fun RegistrationReviewStep(viewModel: AppViewModel) {
    // Refresh the derived redirect URL on first composition
    LaunchedEffect(Unit) {
        viewModel.loadDerivedRedirectUrl()
    }

    val derivedUrl = viewModel.onboardingDerivedRedirectUrl
    val host = remember(derivedUrl) {
        if (derivedUrl != null) deriveHost(derivedUrl) else ""
    }

    var environment by remember { mutableStateOf("PRODUCTION") }
    var redirectUrl by remember(derivedUrl) { mutableStateOf(derivedUrl ?: "") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("BankTeller") }
    var gdprEmail by remember { mutableStateOf(viewModel.onboardingEmail) }
    var privacyUrl by remember(host) { mutableStateOf(if (host.isNotEmpty()) "$host/privacy" else "") }
    var termsUrl by remember(host) { mutableStateOf(if (host.isNotEmpty()) "$host/terms" else "") }

    val submit = {
        if (!redirectUrl.startsWith("http://") && !redirectUrl.startsWith("https://")) {
            urlError = "Redirect URL must start with http:// or https://"
        } else {
            urlError = null
            val overrides = if (environment == "PRODUCTION") {
                ProductionFieldOverrides(description, gdprEmail, privacyUrl, termsUrl)
            } else {
                null
            }
            viewModel.submitRegistration(environment, redirectUrl, overrides)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Review registration",
                style = MaterialTheme.typography.headlineSmall,
            )

            // Show retryable error banner so the user knows what to fix
            viewModel.onboardingError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Environment radio group ---
            Text(
                text = "Environment",
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(modifier = Modifier.height(8.dp))

            EnvironmentOption(
                label = "PRODUCTION",
                description = "For accessing your real bank accounts. After registration, you will " +
                        "need to complete one manual step on the Enable Banking control panel: link " +
                        "at least one of your accounts to activate the app for production use. " +
                        "Production apps cannot be transferred to sandbox — you would need to re-run " +
                        "onboarding to switch environments.",
                selected = environment == "PRODUCTION",
                onClick = { environment = "PRODUCTION" },
            )

            Spacer(modifier = Modifier.height(8.dp))

            EnvironmentOption(
                label = "SANDBOX",
                description = "For testing against simulated banks with test data. Auto-activated, " +
                        "no manual step required. Sandbox apps cannot be transferred to production — " +
                        "you would need to re-run onboarding with the PRODUCTION environment to " +
                        "access real accounts.",
                selected = environment == "SANDBOX",
                onClick = { environment = "SANDBOX" },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // --- Redirect URL ---
            OutlinedTextField(
                value = redirectUrl,
                onValueChange = {
                    redirectUrl = it
                    urlError = null
                    viewModel.clearOnboardingError()
                },
                label = { Text("Redirect URL") },
                singleLine = true,
                isError = urlError != null,
                supportingText = urlError?.let { err -> { Text(err) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            // --- Production field overrides (only shown for PRODUCTION) ---
            if (environment == "PRODUCTION") {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Production field overrides",
                    style = MaterialTheme.typography.titleSmall,
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it
                        viewModel.clearOnboardingError()
                    },
                    label = { Text("Description") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = gdprEmail,
                    onValueChange = {
                        gdprEmail = it
                        viewModel.clearOnboardingError()
                    },
                    label = { Text("GDPR email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = privacyUrl,
                    onValueChange = {
                        privacyUrl = it
                        viewModel.clearOnboardingError()
                    },
                    label = { Text("Privacy URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = termsUrl,
                    onValueChange = {
                        termsUrl = it
                        viewModel.clearOnboardingError()
                    },
                    label = { Text("Terms URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Submit button ---
            Button(
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Register")
            }
        }
    }
}

// ---------------------------------------------------------------
// VerifyingStep (task 7.6)
// ---------------------------------------------------------------

/**
 * Pending state while the completion call is in-flight.
 * Shows an error + restart action if [viewModel.onboardingError] is non-null.
 */
@Composable
private fun VerifyingStep(viewModel: AppViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Verifying…",
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Registering your Enable Banking application. This may take a few seconds.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (viewModel.onboardingError == null) {
            CircularProgressIndicator()
        }

        viewModel.onboardingError?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(onClick = { viewModel.cancelOnboarding() }) {
                Text("Restart onboarding")
            }
        }
    }
}

// ---------------------------------------------------------------
// ActivationGuideStep (task 7.7)
// ---------------------------------------------------------------

/**
 * Shown when the EB app is registered but not yet active.
 * Provides instructions for activating the app on the Enable Banking control panel.
 */
@Composable
private fun ActivationGuideStep(viewModel: AppViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Almost there!",
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Your Enable Banking application has been registered, but it is not yet active. " +
                    "To activate it for production use, you need to link at least one of your " +
                    "accounts on the Enable Banking control panel.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Open this URL in a new tab to link your account(s):",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(4.dp))

        val controlPanelUrl = "https://enablebanking.com/cp/applications"
        Text(
            text = controlPanelUrl,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { openUrlInNewTab(controlPanelUrl) },
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "For personal-use registrations, linking an account both activates the app " +
                    "and grants that account API access. The onboarding gate will close " +
                    "automatically on your next login once the app is active.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = { viewModel.logout() }) {
            Text("Logout")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = { viewModel.resetOnboarding() }) {
            Text("Restart onboarding")
        }
    }
}

// ---------------------------------------------------------------
// Shared composables & helpers
// ---------------------------------------------------------------

/**
 * A single environment radio option with a label and description.
 */
@Composable
private fun EnvironmentOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Extract the scheme + host portion from a URL (strip the path).
 * Falls back to the raw URL if parsing fails.
 */
private fun deriveHost(url: String): String {
    return try {
        val protocol = if (url.startsWith("https://")) "https://"
        else if (url.startsWith("http://")) "http://"
        else return url

        val afterProtocol = url.removePrefix(protocol)
        val slashIndex = afterProtocol.indexOf('/')
        val host = if (slashIndex >= 0) afterProtocol.substring(0, slashIndex) else afterProtocol
        "$protocol$host"
    } catch (_: Exception) {
        url
    }
}
