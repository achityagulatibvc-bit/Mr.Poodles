package com.mrpoodles.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WelcomeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun welcomeAllowsContinuingWithoutOpeningModal() {
        var continued = false
        compose.setContent { PoodlesTheme(motion = false) { WelcomeScreen(false, null, {}, {}, { continued = true }) } }
        compose.onNodeWithText("Set up later").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(continued) }
    }

    @Test fun profileBackReturnsControlAndRetainsTypedDraft() {
        var exited = false
        var draft = Profile()
        compose.setContent { PoodlesTheme(motion = false) { ProfileScreen(Profile(), false, null, {}, { draft = it }, { exited = true }, {}) } }
        compose.onNodeWithText("What should Poodles call you? (optional)").performTextInput("Lovely")
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { assertTrue(exited); assertEquals("Lovely", draft.name) }
    }

    @Test @Config(qualifiers = "w360dp-h480dp") fun profileSaveRemainsReachableWhileFormIsScrolled() {
        var saved: Profile? = null
        compose.setContent { PoodlesTheme(motion = false) { ProfileScreen(Profile(), false, null, {}, {}, {}, { saved = it }) } }
        compose.onNodeWithText("Optional calorie target (kcal/day)").performScrollTo().performTextInput("2100")
        compose.onNodeWithText("Save my preferences").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2100, saved?.calorieTarget) }
    }

    @Test fun saveFailureKeepsFormAndTextAvailable() {
        compose.setContent { PoodlesTheme(motion = false) { ProfileScreen(Profile(name = "Test"), false, "Storage full", {}, {}, {}, {}) } }
        compose.onNodeWithText("Your draft is safe").assertIsDisplayed()
        compose.onNodeWithText("Storage full").assertIsDisplayed()
    }
}
