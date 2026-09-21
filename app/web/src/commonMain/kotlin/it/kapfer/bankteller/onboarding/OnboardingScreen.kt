package it.kapfer.bankteller.onboarding

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
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
import it.kapfer.bankteller.jetBrainsMonoFamily
import it.kapfer.bankteller.ui.components.ActionButton
import it.kapfer.bankteller.ui.components.QuietActionButton
import it.kapfer.bankteller.ui.components.WaitingIndicator
import it.kapfer.bankteller.ui.components.BrandedTopBar
import it.kapfer.bankteller.ui.components.DecisionBox
import it.kapfer.bankteller.ui.components.QuietButton
import it.kapfer.bankteller.ui.components.ScreenShell
import it.kapfer.bankteller.ui.components.ThemeToggle
import it.kapfer.bankteller.ui.components.WizardProgressIndicator
import it.kapfer.bankteller.ui.components.WizardScaffold
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.encodeURLParameter
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.graphics.toComposeImageBitmap

/**
 * Root composable for the Enable Banking onboarding flow.
 *
 * Dispatches to the appropriate step composable based on [viewModel.onboardingStep].
 * Tasks 7.3 – 7.7. App chrome (task 11.5): a [BrandedTopBar] with the theme toggle
 * as the FIRST actions item, then logout — replacing ad-hoc logout buttons buried
 * in individual wizard steps.
 */
@Composable
fun OnboardingScreen(viewModel: AppViewModel) {
    ScreenShell(
        maxWidth = Dimens.contentMaxWidth,
        scrollable = true,
        topBar = {
            BrandedTopBar(
                actions = {
                    ThemeToggle()
                    TextButton(
                        onClick = { viewModel.logout() },
                    ) {
                        Text("Logout")
                    }
                },
            )
        },
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

    WizardScaffold(
        eyebrow = "STEP 1 OF 8",
        title = "Enable Banking setup",
        oneLiner = "Enter your Enable Banking email to begin. BankTeller will send a login link to that address.",
        progress = { WizardProgressIndicator(currentStep = 0, totalSteps = 8) },
        onBack = null,
        forward = {
            ActionButton(onClick = submit, label = "Send login email")
        },
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                emailError = null
            },
            label = { Text("Email") },
            singleLine = true,
            isError = emailError != null,
            supportingText = emailError?.let { err -> { Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(emailFocusRequester),
        )

        // Display the derived redirect URL as informational text
        val derivedUrl = viewModel.onboardingDerivedRedirectUrl
        if (derivedUrl != null) {
            Text(
                text = buildAnnotatedString {
                    append("BankTeller will use ")
                    withStyle(SpanStyle(fontFamily = jetBrainsMonoFamily())) {
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

        // Show error from server
        viewModel.onboardingError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
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
    WizardScaffold(
        eyebrow = "STEP 2 OF 8",
        title = "Check your email",
        oneLiner = "We sent a login email to ${viewModel.onboardingEmail}. Click the link in that email to continue. You can click the link on another device — this tab will auto-advance.",
        progress = { WizardProgressIndicator(currentStep = 1, totalSteps = 8) },
        onBack = { viewModel.resetOnboarding() },
        backLabel = "Start over",
        backServerAction = true, // POST reset credentials — disable while in flight
        forward = null,
    ) {
        WaitingIndicator.Zone()

        val derivedUrl = viewModel.onboardingDerivedRedirectUrl
        if (derivedUrl != null) {
            Text(
                text = buildAnnotatedString {
                    append("Redirect URL in use: ")
                    withStyle(SpanStyle(fontFamily = jetBrainsMonoFamily())) {
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

    // Registration info arrives asynchronously (gate-routed users). Auto-fill the
    // redirect URL / GDPR email while the user has not edited them; once the user
    // types, their input wins and is never clobbered by a late-arriving value.
    val infoRedirectUrl = viewModel.registrationInfoRedirectUrl
    val infoEmail = viewModel.registrationInfoEmail

    var environment by remember { mutableStateOf("PRODUCTION") }
    var hasEditedRedirectUrl by remember { mutableStateOf(false) }
    var hasEditedGdprEmail by remember { mutableStateOf(false) }
    var redirectUrl by remember {
        mutableStateOf(infoRedirectUrl ?: derivedUrl ?: "")
    }
    var urlError by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("BankTeller") }
    var gdprEmail by remember {
        mutableStateOf(infoEmail ?: viewModel.onboardingEmail)
    }
    var privacyUrl by remember(host) { mutableStateOf(if (host.isNotEmpty()) "$host/privacy" else "") }
    var termsUrl by remember(host) { mutableStateOf(if (host.isNotEmpty()) "$host/terms" else "") }

    // Apply late-arriving registration info (async fetch) unless the user edited.
    LaunchedEffect(infoRedirectUrl, derivedUrl) {
        if (!hasEditedRedirectUrl && !infoRedirectUrl.isNullOrEmpty()) {
            redirectUrl = infoRedirectUrl
        } else if (!hasEditedRedirectUrl && redirectUrl.isEmpty() && !derivedUrl.isNullOrEmpty()) {
            redirectUrl = derivedUrl
        }
    }
    LaunchedEffect(infoEmail) {
        if (!hasEditedGdprEmail && !infoEmail.isNullOrEmpty()) {
            gdprEmail = infoEmail
        }
    }

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

    WizardScaffold(
        eyebrow = "STEP 3 OF 8",
        title = "Review registration",
        oneLiner = "Review the registration details before submitting.",
        progress = { WizardProgressIndicator(currentStep = 2, totalSteps = 8) },
        onBack = { viewModel.resetOnboarding() },
        backLabel = "Back to email",
        backServerAction = true, // POST reset credentials — disable while in flight
        forward = {
            ActionButton(onClick = submit, label = "Register")
        },
    ) {
        // Show retryable error banner so the user knows what to fix
        viewModel.onboardingError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // --- Environment radio group ---
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            Text(
                text = "Environment",
                style = MaterialTheme.typography.titleSmall,
            )

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

            EnvironmentOption(
                label = "SANDBOX",
                description = "For testing against simulated banks with test data. Auto-activated, " +
                        "no manual step required. Sandbox apps cannot be transferred to production — " +
                        "you would need to re-run onboarding with the PRODUCTION environment to " +
                        "access real accounts.",
                selected = environment == "SANDBOX",
                onClick = { environment = "SANDBOX" },
            )
        }

        // --- Redirect URL ---
        OutlinedTextField(
            value = redirectUrl,
            onValueChange = {
                redirectUrl = it
                hasEditedRedirectUrl = true
                urlError = null
                viewModel.clearOnboardingError()
            },
            label = { Text("Redirect URL") },
            singleLine = true,
            isError = urlError != null,
            supportingText = urlError?.let { err -> { Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )

        // --- Production field overrides (only shown for PRODUCTION) ---
        if (environment == "PRODUCTION") {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                Text(
                    text = "Production field overrides",
                    style = MaterialTheme.typography.titleSmall,
                )

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

                OutlinedTextField(
                    value = gdprEmail,
                    onValueChange = {
                        gdprEmail = it
                        hasEditedGdprEmail = true
                        viewModel.clearOnboardingError()
                    },
                    label = { Text("GDPR email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )

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
    WizardScaffold(
        eyebrow = "STEP 4 OF 8",
        title = "Verifying…",
        oneLiner = null,
        progress = { WizardProgressIndicator(currentStep = 3, totalSteps = 8) },
        onBack = null,
        forward = null,
    ) {
        if (viewModel.onboardingError == null) {
            WaitingIndicator.Zone()
        }

        viewModel.onboardingError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

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
 * Provides a primary button to start bank setup and a restart option.
 * Logout lives in the BrandedTopBar (task 11.5) — the back slot is not repurposed.
 */
@Composable
private fun ActivationGuideStep(viewModel: AppViewModel) {
    WizardScaffold(
        eyebrow = "STEP 5 OF 8",
        title = "Complete bank setup",
        oneLiner = "Bank setup requires a two-step authorization process with your bank.",
        progress = { WizardProgressIndicator(currentStep = 4, totalSteps = 8) },
        onBack = null,
        extraActions = {
            QuietActionButton(
                onClick = { viewModel.resetOnboarding() },
                label = "Restart onboarding",
            )
        },
        forward = {
            ActionButton(onClick = { viewModel.startBankSetup() }, label = "Start Bank Setup")
        },
    ) {
        // Two-step explanation as Tier 2 groups
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            Text(
                text = "1. Account linking",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Connects Enable Banking to your financial institution in a new browser tab.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "2. Session authorization",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Grants active session permissions in this tab.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Both steps are required to provide free access to your accounts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@Composable
private fun themedAvatarBackgroundColors(): List<Color> {
    val primary = MaterialTheme.colorScheme.primary
    val brass = LocalBankTellerColors.current.brass
    val emerald = LocalBankTellerColors.current.emerald
    val error = MaterialTheme.colorScheme.error
    val onSurface = MaterialTheme.colorScheme.onSurface
    return listOf(
        primary, brass, emerald, error,
        lerp(primary, brass, 0.5f), lerp(primary, emerald, 0.5f),
        lerp(brass, emerald, 0.5f), lerp(primary, error, 0.5f),
        lerp(primary, onSurface, 0.4f), lerp(brass, onSurface, 0.4f),
        lerp(emerald, onSurface, 0.4f), lerp(error, onSurface, 0.4f),
    )
}

/**
 * Deterministic background color from BIC hash or bank name.
 */
private fun getAvatarBackgroundColor(key: String, palette: List<Color>): Color {
    val hash = key.hashCode()
    val index = (hash and 0x7FFFFFFF) % palette.size
    return palette[index]
}

/**
 * Fallback Provider Avatar rendering a 2-letter badge with a deterministic color.
 */
@Composable
private fun BankAvatar(bankName: String, bic: String?, modifier: Modifier = Modifier) {
    val initials = bankName.trim().take(2).uppercase()
    val bgKey = bic ?: bankName
    val bgColor = getAvatarBackgroundColor(bgKey, themedAvatarBackgroundColors())
    // Relative luminance (Rec. 601) for contrast-safe initials.
    val bgLum = 0.299f * bgColor.red + 0.587f * bgColor.green + 0.114f * bgColor.blue
    val textColor = if (bgLum > 0.5f) Color.Black else Color.White

    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = textColor,
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
                .clip(RoundedCornerShape(Dimens.sm)),
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

    if (viewModel.isResumeMode) {
        // Enrichment state gates the card: while the /api/aspsps fetch is in
        // flight the card waits (logo/BIC may still arrive); if the fetch
        // failed, the card cannot be enriched, so surface the same error +
        // retry affordance as the bank list — the user can retry or pick
        // "Choose a different bank" below, which needs the list anyway.
        when (viewModel.aspspsState) {
            is AspspsState.Loading -> {
                WizardScaffold(
                    eyebrow = "STEP 6 OF 8",
                    title = "Choose your bank",
                    oneLiner = "Confirm your account type to continue.",
                    progress = { WizardProgressIndicator(currentStep = 5, totalSteps = 8) },
                    onBack = null,
                    forward = {},
                ) {
                    WaitingIndicator.Zone()
                }
            }
            is AspspsState.Error -> {
                WizardScaffold(
                    eyebrow = "STEP 6 OF 8",
                    title = "Choose your bank",
                    oneLiner = "Confirm your account type to continue.",
                    progress = { WizardProgressIndicator(currentStep = 5, totalSteps = 8) },
                    onBack = null,
                    forward = {},
                ) {
                    Text(
                        text = (viewModel.aspspsState as AspspsState.Error).message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ActionButton(onClick = { viewModel.loadAspsps() }, label = "Retry")
                    Spacer(modifier = Modifier.height(Dimens.xs))
                    QuietButton(
                        onClick = { viewModel.dismissResumeCard() },
                        label = "Choose a different bank",
                    )
                }
            }
            else -> {
                val bankName = viewModel.selectedAspsp?.name ?: viewModel.selectedBankFromState?.aspspName ?: "Selected Bank"
        val bankCountry = viewModel.selectedAspsp?.country ?: viewModel.selectedBankFromState?.aspspCountry ?: ""
        val aspsp = viewModel.selectedAspsp
        val psuTypes = aspsp?.psuTypes?.ifEmpty { listOf("personal", "business") } ?: listOf("personal", "business")

        WizardScaffold(
            eyebrow = "STEP 6 OF 8",
            title = "Choose your bank",
            oneLiner = "Confirm your account type to continue.",
            progress = { WizardProgressIndicator(currentStep = 5, totalSteps = 8) },
            onBack = null,
            forward = {
                ActionButton(
                    onClick = { viewModel.continueResumeWithBank() },
                    label = "Continue with $bankName",
                    enabled = viewModel.selectedPsuType.isNotEmpty(),
                )
            },
        ) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dimens.lg),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (aspsp != null) {
                            BankLogo(bank = aspsp)
                        } else {
                            BankAvatar(bankName = bankName, bic = null)
                        }

                        Spacer(modifier = Modifier.width(Dimens.md))

                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = bankName,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(modifier = Modifier.width(Dimens.sm))
                                val countryName = countryCodeToNameMap[bankCountry.uppercase()]
                                val displayCountry = if (countryName != null) {
                                    "${bankCountry.uppercase()} · $countryName"
                                } else {
                                    bankCountry.uppercase()
                                }
                                Text(
                                    text = displayCountry,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            if (aspsp?.bic != null) {
                                Spacer(modifier = Modifier.height(Dimens.xs))
                                Text(
                                    text = "BIC: ${aspsp.bic}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = jetBrainsMonoFamily()),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Dimens.lg))

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                    Spacer(modifier = Modifier.height(Dimens.md))

                    Text(
                        text = "Account type",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(modifier = Modifier.height(Dimens.sm))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                    ) {
                        psuTypes.sortedBy { if (it == "personal") 0 else 1 }.forEach { psu ->
                            val psuSelected = viewModel.selectedPsuType == psu
                            FilterChip(
                                selected = psuSelected,
                                onClick = {
                                    viewModel.selectPsuType(psu)
                                },
                                label = {
                                    Text(
                                        text = psu.capitalizeFirstLetter(),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                                shape = MaterialTheme.shapes.small,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = psuSelected,
                                    borderColor = MaterialTheme.colorScheme.outline,
                                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                                    borderWidth = 1.dp,
                                    selectedBorderWidth = 1.dp,
                                ),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Dimens.xs))

                    val isPsuConfirmed = viewModel.selectedPsuType.isNotEmpty()
                    val hasValidationErr = viewModel.linkError != null && !isPsuConfirmed
                    Text(
                        text = "Confirm your account type to continue",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasValidationErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.xs))

            QuietButton(
                onClick = { viewModel.dismissResumeCard() },
                label = "Choose a different bank",
            )

            viewModel.linkError?.takeIf { viewModel.selectedPsuType.isNotEmpty() }?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
            }
        }
    } else {
        var searchQuery by remember { mutableStateOf("") }

        WizardScaffold(
            eyebrow = "STEP 6 OF 8",
            title = "Choose your bank",
            oneLiner = "Search and select your bank.",
            progress = { WizardProgressIndicator(currentStep = 5, totalSteps = 8) },
            onBack = null,
            forward = {
                val selected = viewModel.selectedAspsp
                if (selected != null) {
                    ActionButton(
                        onClick = { viewModel.linkAccounts() },
                        label = "Connect ${selected.name}",
                    )
                }
            },
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search by name, BIC, or country") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            when (val state = viewModel.aspspsState) {
            is AspspsState.Loading -> {
                WaitingIndicator.Zone()
            }
                is AspspsState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ActionButton(onClick = { viewModel.loadAspsps() }, label = "Retry")
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
                    } else {
                        Text(
                            text = "${filtered.size} banks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                                verticalArrangement = Arrangement.spacedBy(Dimens.sm),
                            ) {
                                items(
                                    items = filtered,
                                    key = { bank -> "${bank.name}-${bank.country}" },
                                ) { bank ->
                                    val isSelected = viewModel.selectedAspsp?.name == bank.name &&
                                            viewModel.selectedAspsp?.country == bank.country
                                    OutlinedCard(
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
                                        shape = MaterialTheme.shapes.medium,
                                        border = BorderStroke(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        ),
                                        colors = CardDefaults.outlinedCardColors(
                                            containerColor = if (isSelected) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainer
                                            }
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(Dimens.md),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            BankLogo(bank = bank)

                                            Spacer(modifier = Modifier.width(Dimens.md))

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
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.weight(1f, fill = false),
                                                    )
                                                    Spacer(modifier = Modifier.width(Dimens.sm))
                                                    val countryName = countryCodeToNameMap[bank.country.uppercase()]
                                                    val displayCountry = if (countryName != null) {
                                                        "${bank.country.uppercase()} · $countryName"
                                                    } else {
                                                        bank.country.uppercase()
                                                    }
                                                    Text(
                                                        text = displayCountry,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }

                                                if (bank.bic != null) {
                                                    Spacer(modifier = Modifier.height(Dimens.xs))
                                                    Text(
                                                        text = "BIC: ${bank.bic}",
                                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = jetBrainsMonoFamily()),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }

                                                if (bank.psuTypes.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.height(Dimens.sm))
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
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
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                    )
                                                                },
                                                                shape = MaterialTheme.shapes.small,
                                                                colors = FilterChipDefaults.filterChipColors(
                                                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                                                    containerColor = MaterialTheme.colorScheme.surface,
                                                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                ),
                                                                border = FilterChipDefaults.filterChipBorder(
                                                                    enabled = true,
                                                                    selected = psuSelected,
                                                                    borderColor = MaterialTheme.colorScheme.outline,
                                                                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                                                                    borderWidth = 1.dp,
                                                                    selectedBorderWidth = 1.dp,
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
                    }
                }
            }

            viewModel.linkError?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------
// LinkingProgressStep (task 9.1 - 9.6)
// ---------------------------------------------------------------

@Composable
private fun LinkingProgressStep(viewModel: AppViewModel) {
    val isResumeMode = viewModel.isResumeMode

    val linkUrl = viewModel.linkAuthorizationUrl

    // Open the linking tab automatically in resume mode, or after the user
    // explicitly requested a fresh linking URL via "Re-open linking page".
    var pendingAutoOpen by remember { mutableStateOf(false) }
    LaunchedEffect(linkUrl, isResumeMode, pendingAutoOpen) {
        if (linkUrl != null && (isResumeMode || pendingAutoOpen)) {
            openUrlInNewTab(linkUrl)
            viewModel.consumeLinkAuthorizationUrl()
            pendingAutoOpen = false
        }
    }

    WizardScaffold(
        eyebrow = "STEP 7 OF 8",
        title = "Linking your account",
        oneLiner = if (isResumeMode) {
            "Account linking was started in a previous session. " +
                    "If you've completed linking in the Enable Banking control panel, " +
                    "click below to continue to authorization."
        } else {
            "You'll be redirected to Enable Banking's control panel to link your account. " +
                    "This verifies your identity so BankTeller can securely access your bank accounts."
        },
        progress = { WizardProgressIndicator(currentStep = 6, totalSteps = 8) },
        onBack = { viewModel.cancelLinking() },
        backLabel = "Back to bank list",
        backServerAction = true, // POST cancel-linking — disable while in flight
        forward = {
            ActionButton(
                onClick = { viewModel.checkLinkStatus() },
                label = "I've completed linking",
            )
        },
    ) {
        if (!isResumeMode) {
            Text(
                text = "After completing the linking process, close that tab and return here to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        viewModel.authError?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            ActionButton(onClick = { viewModel.checkLinkStatus() }, label = "Retry")
        }

        viewModel.linkError?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (viewModel.linkStatusChecking) {
            WaitingIndicator.Inline()
        }

        if (linkUrl != null) {
            Button(
                onClick = {
                    openUrlInNewTab(linkUrl)
                    viewModel.consumeLinkAuthorizationUrl()
                },
            ) {
                Text("Open linking page")
            }
            Text(
                text = "Click the button above to open the Enable Banking control panel in a new tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "If you closed the tab by accident, you can re-open the linking page.",
                style = MaterialTheme.typography.bodySmall,
            )
            QuietActionButton(
                onClick = {
                    pendingAutoOpen = true
                    viewModel.relinkAccount()
                },
                label = "Re-open linking page",
            )
        }
    }
}

// ---------------------------------------------------------------
// AuthProgressStep (task 10.1 - 10.4)
// ---------------------------------------------------------------

@Composable
private fun AuthProgressStep(viewModel: AppViewModel) {
    val authRedirectUrl = viewModel.authRedirectUrl

    WizardScaffold(
        eyebrow = "STEP 8 OF 8",
        title = "Authorizing session",
        oneLiner = "You'll be redirected to your bank's secure page to grant account access. " +
                "Here's what to expect:",
        progress = { WizardProgressIndicator(currentStep = 7, totalSteps = 8) },
        onBack = null,
        forward = {
            // D6 footprint rule: the button stays mounted at all times.
            // While POST /api/auth is in flight, LocalActionBusy disables it
            // automatically; once the redirect URL arrives, the domain
            // condition (authRedirectUrl != null) enables it. On failure the
            // URL stays null so the button remains disabled — the error is
            // shown in the content zone below.
            ActionButton(
                onClick = {
                    authRedirectUrl?.let {
                        redirectTo(it)
                        viewModel.consumeAuthRedirectUrl()
                    }
                },
                label = "Continue to your bank",
                enabled = authRedirectUrl != null,
            )
        },
    ) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(Dimens.md),
                verticalArrangement = Arrangement.spacedBy(Dimens.sm),
            ) {
                Text(
                    text = "Your bank's consent page",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                HorizontalDivider()

                Text(
                    text = "Make sure all three access categories are checked:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ConsentItem("Accounts", "Access to your account information")
                ConsentItem("Account balances", "Access to view your balances")
                ConsentItem("Transactions", "Access to your transaction history")

                Spacer(modifier = Modifier.height(Dimens.xs))

                Text(
                    text = "Then select the accounts you want to connect and click \"Grant authorization\".",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DecisionBox(
            tint = LocalBankTellerColors.current.emerald.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "BankTeller will never transfer money without your explicit approval.",
                style = MaterialTheme.typography.bodyMedium,
            )
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
        Spacer(modifier = Modifier.width(Dimens.sm))
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
        Spacer(modifier = Modifier.width(Dimens.sm))
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
