package com.mrpoodles.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun everyTabOpensWithoutVisitingChatFirst() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Set up later").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Set up later").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("tab_Today").fetchSemanticsNodes().isNotEmpty() }
        listOf("Check" to "Can I eat this?", "Meals" to "Something nourishing",
            "Move" to "Find a workout", "Today" to "Your little plans",
            "Check" to "Can I eat this?").forEach { (tab, heading) ->
            compose.onNodeWithTag("tab_$tab").performTouchInput { click(center) }
            compose.onNodeWithTag("tab_$tab").assertIsSelected()
            compose.onNodeWithText(heading).assertExists()
        }
    }

    @Test fun chatDraftAndMealSubtabSurviveNavigationAndRecreation() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Set up later").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Set up later").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("tab_Chat").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab_Chat").performClick()
        compose.onNodeWithTag("chat_draft").performTextInput("A draft worth keeping")
        compose.onNodeWithTag("tab_Meals").performClick()
        compose.onNodeWithText("Recipes").performClick()
        compose.onNodeWithText("Find a recipe").performScrollTo().performClick()
        compose.onNodeWithText("Your recipe wish").performTextInput("A banana breakfast")
        compose.onNodeWithTag("tab_Check").performClick()
        compose.onNodeWithTag("check_draft").performTextInput("rice, whey")
        compose.onNodeWithTag("tab_Meals").performClick()
        compose.onNodeWithText("Your recipe shelf").assertExists()
        compose.onNodeWithText("Find a recipe").performScrollTo().performClick()
        compose.onNodeWithText("A banana breakfast").assertExists()
        compose.onNodeWithTag("tab_Chat").performClick()
        compose.onNodeWithTag("chat_draft").assertTextContains("A draft worth keeping")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("chat_draft").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_draft").assertTextContains("A draft worth keeping")
        compose.onNodeWithTag("tab_Check").performClick()
        compose.onNodeWithText("rice, whey").assertExists()
    }

    @Test @Config(qualifiers = "w320dp-h480dp") fun compactChatKeepsComposerAndAllNavigationTargetsReachable() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Set up later").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Set up later").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("tab_Chat").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab_Chat").performClick()
        compose.onNodeWithTag("chat_draft").assertIsDisplayed().performTextInput("hello")
        compose.onNodeWithContentDescription("Send to Poodles").assertIsDisplayed()
        listOf("Today", "Check", "Meals", "Move", "Chat").forEach { compose.onNodeWithTag("tab_$it").assertIsDisplayed() }
        compose.onNodeWithContentDescription("Conversation options").performClick()
        compose.onNodeWithText("Clear conversation").assertIsDisplayed().performClick()
        compose.onNodeWithText("Keep it").performClick()
    }

    @Test fun onboardingSkipLeadsToInteractiveDashboard() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Set up later").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Set up later").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("A little care,\njust for you.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("About you").performClick()
        compose.onNodeWithText("Save my preferences").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithContentDescription("About you").assertIsDisplayed().performClick()
        compose.onNodeWithText("Save my preferences").assertIsDisplayed()
    }

    @Test fun profileDraftSurvivesActivityRecreation() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Make it more me").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Make it more me").performScrollTo().performClick()
        compose.onNodeWithText("What should Poodles call you? (optional)").performTextInput("Sunshine")
        compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Sunshine").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Sunshine").assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Make it more me").performScrollTo().performClick()
        compose.onNodeWithText("Sunshine").assertExists()
    }

    @Test fun homeActionsOpenTheirTaskDirectlyAndBackReturnsHome() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Set up later").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Set up later").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("tab_Today").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Find a recipe").performScrollTo().performClick()
        compose.onNodeWithTag("recipe_draft").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("tab_Today").assertIsSelected()
        compose.onAllNodesWithText("Find a workout")[0].performScrollTo().performClick()
        compose.onNodeWithText("Find a workout").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("tab_Today").assertIsSelected()
        compose.onNodeWithText("Can I eat this?").performScrollTo().performClick()
        compose.onNodeWithText("Can I eat this?").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Log what I ate").performScrollTo().performClick()
        compose.onNodeWithTag("food_log_screen").assertExists()
        compose.onNodeWithTag("food_log_draft").assertIsDisplayed().performTextInput("A food log draft worth keeping")
        compose.onNodeWithText("Food and portion").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("food_log_draft").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("food_log_draft").assertTextContains("A food log draft worth keeping")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("tab_Today").assertIsSelected()
        compose.onNodeWithTag("tab_Meals").performClick()
        compose.onNodeWithText("Food diary").performClick()
        compose.onNodeWithText("Log food, snack or drink").performScrollTo().performClick()
        compose.onNodeWithText("Food and portion").assertIsDisplayed().performTextInput("Saved dialog draft")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Saved dialog draft").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Today's food diary").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("tab_Today").assertIsSelected()
    }

    @Test fun recipeBackReturnsToShelfAndRecreationDoesNotRepeatLaunch() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Set up later").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Set up later").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("tab_Meals").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab_Meals").performClick()
        compose.onNodeWithText("Recipes").performClick()
        compose.onNodeWithText("Find a recipe").performScrollTo().performClick()
        compose.onNodeWithTag("recipe_draft").performTextInput("Keep this recipe request")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("launch_splash").assertDoesNotExist()
        compose.onNodeWithTag("recipe_draft").assertTextContains("Keep this recipe request")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("launch_splash").assertDoesNotExist()
        compose.onNodeWithTag("recipe_draft").assertTextContains("Keep this recipe request")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Your recipe shelf").assertExists()
    }

    @Test @Config(qualifiers = "w320dp-h480dp") fun imeInsetsCompactRecipeAndKeepHomeAndComposerVisible() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Set up later").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Set up later").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("tab_Today").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Find a recipe").performScrollTo().performClick()
        compose.onNodeWithTag("recipe_draft").performTextInput("Keep this")
        compose.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 220))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.onNodeWithTag("tab_Today").assertDoesNotExist()
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithTag("recipe_draft").assertIsDisplayed().assertTextContains("Keep this")
        compose.onNodeWithTag("recipe_send").assertIsDisplayed()
    }
}
