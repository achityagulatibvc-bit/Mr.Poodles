package com.mrpoodles.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class Phase4FoodUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun foodCheckIsTextOnlyAndPastedIngredientsNeedNoProviderOptIn() {
        val app = RuntimeEnvironment.getApplication()
        val data = AppData(profile = Profile(sourceLookupConsent = false, externalModelConsent = false))
        val gate = RequestGate()
        val request = gate.begin(data.revision)
        LocalStore(app).save(data) { move -> gate.commit(request, move) }
        var calls = 0
        val model = object : CloudModel() {
            override suspend fun research(input: ResearchRequest): ResearchResponse {
                calls++
                error("Pasted ingredients must remain local")
            }
        }
        val vm = PoodlesViewModel(app, SavedStateHandle(), model)
        compose.setContent {
            val state by vm.state.collectAsState()
            PoodlesTheme(motion = false) { FoodCheckWorkspace(state, vm) }
        }
        compose.waitUntil(10000) { compose.runOnIdle { !vm.state.value.loading } }
        compose.onNodeWithTag("check_draft").assertIsDisplayed()
        compose.onNodeWithTag("check_send").assertIsNotEnabled()
        for (term in listOf("camera", "gallery", "photo", "rotate", "re-read")) {
            compose.onAllNodes(hasText(term, substring = true, ignoreCase = true) or
                hasContentDescription(term, substring = true, ignoreCase = true)).assertCountEquals(0)
        }
        compose.onNodeWithTag("check_draft").performTextInput("Ingredients: rice, salt.")
        compose.onNodeWithText("Allow source lookup").assertDoesNotExist()
        compose.onNodeWithTag("check_send").assertIsEnabled().performClick()
        compose.waitUntil(10000) { compose.runOnIdle {
            !vm.state.value.busy && vm.featureStates.value[Feature.FOOD_CHECK]?.result != null
        } }
        val result = requireNotNull(vm.featureStates.value.getValue(Feature.FOOD_CHECK).result?.assessment)
        assertEquals(AssessmentOutcome.NEED_MORE_INFORMATION, result.outcome)
        assertNull(result.origin.retrieval)
        assertEquals(0, calls)
        assertFalse(LocalStore(app).read().profile.sourceLookupConsent)
        assertEquals("", vm.featureStates.value.getValue(Feature.FOOD_CHECK).draft)
    }

    @Test fun settingsEditPreservesExplicitSourceAndExternalFallbackConsent() {
        val original = Profile(sourceLookupConsent = true, externalModelConsent = true,
            cuisine = "Indian", rememberChats = true)
        var saved: Profile? = null
        compose.setContent { PoodlesTheme(motion = false) {
            ProfileScreen(original, false, null, {}, {}, {}, { saved = it })
        } }
        compose.onNodeWithText("Where are you today?").performScrollTo()
        compose.onNodeWithText("Home", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Save my preferences").performClick()
        assertEquals(original.copy(mode = "Home"), saved)
        assertTrue(requireNotNull(saved).sourceLookupConsent)
        assertTrue(requireNotNull(saved).externalModelConsent)
    }
}
