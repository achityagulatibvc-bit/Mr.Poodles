package com.mrpoodles.app

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FoundationPersistenceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val recipe = Recipe("Banana bowl", listOf(Ingredient("banana", 100.0)), id = "draft")
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("Timed out waiting for foundation state", condition())
    }
    private class DelayedModel : CloudModel() {
        val result = CompletableDeferred<String>()
        var started = false
        override fun cancel() = Unit
        override suspend fun generate(instructions: String, input: String, structured: Boolean, maxTokens: Int,
            task: String, history: List<Message>, status: (String) -> Unit, stream: (String) -> Unit): String {
            started = true
            // Deliberately ignores cancellation to exercise the ownership fence, not only cooperative cancellation.
            return withContext(NonCancellable) { result.await() }
        }
    }
    private fun obstruct(): File = File(app.filesDir, "poodles-v1.json.pending").apply {
        mkdirs(); resolve("obstruction").writeText("test")
    }

    @Test fun failedRecipeSaveRetainsDraftInMemoryAndOnDiskThenRetryClearsOnlyAfterCommit() {
        val store = LocalStore(app)
        store.save(AppData(recipeDraft = recipe))
        val vm = PoodlesViewModel(app, SavedStateHandle())
        await { !vm.state.value.loading }
        val pending = obstruct()
        try {
            vm.saveDraft()
            await { vm.state.value.error != null }
            assertEquals(recipe, vm.state.value.draft)
            assertEquals(recipe, store.read().recipeDraft)
            assertTrue(store.read().recipes.isEmpty())
            assertEquals(0, vm.state.value.successSequence)
        } finally { pending.deleteRecursively() }
        vm.saveDraft()
        await { vm.state.value.draft == null }
        assertEquals(listOf(recipe), store.read().recipes)
        assertNull(store.read().recipeDraft)
        assertEquals(1, vm.state.value.successSequence)
    }

    @Test fun failedIntakeSaveHasNoReceiptOrSuccessAndRepeatedRetryPersistsOnce() {
        val store = LocalStore(app)
        store.save(AppData())
        val vm = PoodlesViewModel(app, SavedStateHandle())
        await { !vm.state.value.loading }
        val entry = Intake(id = "food", date = "2026-10-03", name = "Snack", kcal = null)
        val operation = IntakeOperation("intent", entry.id, entry = entry)
        var successes = 0
        val pending = obstruct()
        try {
            vm.applyIntake(operation) { successes++ }
            await { vm.state.value.error != null }
            assertTrue(store.read().intakeOperations.isEmpty())
            assertTrue(vm.state.value.data.intake.isEmpty())
            assertEquals(0, successes)
        } finally { pending.deleteRecursively() }
        vm.applyIntake(operation) { successes++ }
        vm.applyIntake(operation) { successes++ }
        await { successes == 2 }
        assertEquals(listOf(entry), store.read().intake)
        assertEquals(1, store.read().intakeOperations.size)
    }

    @Test fun cancellationAtFinalMovePreservesPreviouslyCommittedSnapshot() {
        val store = LocalStore(app)
        val original = AppData(recipeDraft = recipe)
        store.save(original)
        val gate = RequestGate()
        val request = gate.begin(0)
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            store.save(original.copy(recipes = listOf(recipe), recipeDraft = null)) { move ->
                gate.invalidate()
                gate.commit(request, move)
            }
        }
        assertEquals(original, store.read())
        assertFalse(File(app.filesDir, "poodles-v1.json.pending").exists())
    }

    @Test fun lateRecipeAfterProfileEditCannotCommitAndOldDraftSurvivesRecreation() {
        val store = LocalStore(app)
        store.save(AppData(recipeDraft = recipe))
        val model = DelayedModel()
        val vm = PoodlesViewModel(app, SavedStateHandle(), model)
        await { !vm.state.value.loading }
        vm.makeRecipe("banana")
        await { model.started }
        vm.saveProfile(vm.state.value.data.profile.copy(name = "New profile"))
        await { !vm.state.value.profileSaving }
        model.result.complete(json.encodeToString(RecipeBatch.serializer(), RecipeBatch(listOf(recipe.copy(title = "Late banana")))))
        await { !vm.state.value.busy }
        assertEquals(recipe, vm.state.value.draft)
        assertTrue(store.read().recentRecipes.isEmpty())
        assertEquals("New profile", store.read().profile.name)
        val recreated = PoodlesViewModel(app, SavedStateHandle())
        await { !recreated.state.value.loading }
        assertEquals(recipe, recreated.state.value.draft)
    }

    @Test fun concurrentCalendarEditIsNotOverwrittenByLatePlan() {
        val recipes = listOf(recipe, Recipe("Potato", listOf(Ingredient("potato", 100.0))),
            Recipe("Pasta", listOf(Ingredient("pasta", 100.0))))
        val store = LocalStore(app)
        store.save(AppData(recipes = recipes))
        val model = DelayedModel()
        val vm = PoodlesViewModel(app, SavedStateHandle(), model)
        await { !vm.state.value.loading }
        vm.makePlan("2026-10-03")
        await { model.started }
        vm.planRecipe(recipe, "2026-10-03", "Lunch")
        await { vm.state.value.data.meals.size == 1 }
        val edited = vm.state.value.data.meals
        model.result.complete("{\"days\":[{\"breakfast\":0,\"lunch\":1,\"dinner\":2}]}")
        await { !vm.state.value.busy }
        assertEquals(edited, store.read().meals)
        assertTrue(vm.state.value.error!!.contains("plan changed"))
    }

    @Test fun realLegacyFileAndAtomicFileBackupMigrateWithoutLosingHistoricalData() {
        val text = javaClass.getResource("/snapshots/historical-v1.json")!!.readText()
        val expected = SnapshotMigration.decode(text)
        val file = File(app.filesDir, "poodles-v1.json")
        file.writeText(text)
        val store = LocalStore(app)
        assertEquals(expected, store.read())
        assertEquals(text, file.readText())
        store.save(expected)
        assertEquals(expected, LocalStore(app).read())
        File(app.filesDir, "poodles-v1.json.bak").writeText(text)
        file.writeText("interrupted old AtomicFile write")
        assertEquals(expected, LocalStore(app).read())
    }

    @Test fun featureRecoveryRetainsResultDraftDateAndScrollButNeverResumesOldNetworkWork() {
        val request = RequestIdentity("interrupted", 0, operationId = "log-intent")
        val feature = FeatureConversation(draft = "I ate two", selectedDate = "2026-09-30", scrollIndex = 4, scrollOffset = 17).begin(request)
        LocalStore(app).save(AppData(features = mapOf(Feature.FOOD_LOG to feature)))
        val restored = LocalStore(app).read().features.getValue(Feature.FOOD_LOG)
        assertNull(restored.activeRequest)
        assertEquals(request, restored.retryRequest)
        assertEquals(feature.draft, restored.draft)
        assertEquals(feature.selectedDate, restored.selectedDate)
        assertEquals(feature.scrollIndex, restored.scrollIndex)
        assertEquals(feature.scrollOffset, restored.scrollOffset)
    }
}
