package com.mrpoodles.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class Phase6NavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val restoration by lazy { StateRestorationTester(compose) }

    private fun render(data: AppData = AppData()): PoodlesViewModel {
        val app = RuntimeEnvironment.getApplication()
        LocalStore(app).save(data.copy(profile = data.profile.copy(onboarding = true, cloudConsent = true, gentleMotion = false)))
        val vm = PoodlesViewModel(app, SavedStateHandle())
        restoration.setContent { PoodlesApp(vm) }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("tab_Today").fetchSemanticsNodes().isNotEmpty() }
        return vm
    }

    private fun openDiary() {
        compose.onNodeWithTag("tab_Meals").performClick()
        compose.onNodeWithText("Food diary").performScrollTo().performClick()
    }

    @Test fun foodLogBackRestoresMealsOriginDateAndDraftWhileHomeReturnsHome() {
        val date = "2020-02-29"
        render(AppData(features = mapOf(Feature.FOOD_LOG to FeatureConversation(selectedDate = date))))
        openDiary()
        compose.onNodeWithTag("diary_selected_date").assertTextEquals(date)
        compose.onNodeWithText("Log what I ate").performScrollTo().performClick()
        compose.onNodeWithTag("food_log_screen").assertExists()
        compose.onNodeWithTag("food_log_date").assertTextContains(date)
        compose.onNodeWithTag("food_log_draft").performTextInput("A banana yesterday")
        compose.onNodeWithText("Food and portion").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("food_log_draft").assertTextContains("A banana yesterday")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("tab_Meals").assertIsSelected()
        compose.onNodeWithTag("diary_selected_date").assertTextEquals(date)
        compose.onNodeWithText("Log what I ate").performScrollTo().performClick()
        compose.onNodeWithTag("food_log_draft").assertTextContains("A banana yesterday")
        compose.onNodeWithTag("go_home").performClick()
        compose.onNodeWithTag("tab_Today").assertIsSelected()
        compose.onNodeWithText("Log what I ate").performScrollTo().performClick()
        compose.onNodeWithTag("food_log_draft").assertTextContains("A banana yesterday")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("tab_Today").assertIsSelected()
    }

    @Test fun arbitraryDiaryDateSupportsManualAddCorrectionAndAdjacentDayBrowsing() {
        val vm = render()
        openDiary()
        compose.onNodeWithTag("diary_date").performScrollTo().performTextReplacement("2020-02-30")
        compose.onNodeWithText("Show this date").assertIsNotEnabled()
        compose.onNodeWithTag("diary_date").performTextReplacement("2020-02-29")
        compose.onNodeWithText("Show this date").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.runOnIdle { vm.featureStates.value[Feature.FOOD_LOG]?.selectedDate == "2020-02-29" } }
        compose.onNodeWithText("Log food, snack or drink").performScrollTo().performClick()
        compose.onNodeWithText("Diary date: 2020-02-29").assertExists()
        compose.onNodeWithText("Food and portion").performTextInput("One snack")
        compose.onNodeWithText("Total calories, if known").performScrollTo().performTextInput("120")
        compose.onNodeWithText("Package label").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("One snack").assertExists()
        compose.onNodeWithText("Log food").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { vm.state.value.data.intake.size == 1 } }
        compose.onNodeWithText("What did you have?").assertDoesNotExist()
        val original = vm.state.value.data.intake.single()
        assertEquals("2020-02-29", original.date)
        assertEquals("Package label", original.source)
        compose.onNodeWithTag("diary_total").assertTextEquals("120 kcal logged")
        compose.onNodeWithTag("diary_edit_${original.id}").performScrollTo().performClick()
        compose.onNodeWithTag("diary_edit_name").performTextReplacement("Two snacks")
        compose.onNodeWithTag("diary_edit_date").performScrollTo().performTextReplacement("2020-03-01")
        compose.onNodeWithTag("diary_edit_calories").performScrollTo().performTextReplacement("240")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("diary_edit_name").assertTextContains("Two snacks")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { vm.state.value.data.intake.singleOrNull()?.name == "Two snacks" } }
        compose.onNodeWithText("Edit diary entry").assertDoesNotExist()
        compose.onNodeWithTag("diary_total").assertTextEquals("0 kcal logged")
        compose.onNodeWithText("Next day").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.runOnIdle { vm.featureStates.value[Feature.FOOD_LOG]?.selectedDate == "2020-03-01" } }
        compose.onNodeWithTag("diary_total").assertTextEquals("240 kcal logged")
        compose.onNodeWithText("Two snacks").assertExists()
        compose.onNodeWithText("Previous day").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.runOnIdle { vm.featureStates.value[Feature.FOOD_LOG]?.selectedDate == "2020-02-29" } }
        compose.onNodeWithTag("diary_total").assertTextEquals("0 kcal logged")
        val saved = LocalStore(RuntimeEnvironment.getApplication()).read().intake.single()
        assertEquals(original.id, saved.id)
        assertEquals("2020-03-01", saved.date)
        assertEquals("Two snacks", saved.name)
        assertEquals(240.0, requireNotNull(saved.kcal), 0.0)
    }

    @Test fun plannedMealLogValidatesFractionsRestoresAmountAndUpdatesOneDiaryEntry() {
        val meal = Meal(id = "banana-meal", date = LocalDate.now().toString(), slot = "Lunch",
            recipe = Recipe("Banana bowl", listOf(Ingredient("banana", 100.0))), mode = "Hostel", revision = 0, portions = 0.5)
        val vm = render(AppData(meals = listOf(meal)))
        compose.onNodeWithText("Open meal planner").performScrollTo().performClick()
        compose.onNodeWithText("Your meal planner").assertExists()
        compose.onNodeWithText("0.5 serving(s) · Hostel").assertExists()
        compose.onNodeWithTag("plan_log_${meal.id}").performScrollTo().performClick()
        compose.onNodeWithTag("planned_log_portions").assertTextContains("0.5")
        compose.onNodeWithTag("planned_log_portions").performTextReplacement("0")
        compose.onNodeWithTag("planned_log_save").assertIsNotEnabled()
        compose.onNodeWithTag("planned_log_portions").performTextReplacement("21")
        compose.onNodeWithTag("planned_log_save").assertIsNotEnabled()
        compose.onNodeWithTag("planned_log_portions").performTextReplacement("0.5")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("planned_log_portions").assertTextContains("0.5")
        compose.onNodeWithTag("planned_log_save").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.runOnIdle { vm.state.value.data.intake.size == 1 } }
        compose.onNodeWithTag("planned_log_portions").assertDoesNotExist()
        val first = vm.state.value.data.intake.single()
        assertEquals(0.5, first.portions, 0.0)
        assertEquals(meal.id, first.mealId)
        compose.onNodeWithTag("plan_log_${meal.id}").performScrollTo().performClick()
        compose.onNodeWithTag("planned_log_portions").performTextReplacement("2")
        compose.onNodeWithTag("planned_log_save").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.runOnIdle { vm.state.value.data.intake.singleOrNull()?.portions == 2.0 } }
        val saved = LocalStore(RuntimeEnvironment.getApplication()).read().intake.single()
        assertEquals(first.id, saved.id)
        assertEquals(2.0, saved.portions, 0.0)
        assertEquals(0.5, vm.state.value.data.meals.single().portions, 0.0)
    }
}
