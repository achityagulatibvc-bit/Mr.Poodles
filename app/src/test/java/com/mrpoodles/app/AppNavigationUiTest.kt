package com.mrpoodles.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        compose.waitUntil(10000) { compose.onAllNodesWithText("Today").fetchSemanticsNodes().isNotEmpty() }
        listOf("Check" to "A little label check", "Meals" to "Something nourishing",
            "Move" to "Your movement corner", "Today" to "Your little plans",
            "Check" to "A little label check").forEach { (tab, heading) ->
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
        compose.onNodeWithText("Your recipe wish").performTextInput("A banana breakfast")
        compose.onNodeWithTag("tab_Check").performClick()
        compose.onNodeWithText("Ingredients and allergen statements").performScrollTo().performTextInput("rice, whey")
        compose.onNodeWithTag("tab_Meals").performClick()
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
}
