@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.ridervoice.ui

import android.app.Application
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.auth.FirebaseAuth
import com.ridervoice.R
import com.ridervoice.ui.components.RvAutofillContentTypeKey
import com.ridervoice.ui.screens.EditProfileFormContent
import com.ridervoice.ui.screens.HostSetupScreen
import com.ridervoice.ui.screens.JoinRoomScreen
import com.ridervoice.ui.screens.LoginScreen
import com.ridervoice.ui.screens.PostRideSummaryScreen
import com.ridervoice.ui.screens.RegisterScreen
import com.ridervoice.ui.theme.RiderVoiceTheme
import com.ridervoice.ui.viewmodels.AuthViewModel
import com.ridervoice.ui.viewmodels.HostSetupViewModel
import com.ridervoice.ui.viewmodels.JoinRoomViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = Application::class)
class FormStateAndKeyboardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun hasImeAction(expected: ImeAction): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, expected)

    private fun hasAutofillType(expected: AutofillType): SemanticsMatcher =
        SemanticsMatcher.expectValue(RvAutofillContentTypeKey, expected)

    @Test
    fun loginScreen_preservesFormStateAcrossRecreation_andExposesAutofillAndImeActions() {
        val restorationTester = StateRestorationTester(composeRule)
        val mockVm = mockk<AuthViewModel>(relaxed = true) {
            every { isLoading } returns MutableStateFlow(false)
            every { loginSuccess } returns MutableStateFlow(false)
            every { errorMessage } returns MutableStateFlow(null)
            every { resetEmailSent } returns MutableStateFlow(false)
        }
        val app = ApplicationProvider.getApplicationContext<Application>()

        restorationTester.setContent {
            RiderVoiceTheme {
                LoginScreen(
                    viewModel = mockVm,
                    onLoginSuccess = {},
                    onRegisterClick = {}
                )
            }
        }

        composeRule.onNodeWithTag("login_email_field")
            .assert(hasAutofillType(AutofillType.EmailAddress))
            .assert(hasImeAction(ImeAction.Next))
            .performTextInput("rider@example.com")

        composeRule.onNodeWithTag("login_password_field")
            .assert(hasAutofillType(AutofillType.Password))
            .assert(hasImeAction(ImeAction.Done))
            .performTextInput("hunter234")

        composeRule.onNodeWithContentDescription(app.getString(R.string.cd_show_password)).performClick()
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("login_email_field").assertTextContains("rider@example.com")
        composeRule.onNodeWithTag("login_password_field").assertTextContains("hunter234")
        composeRule.onNodeWithContentDescription(app.getString(R.string.cd_hide_password)).assertExists()
    }

    @Test
    fun registerScreen_preservesFormStateAcrossRecreation_andExposesAutofillAndImeActions() {
        val restorationTester = StateRestorationTester(composeRule)
        val mockVm = mockk<AuthViewModel>(relaxed = true) {
            every { isLoading } returns MutableStateFlow(false)
            every { loginSuccess } returns MutableStateFlow(false)
            every { errorMessage } returns MutableStateFlow(null)
        }

        restorationTester.setContent {
            RiderVoiceTheme {
                RegisterScreen(
                    viewModel = mockVm,
                    onBackClick = {},
                    onRegisterSuccess = {}
                )
            }
        }

        composeRule.onNodeWithTag("register_handle_field")
            .assert(hasAutofillType(AutofillType.Username))
            .assert(hasImeAction(ImeAction.Next))
            .performTextInput("sahil_r")

        composeRule.onNodeWithTag("register_email_field")
            .assert(hasAutofillType(AutofillType.EmailAddress))
            .assert(hasImeAction(ImeAction.Next))
            .performTextInput("sahil@example.com")

        composeRule.onNodeWithTag("register_password_field")
            .assert(hasAutofillType(AutofillType.NewPassword))
            .assert(hasImeAction(ImeAction.Next))
            .performTextInput("Secret123")

        composeRule.onNodeWithTag("register_confirm_password_field")
            .assert(hasAutofillType(AutofillType.NewPassword))
            .assert(hasImeAction(ImeAction.Done))
            .performTextInput("Secret123")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("register_handle_field").assertTextContains("sahil_r")
        composeRule.onNodeWithTag("register_email_field").assertTextContains("sahil@example.com")
    }

    @Test
    fun hostSetupScreen_preservesInputsAndImeFlowAcrossRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        val mockVm = mockk<HostSetupViewModel>(relaxed = true) {
            every { isLoading } returns MutableStateFlow(false)
            every { error } returns MutableStateFlow(null)
            every { createState } returns MutableStateFlow(HostSetupViewModel.CreateRideState.Draft)
            every { getOrCreateClientRequestId() } returns "req-1"
        }

        restorationTester.setContent {
            RiderVoiceTheme {
                HostSetupScreen(
                    onConvoyCreated = {},
                    onBackClick = {},
                    viewModel = mockVm
                )
            }
        }

        composeRule.onNodeWithTag("host_convoy_name_field")
            .assert(hasImeAction(ImeAction.Next))
            .performTextInput("Konkan Dawn")
        composeRule.onNodeWithTag("host_meetup_field")
            .assert(hasImeAction(ImeAction.Next))
            .performTextInput("Toll Plaza")
        composeRule.onNodeWithTag("host_destination_field")
            .assert(hasImeAction(ImeAction.Next))
            .performTextInput("Ratnagiri")
        composeRule.onNodeWithTag("host_origin_field")
            .assert(hasImeAction(ImeAction.Done))
            .performTextInput("Pune")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("host_convoy_name_field").assertTextContains("Konkan Dawn")
        composeRule.onNodeWithTag("host_meetup_field").assertTextContains("Toll Plaza")
        composeRule.onNodeWithTag("host_destination_field").assertTextContains("Ratnagiri")
        composeRule.onNodeWithTag("host_origin_field").assertTextContains("Pune")
    }

    @Test
    fun joinRoomScreen_preservesRoomCodeAndImeActions() {
        val restorationTester = StateRestorationTester(composeRule)
        val mockVm = mockk<JoinRoomViewModel>(relaxed = true) {
            every { isConnecting } returns MutableStateFlow(false)
            every { error } returns MutableStateFlow(null)
        }
        val mockAuth = mockk<FirebaseAuth>(relaxed = true)
        every { mockAuth.currentUser } returns null

        mockkStatic(FirebaseAuth::class)
        try {
            every { FirebaseAuth.getInstance() } returns mockAuth

            restorationTester.setContent {
                RiderVoiceTheme {
                    JoinRoomScreen(
                        onJoin = { _, _ -> },
                        onBackClick = {},
                        viewModel = mockVm
                    )
                }
            }

            composeRule.onNodeWithTag("join_username_field")
                .assert(hasImeAction(ImeAction.Next))
            composeRule.onNodeWithTag("join_room_code_field")
                .assert(hasImeAction(ImeAction.Go))
                .performTextInput("428199")

            restorationTester.emulateSavedInstanceStateRestore()
            composeRule.onNodeWithTag("join_room_code_field").assertTextContains("428199")
        } finally {
            unmockkStatic(FirebaseAuth::class)
        }
    }

    @Test
    fun editProfileDialog_preservesAllFiveFieldsAndImeFlowAcrossRecreation() {
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            RiderVoiceTheme {
                EditProfileFormContent(
                    initialDisplayName = "Asha",
                    initialHandle = "asha_r",
                    initialPhone = "98765",
                    initialBikeModel = "RE Himalayan",
                    initialBio = "Touring",
                    isSaving = false,
                    onDismiss = {},
                    onSave = { _, _, _, _, _ -> }
                )
            }
        }

        composeRule.onNodeWithTag("account_display_name_field")
            .assert(hasImeAction(ImeAction.Next))
            .performTextReplacement("Asha Patil")
        composeRule.onNodeWithTag("account_handle_field").assert(hasImeAction(ImeAction.Next))
        composeRule.onNodeWithTag("account_phone_field").assert(hasImeAction(ImeAction.Next))
        composeRule.onNodeWithTag("account_bike_field").assert(hasImeAction(ImeAction.Next))
        composeRule.onNodeWithTag("account_bio_field")
            .assert(hasImeAction(ImeAction.Done))
            .performTextReplacement("Touring 450")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("account_display_name_field").assertTextContains("Asha Patil")
        composeRule.onNodeWithTag("account_bio_field").assertTextContains("Touring 450")
    }

    @Test
    fun postRideSummaryScreen_preservesTitleAndNotesAcrossRecreation() {
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            RiderVoiceTheme {
                PostRideSummaryScreen(
                    durationMinutes = 45,
                    distanceKm = 38.5f,
                    topSpeed = 88f,
                    convoyName = "Dawn Loop",
                    onSaveToLogbook = { _, _ -> },
                    onDiscard = {}
                )
            }
        }

        composeRule.onNodeWithTag("summary_ride_title_input")
            .assert(hasImeAction(ImeAction.Next))
            .performTextInput("Mulshi Run")
        composeRule.onNodeWithTag("summary_ride_notes_input")
            .assert(hasImeAction(ImeAction.Done))
            .performTextInput("Dry roads.")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("summary_ride_title_input").assertTextContains("Mulshi Run")
        composeRule.onNodeWithTag("summary_ride_notes_input").assertTextContains("Dry roads.")
    }
}
