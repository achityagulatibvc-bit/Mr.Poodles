package com.mrpoodles.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
class Phase3LayoutUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longContextCompactsForKeyboardAndDraftSurvivesRestorationAtLargeFont() {
        var keyboard by mutableStateOf(false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PoodlesTheme(motion = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    var draft by rememberSaveable { mutableStateOf("") }
                    Box(Modifier.width(320.dp).height(if (keyboard) 250.dp else 460.dp)) {
                        ContextChatLayout(keyboardVisible = keyboard,
                            context = { repeat(40) { Text("Evidence line $it") } },
                            compactContext = { Text("Current result") },
                            conversation = { LazyColumn(Modifier.fillMaxSize()) { item { Text("Conversation stays separate") } } },
                            composer = {
                                Row(Modifier.fillMaxWidth()) {
                                    OutlinedTextField(draft, { draft = it }, Modifier.weight(1f).testTag("test_draft"), maxLines = 1)
                                    Button({}, Modifier.heightIn(min = 48.dp).testTag("test_send")) { Text("Send") }
                                }
                            })
                    }
                }
            }
        }
        compose.onNodeWithTag("test_draft").performTextInput("Keep this")
        compose.onNodeWithText("Evidence line 20").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { keyboard = true }
        compose.onNodeWithText("Current result").assertIsDisplayed()
        compose.onNodeWithText("Evidence line 0").assertDoesNotExist()
        compose.onNodeWithTag("test_draft").assertIsDisplayed()
        compose.onNodeWithTag("test_send").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("test_draft").assertTextContains("Keep this")
        compose.runOnIdle { keyboard = false }
        compose.onNodeWithText("Evidence line 20").assertIsDisplayed()
    }

    @Test fun compactRecipeKeepsComposerDetailsAndSavedShelfReachable() {
        val app = RuntimeEnvironment.getApplication()
        val vm = PoodlesViewModel(app, SavedStateHandle())
        val previous = Recipe("Previous draft", emptyList())
        vm.editRecipeRequest("banana")
        compose.setContent {
            PoodlesTheme(motion = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.width(320.dp).height(260.dp)) {
                        RecipeWorkspace(ScreenState(loading = false, draft = previous), vm, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("recipe_draft").assertIsDisplayed().assertTextContains("banana")
        compose.onNodeWithTag("recipe_send").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Details").assertIsDisplayed().performClick()
        compose.onNodeWithText("Save this recipe").performScrollTo().assertIsDisplayed()
    }

    @Test fun failedRecipeKeepsDraftPreviousResultAndInlineRetry() {
        val app = RuntimeEnvironment.getApplication()
        LocalStore(app).save(AppData(profile = Profile(onboarding = true, sourceLookupConsent = true), recipeDraft = Recipe("Previous draft", emptyList())))
        var calls = 0
        val model = object : CloudModel() {
            override suspend fun research(input: ResearchRequest): ResearchResponse {
                calls++
                throw IllegalStateException("Offline for this test.")
            }
        }
        val vm = PoodlesViewModel(app, SavedStateHandle(), model)
        compose.setContent {
            val state by vm.state.collectAsState()
            PoodlesTheme(motion = false) { RecipeWorkspace(state, vm, {}) }
        }
        compose.waitUntil(10000) { compose.runOnIdle { !vm.state.value.loading } }
        compose.onNodeWithTag("recipe_draft").performTextInput("A banana breakfast")
        compose.onNodeWithTag("recipe_send").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { vm.featureStates.value[Feature.RECIPE]?.error != null && !vm.state.value.busy } }
        compose.onNodeWithTag("recipe_draft").assertTextContains("A banana breakfast")
        assertEquals("Previous draft", vm.state.value.draft?.title)
        assertNull(vm.state.value.chatError)
        compose.onNodeWithTag("recipe_conversation").performScrollToNode(hasText("Try again"))
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { calls == 2 && !vm.state.value.busy } }
        compose.onNodeWithTag("recipe_draft").performTextClearance()
        compose.onNodeWithTag("recipe_draft").performTextInput("A different idea")
        compose.onNodeWithText("Try again").assertDoesNotExist()
        assertEquals("Previous draft", vm.state.value.draft?.title)
    }

    @Test fun settingsHideOptionalFieldsAndPreserveBothEnvironmentsAndLegacyPreferences() {
        val original = Profile(cuisine = "Indian", goal = "Original goal", calorieTarget = 1800,
            hostel = Environment(pantry = "oats", budget = "100 INR", rules = "quiet", workoutSpace = "Small room"),
            home = Environment(equipment = listOf("Oven"), pantry = "rice", maxMinutes = 45))
        var saved: Profile? = null
        compose.setContent { PoodlesTheme(motion = false) {
            ProfileScreen(original, false, null, {}, {}, {}, { saved = it })
        } }
        compose.onNodeWithText("Favorite cuisines").assertDoesNotExist()
        compose.onNodeWithText("Edit environment").assertDoesNotExist()
        compose.onNodeWithText("Where are you today?").performScrollTo()
        compose.onNodeWithText("Home", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Optional food preferences").performScrollTo().performClick()
        compose.onNodeWithText("rice").assertExists()
        compose.onNodeWithText("Save my preferences").performClick()
        assertEquals(original.copy(mode = "Home"), saved)
    }

    @Test fun launchDeadlineRunsOnceAndDoesNotWaitForNetworkOrReplayAfterRestoration() {
        var now = 0L
        val gate = ColdLaunchGate { now }
        assertEquals(2000L, gate.remainingMillis())
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Text(if (launchDelayFinished(gate)) "Ready" else "Launching") }
        compose.mainClock.advanceTimeBy(1800)
        compose.onNodeWithText("Launching").assertExists()
        now = 2100
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Ready").assertExists()
        assertEquals(0L, gate.remainingMillis())
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Ready").assertExists()
        now = 10000
        assertEquals(0L, gate.remainingMillis())
    }

    @Test fun largeFontHomeActionsHaveLabeledTouchTargetsAndShowNextUnloggedMeal() {
        val date = java.time.LocalDate.now().toString()
        val eaten = Meal(date = date, slot = "Breakfast", recipe = Recipe("Already eaten", emptyList()), mode = "Hostel", revision = 0)
        val upcoming = Meal(date = date, slot = "Lunch", recipe = Recipe("Next lunch", emptyList()), mode = "Hostel", revision = 0)
        val state = ScreenState(loading = false, data = AppData(meals = listOf(upcoming, eaten),
            intake = listOf(Intake(date = date, name = "Breakfast", kcal = 100.0, mealId = eaten.id))))
        var selected: HomeAction? = null
        compose.setContent { PoodlesTheme(motion = false) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { TodayScreen(state) { selected = it } }
            }
        } }
        listOf("Can I eat this?" to HomeAction.CHECK, "Find a recipe" to HomeAction.RECIPE,
            "Find a workout" to HomeAction.WORKOUT, "Log what I ate" to HomeAction.FOOD_LOG).forEach { (label, action) ->
            compose.onNodeWithText(label).performScrollTo().assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(action, selected)
        }
        compose.onNodeWithText("Next lunch").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Already eaten").assertDoesNotExist()
    }
}
