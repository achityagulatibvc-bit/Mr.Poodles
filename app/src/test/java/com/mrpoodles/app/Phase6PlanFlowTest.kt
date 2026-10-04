package com.mrpoodles.app

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase6PlanFlowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val profile = Profile(sourceLookupConsent = true, restrictions = emptyList())
    private val day = LocalDate.now().toString()

    private fun source(food: String, title: String = "$food salad") = RetrievedSource(
        "fixture-$food", "https://example.org/synthetic-$food", title, "2026-10-04T08:00:00Z",
        excerpt = """
            Serves: 4
            Prep: 5 minutes
            Ingredients
            400 g $food
            200 g cucumber
            Method
            Wash and slice the $food and cucumber. Combine in a clean bowl and serve straight away.
        """.trimIndent(), kind = "recipe")

    private fun recipe(food: String): Recipe {
        val source = source(food)
        val origin = ResultOrigin("request-$food", 0,
            retrieval = RetrievalSnapshot("snapshot-$food", "request-$food", listOf(source)))
        return requireNotNull(PublishedRecipeParser.parse(source, origin)).copy(id = "recipe-$food")
    }

    private inner class Model(hold: Boolean = false, val failSecond: Boolean = false) : CloudModel() {
        val release = CompletableDeferred<Unit>().also { if (!hold) it.complete(Unit) }
        val requests = CopyOnWriteArrayList<ResearchRequest>()
        override suspend fun research(input: ResearchRequest): ResearchResponse {
            requests += input
            release.await()
            if (failSecond && requests.size == 2) throw IOException("Synthetic second source lookup failed")
            // The planner's first requested title is matched, with a synthetic complete recipe.
            val source = source("banana", "Overnight oats")
            return ResearchResponse(2, input.requestId, input.profileRevision,
                RetrievalSnapshot("lookup-${requests.size}", input.requestId, listOf(source)),
                answer = ResearchAnswer("Let's review this published recipe.", emptyList(), emptyList()), provider = "synthetic")
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for Phase 6 planner flow", condition())
    }
    private fun start(model: Model, data: AppData): PoodlesViewModel {
        LocalStore(app).save(data)
        return PoodlesViewModel(app, SavedStateHandle(), model).also { vm ->
            await { !vm.state.value.loading }
            assertFalse(vm.state.value.storageError)
        }
    }
    private fun disk() = LocalStore(app).read()

    @Test fun mealEditDuringSourceLookupIsPreservedAndRejectsStalePreviewApplication() {
        val apple = recipe("apple")
        val pear = recipe("pear")
        val original = Meal(id = "planned", date = day, slot = "Lunch", recipe = apple,
            mode = profile.mode, revision = 0)
        val model = Model(hold = true)
        val vm = start(model, AppData(profile = profile, recipes = listOf(apple, pear), meals = listOf(original)))
        vm.previewPlan(day, 1)
        try {
            await { model.requests.size == 1 }
            vm.moveMeal(original.id, day, "Dinner", 2.0)
            await { vm.state.value.successSequence == 1 }
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy }
        assertNull(vm.state.value.error)
        val preview = requireNotNull(vm.state.value.planPreview)
        assertEquals(listOf(original), preview.expectedMeals)
        assertEquals(3, preview.meals.size)
        val edited = original.copy(slot = "Dinner", portions = 2.0)
        assertEquals(listOf(edited), disk().meals)
        assertEquals(preview, disk().pendingPlan)
        vm.applyPlanPreview()
        await { vm.state.value.error != null }
        assertTrue(vm.state.value.error!!.contains("meals changed"))
        assertEquals(listOf(edited), disk().meals)
        assertEquals(preview, disk().pendingPlan)
        assertEquals(1, vm.state.value.successSequence)
    }

    @Test fun partialSourceLookupFailureKeepsExistingMealsAndFirstRetrievedRecipeAcrossReload() {
        val original = Meal(id = "planned", date = day, slot = "Lunch", recipe = recipe("apple"),
            mode = profile.mode, revision = 0)
        val model = Model(failSecond = true)
        val vm = start(model, AppData(profile = profile, meals = listOf(original)))
        vm.previewPlan(day, 1)
        await { !vm.state.value.busy }
        assertEquals(2, model.requests.size)
        assertTrue(model.requests.all { it.task == "recipe" })
        assertEquals("Synthetic second source lookup failed", vm.state.value.error)
        assertEquals(listOf(original), disk().meals)
        assertNull(disk().pendingPlan)
        val retained = disk().recentRecipes.single()
        assertEquals("Overnight oats", retained.title)
        assertNotNull(retained.sourced)
        assertEquals(model.requests.first().requestId, retained.origin!!.requestId)
        assertTrue(SourcedRecipeRules.validate(retained, profile).isEmpty())
        val restored = PoodlesViewModel(app, SavedStateHandle(), Model())
        await { !restored.state.value.loading }
        assertEquals(listOf(original), restored.state.value.data.meals)
        assertEquals(listOf(retained), restored.state.value.data.recentRecipes)
        assertNull(restored.state.value.planPreview)
    }
}
