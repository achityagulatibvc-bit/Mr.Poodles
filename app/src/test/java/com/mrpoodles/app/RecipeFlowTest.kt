package com.mrpoodles.app

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecipeFlowTest {
    private fun recipe(name: String, vararg ids: String) = Recipe(name, ids.map { Ingredient(it, 70.0) }, aiGenerated = true,
        preparation = listOf(PreparationStep("mix", ids.toList())))
    private val options = listOf(recipe("Pasta salad", "pasta", "tomato"), recipe("Potato mash", "potato", "pea"), recipe("Chickpea sandwich", "chickpea", "bread"))
    private class FakeRecipes(var recipes: List<Recipe>) : CloudModel() {
        val calls = mutableListOf<String>()
        override suspend fun generate(instructions: String, input: String, structured: Boolean, maxTokens: Int,
            task: String, history: List<Message>, image: String?, status: (String) -> Unit, stream: (String) -> Unit): String {
            calls += task
            return if (task == "recipe") json.encodeToString(RecipeBatch.serializer(), RecipeBatch(recipes))
                else json.encodeToString(GeneratedMenu.serializer(), GeneratedMenu(List(7) { day -> MenuDay(day % 3, (day + 1) % 3, (day + 2) % 3) }))
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("Timed out waiting for recipes", condition())
    }
    @Test fun requestMatchingSelectsAnActualPastaRecipeAndRejectsRepeats() {
        val app = RuntimeEnvironment.getApplication()
        LocalStore(app).save(AppData(profile = Profile(onboarding = true)))
        val fake = FakeRecipes(listOf(options[1], options[0]))
        val vm = PoodlesViewModel(app, SavedStateHandle(), fake)
        await { !vm.state.value.loading }
        vm.makeRecipe("pasta without spinach")
        await { !vm.state.value.busy && vm.state.value.draft != null }
        assertEquals("Pasta salad", vm.state.value.draft!!.title)
        assertEquals(1, vm.state.value.data.recentRecipes.size)
        vm.makeRecipe("pasta without spinach")
        await { !vm.state.value.busy && vm.state.value.error != null }
        assertNull(vm.state.value.draft)
        assertTrue(vm.state.value.error!!.contains("repeated"))
        assertEquals(2, fake.calls.size)
    }
    @Test fun sevenDayPlanningUsesOneCallWhenRecipesAlreadyExist() {
        val app = RuntimeEnvironment.getApplication()
        val intake = Intake(date = "2026-10-03", name = "My lunch", kcal = null)
        LocalStore(app).save(AppData(profile = Profile(onboarding = true), recipes = options, intake = listOf(intake)))
        val fake = FakeRecipes(options)
        val vm = PoodlesViewModel(app, SavedStateHandle(), fake)
        await { !vm.state.value.loading }
        vm.makePlan("2026-10-03", 7)
        await { !vm.state.value.busy && vm.state.value.data.meals.size == 21 }
        assertEquals(listOf("plan"), fake.calls)
        assertEquals(listOf(intake), vm.state.value.data.intake)
    }
    @Test fun emptyRecipeShelfGetsAIGeneratedChoicesNotPresetOatsOrSpinach() {
        val app = RuntimeEnvironment.getApplication()
        LocalStore(app).save(AppData(profile = Profile(onboarding = true)))
        val fake = FakeRecipes(options)
        val vm = PoodlesViewModel(app, SavedStateHandle(), fake)
        await { !vm.state.value.loading }
        vm.makePlan("2026-10-03", 7)
        await { !vm.state.value.busy && vm.state.value.data.meals.size == 21 }
        assertEquals(listOf("recipe", "plan"), fake.calls)
        assertTrue(vm.state.value.data.meals.all { it.recipe.aiGenerated })
    }
}
