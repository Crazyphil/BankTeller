package it.kapfer.bankteller.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.kapfer.bankteller.ApiClient
import it.kapfer.bankteller.AppViewModel
import it.kapfer.bankteller.Aspssp
import it.kapfer.bankteller.AspspsState
import it.kapfer.bankteller.OnboardingStep
import it.kapfer.bankteller.ProductionFieldOverrides
import it.kapfer.bankteller.createHttpClient

import it.kapfer.bankteller.openUrlInNewTab
import it.kapfer.bankteller.redirectTo
import it.kapfer.bankteller.robotoMonoFamily
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.encodeURLParameter
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.graphics.toComposeImageBitmap

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
            OnboardingStep.BankSelection -> BankSelectionStep(viewModel)
            OnboardingStep.LinkingProgress -> LinkingProgressStep(viewModel)
            OnboardingStep.AuthProgress -> AuthProgressStep(viewModel)
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
// ActivationGuideStep (task 7.1, 7.7)
// ---------------------------------------------------------------

/**
 * Explains the two-step bank setup process (account linking in a new tab + session auth in current tab).
 * Provides a primary button to start bank setup, as well as logout and restart options.
 */
@Composable
private fun ActivationGuideStep(viewModel: AppViewModel) {
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
                text = "Complete Bank Setup",
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Bank setup requires a two-step authorization process with your bank:\n\n" +
                        "1. Account linking — connects Enable Banking to your financial institution in a new browser tab.\n" +
                        "2. Session authorization — grants active session permissions in this tab.\n\n" +
                        "Both steps are required to provide free access to your accounts.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.startBankSetup() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start Bank Setup")
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = { viewModel.logout() }) {
                Text("Logout")
            }

            Spacer(modifier = Modifier.height(4.dp))

            TextButton(onClick = { viewModel.resetOnboarding() }) {
                Text("Restart onboarding")
            }
        }
    }
}

// ---------------------------------------------------------------
// Country Name Mapping & Avatar Helpers
// ---------------------------------------------------------------

private val countryCodeToNameMap = mapOf(
    "AT" to "Austria",
    "BE" to "Belgium",
    "BG" to "Bulgaria",
    "CY" to "Cyprus",
    "CZ" to "Czechia",
    "DE" to "Germany",
    "DK" to "Denmark",
    "EE" to "Estonia",
    "ES" to "Spain",
    "FI" to "Finland",
    "FR" to "France",
    "GR" to "Greece",
    "HR" to "Croatia",
    "HU" to "Hungary",
    "IE" to "Ireland",
    "IT" to "Italy",
    "LT" to "Lithuania",
    "LU" to "Luxembourg",
    "LV" to "Latvia",
    "MT" to "Malta",
    "NL" to "Netherlands",
    "PL" to "Poland",
    "PT" to "Portugal",
    "RO" to "Romania",
    "SE" to "Sweden",
    "SI" to "Slovenia",
    "SK" to "Slovakia",
    "NO" to "Norway",
    "IS" to "Iceland",
    "LI" to "Liechtenstein",
    "CH" to "Switzerland",
    "GB" to "United Kingdom",
)

private val avatarBackgroundColors = listOf(
    Color(0xFF6366F1), // Indigo
    Color(0xFF4F46E5), // Darker Indigo
    Color(0xFF10B981), // Emerald
    Color(0xFF0D9488), // Teal
    Color(0xFF0284C7), // Sky Blue
    Color(0xFF2563EB), // Royal Blue
    Color(0xFF7C3AED), // Violet
    Color(0xFF9333EA), // Purple
    Color(0xFFC026D3), // Fuchsia
    Color(0xFFDB2777), // Pink
    Color(0xFFD97706), // Amber
    Color(0xFFEA580C), // Orange
)

/**
 * Deterministic background color from BIC hash or bank name.
 */
private fun getAvatarBackgroundColor(key: String): Color {
    val hash = key.hashCode()
    val index = (hash and 0x7FFFFFFF) % avatarBackgroundColors.size
    return avatarBackgroundColors[index]
}

/**
 * Fallback Provider Avatar rendering a 2-letter badge with a deterministic color.
 */
@Composable
private fun BankAvatar(bankName: String, bic: String?, modifier: Modifier = Modifier) {
    val initials = bankName.trim().take(2).uppercase()
    val bgKey = bic ?: bankName
    val bgColor = remember(bgKey) { getAvatarBackgroundColor(bgKey) }

    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            ),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Module-level cache for loaded bank logo ImageBitmaps, keyed by logo URL.
 * Survives recomposition; cleared on page reload.
 */
private val bankLogoCache = mutableMapOf<String, ImageBitmap>()
private val bankLogoHttpClient by lazy { createHttpClient() }

/**
 * BankLogo: loads the remote logo PNG via ktor + Skia, falls back to BankAvatar
 * (two-letter initials avatar) on null logo, load failure, or while loading.
 */
@Composable
private fun BankLogo(bank: Aspssp, modifier: Modifier = Modifier) {
    val logoUrl = bank.logo
    var bitmap by remember(logoUrl) {
        mutableStateOf(bankLogoCache[logoUrl])
    }
    var loadFailed by remember(logoUrl) { mutableStateOf(logoUrl == null) }

    // Kick off async load if not yet cached and not previously failed.
    // Fetches the logo through the server-side /api/logo endpoint which scales
    // the image with high-quality bilinear resampling, avoiding Skia's
    // nearest-neighbor downscaling on wasmJs (FilterQuality is ignored —
    // SamplingMode.DEFAULT = NEAREST is always used).
    LaunchedEffect(logoUrl) {
        if (logoUrl != null && bitmap == null && !loadFailed) {
            try {
                val scaledUrl = "/api/logo?url=" + logoUrl.encodeURLParameter() + "&size=64"
                val bytes = bankLogoHttpClient.get(scaledUrl).readRawBytes()
                val loaded = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                bankLogoCache[logoUrl] = loaded
                bitmap = loaded
            } catch (e: Throwable) {
                loadFailed = true
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = "${bank.name} logo",
            modifier = modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    } else {
        BankAvatar(bankName = bank.name, bic = bank.bic, modifier = modifier)
    }
}

// ---------------------------------------------------------------
// BankSelectionStep (task 8.1 - 8.5)
// ---------------------------------------------------------------

@Composable
private fun BankSelectionStep(viewModel: AppViewModel) {
    LaunchedEffect(Unit) {
        if (viewModel.aspspsState is AspspsState.Loading) {
            viewModel.loadAspsps()
        }
    }

    var searchQuery by remember { mutableStateOf("") }

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
                text = "Select your bank",
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search by name, BIC, or country") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (val state = viewModel.aspspsState) {
                is AspspsState.Loading -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                }
                is AspspsState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { viewModel.loadAspsps() }) {
                        Text("Retry")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                is AspspsState.Loaded -> {
                    val query = searchQuery.trim().lowercase()
                    val filtered = remember(query, state.aspsps) {
                        if (query.isEmpty()) state.aspsps
                        else state.aspsps.filter { bank ->
                            val countryName = countryCodeToNameMap[bank.country.uppercase()]?.lowercase() ?: ""
                            bank.name.lowercase().contains(query) ||
                            bank.country.lowercase().contains(query) ||
                            countryName.contains(query) ||
                            (bank.bic?.lowercase()?.contains(query) == true)
                        }
                    }

                    if (filtered.isEmpty()) {
                        Text(
                            text = "No banks found matching your search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    } else {
                        Text(
                            text = "${filtered.size} banks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val listState = rememberLazyListState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(
                                    items = filtered,
                                    key = { bank -> "${bank.name}-${bank.country}" },
                                ) { bank ->
                                val isSelected = viewModel.selectedAspsp?.name == bank.name &&
                                        viewModel.selectedAspsp?.country == bank.country
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val defaultPsu = if (bank.psuTypes.contains("personal")) "personal"
                                            else bank.psuTypes.firstOrNull()
                                            viewModel.selectAspsp(bank)
                                            if (defaultPsu != null) {
                                                viewModel.selectPsuType(defaultPsu)
                                            }
                                        },
                                    colors = if (isSelected) {
                                        CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer
                                        )
                                    } else {
                                        CardDefaults.cardColors()
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        BankLogo(bank = bank)

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = bank.name,
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                                    modifier = Modifier.weight(1f, fill = false),
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                val countryName = countryCodeToNameMap[bank.country.uppercase()]
                                                val displayCountry = if (countryName != null) {
                                                    "${bank.country.uppercase()} · $countryName"
                                                } else {
                                                    bank.country.uppercase()
                                                }
                                                Text(
                                                    text = displayCountry,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }

                                            if (bank.bic != null) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "BIC: ${bank.bic}",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = robotoMonoFamily()),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }

                                            if (bank.psuTypes.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    bank.psuTypes.sortedBy { if (it == "personal") 0 else 1 }.forEach { psu ->
                                                        val psuSelected = isSelected && viewModel.selectedPsuType == psu
                                                        FilterChip(
                                                            selected = psuSelected,
                                                            onClick = {
                                                                viewModel.selectAspsp(bank)
                                                                viewModel.selectPsuType(psu)
                                                            },
                                                            label = {
                                                                Text(
                                                                    text = psu.capitalizeFirstLetter(),
                                                                    color = if (psuSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                                    fontWeight = if (psuSelected) FontWeight.Bold else FontWeight.Normal,
                                                                )
                                                            },
                                                            colors = FilterChipDefaults.filterChipColors(
                                                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                                                containerColor = MaterialTheme.colorScheme.surface,
                                                                labelColor = MaterialTheme.colorScheme.onSurface,
                                                            ),
                                                            border = FilterChipDefaults.filterChipBorder(
                                                                enabled = true,
                                                                selected = psuSelected,
                                                                borderColor = MaterialTheme.colorScheme.outline,
                                                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                                                            ),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            }
                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(listState),
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .align(Alignment.TopEnd),
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            viewModel.linkError?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            val selected = viewModel.selectedAspsp
            Button(
                onClick = { viewModel.linkAccounts() },
                enabled = selected != null && !viewModel.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (selected != null) "Connect ${selected.name}" else "Select a bank")
            }
        }
    }
}

// ---------------------------------------------------------------
// LinkingProgressStep (task 9.1 - 9.6)
// ---------------------------------------------------------------

@Composable
private fun LinkingProgressStep(viewModel: AppViewModel) {
    val isResumeMode = viewModel.selectedBankFromState != null || viewModel.selectedAspsp == null

    val linkUrl = viewModel.linkAuthorizationUrl

    // In resume mode, auto-open the tab when relinkAccount() sets a new URL.
    // In fresh mode, the user clicks "Open linking page" manually.
    LaunchedEffect(linkUrl, isResumeMode) {
        if (linkUrl != null && isResumeMode) {
            openUrlInNewTab(linkUrl)
            viewModel.consumeLinkAuthorizationUrl()
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
                text = "Complete account linking",
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isResumeMode) {
                    "Account linking was started in a previous session. " +
                            "If you've completed linking in the Enable Banking control panel, " +
                            "click below to continue to authorization."
                } else {
                    "You'll be redirected to Enable Banking's control panel to link your account. " +
                            "This verifies your identity so BankTeller can securely access your bank accounts."
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!isResumeMode) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "After completing the linking process, close that tab and return here to continue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            viewModel.authError?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            viewModel.linkError?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (viewModel.linkStatusChecking) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (isResumeMode) {
                Button(
                    onClick = { viewModel.checkLinkStatus() },
                    enabled = !viewModel.linkStatusChecking && !viewModel.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue to authorization")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "If you haven't completed linking yet, you can re-open the linking tab.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.relinkAccount() },
                    enabled = !viewModel.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Re-open linking tab")
                }
            } else {
                if (linkUrl != null) {
                    Button(
                        onClick = {
                            openUrlInNewTab(linkUrl)
                            viewModel.consumeLinkAuthorizationUrl()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open linking page")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Click the button above to open the Enable Banking control panel in a new tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { viewModel.checkLinkStatus() },
                        enabled = !viewModel.linkStatusChecking && !viewModel.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("I've completed linking, authorize now")
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.checkLinkStatus()
                        },
                        enabled = !viewModel.linkStatusChecking && !viewModel.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("I've completed linking, authorize now")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { viewModel.cancelLinking() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back to bank list")
            }
        }
    }
}

// ---------------------------------------------------------------
// AuthProgressStep (task 10.1 - 10.4)
// ---------------------------------------------------------------

@Composable
private fun AuthProgressStep(viewModel: AppViewModel) {
    val authRedirectUrl = viewModel.authRedirectUrl

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
                text = "Authorizing session",
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "You'll be redirected to your bank's secure page to grant account access. " +
                        "Here's what to expect:",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Your bank's consent page",
                        style = MaterialTheme.typography.labelLarge,
                    )

                    HorizontalDivider()

                    Text(
                        text = "Make sure all three access categories are checked:",
                        style = MaterialTheme.typography.labelSmall,
                    )

                    ConsentItem("Accounts", "Access to your account information")
                    ConsentItem("Account balances", "Access to view your balances")
                    ConsentItem("Transactions", "Access to your transaction history")

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Then select the accounts you want to connect and click \"Grant authorization\".",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "After approving, you'll be redirected back here automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (authRedirectUrl != null) {
                Button(
                    onClick = {
                        redirectTo(authRedirectUrl)
                        viewModel.consumeAuthRedirectUrl()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue to your bank")
                }
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun ConsentItem(title: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "-",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun String.capitalizeFirstLetter(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

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
