package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke test — verifies the commonTest infrastructure works on js + wasmJs targets.
 * Exercises a pure path (no coroutines, no viewModelScope) to confirm the test
 * runner compiles and executes kotlin.test assertions in both browser-based
 * and Node-based KMP web targets.
 */
class AppViewModelSmokeTest {

    @Test
    fun screen_enum_has_five_values_with_settings_appended() {
        // Settings APPENDS at the end (bank-connection-management 4.2):
        // the pre-existing members' ordinal positions are part of this
        // contract — the resolver, the tab bar, and the wizard-origin
        // state all key on identity, never on ordinals, but the stable
        // order keeps cold-boot routing diffs readable.
        assertEquals(5, Screen.entries.size)
        assertEquals(Screen.Loading, Screen.entries[0])
        assertEquals(Screen.Login, Screen.entries[1])
        assertEquals(Screen.Onboarding, Screen.entries[2])
        assertEquals(Screen.Dashboard, Screen.entries[3])
        assertEquals(Screen.Settings, Screen.entries[4])
    }

    @Test
    fun onboarding_step_enum_has_eight_values() {
        assertEquals(8, OnboardingStep.entries.size)
        assertEquals(OnboardingStep.EmailEntry, OnboardingStep.entries[0])
        assertEquals(OnboardingStep.WaitingForAuthentication, OnboardingStep.entries[1])
        assertEquals(OnboardingStep.RegistrationReview, OnboardingStep.entries[2])
        assertEquals(OnboardingStep.Verifying, OnboardingStep.entries[3])
        assertEquals(OnboardingStep.ActivationGuide, OnboardingStep.entries[4])
        assertEquals(OnboardingStep.BankSelection, OnboardingStep.entries[5])
        assertEquals(OnboardingStep.LinkingProgress, OnboardingStep.entries[6])
        assertEquals(OnboardingStep.AuthProgress, OnboardingStep.entries[7])
    }

    @Test
    fun production_field_overrides_data_class_holds_all_four_fields() {
        val overrides = ProductionFieldOverrides(
            description = "BankTeller",
            gdprEmail = "admin@example.com",
            privacyUrl = "https://example.com/privacy",
            termsUrl = "https://example.com/terms",
        )
        assertEquals("BankTeller", overrides.description)
        assertEquals("admin@example.com", overrides.gdprEmail)
        assertEquals("https://example.com/privacy", overrides.privacyUrl)
        assertEquals("https://example.com/terms", overrides.termsUrl)
    }
}
