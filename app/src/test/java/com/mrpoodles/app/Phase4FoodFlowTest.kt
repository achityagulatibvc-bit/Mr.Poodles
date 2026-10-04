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

/** Integration tests use synthetic extracted evidence, never a live provider or model.generate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase4FoodFlowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val profile = Profile(sourceLookupConsent = true, rememberChats = true, restrictions = emptyList())
    private val source get() = RetrievedSource("fixture", "https://example.org/tiramisu", "Tiramisu recipe",
        "2026-10-04T08:00:00Z", author = "Synthetic Test Kitchen", publisher = "Synthetic fixtures",
        excerpt = requireNotNull(javaClass.getResource("/phase4/tiramisu.txt")).readText(), kind = "recipe")

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for Phase 4 food flow", condition())
    }

    private inner class ResearchModel(hold: Boolean = false) : CloudModel() {
        val release = CompletableDeferred<Unit>().also { if (!hold) it.complete(Unit) }
        val requests = mutableListOf<ResearchRequest>()
        var returned = 0
        override fun cancel() = Unit
        override suspend fun research(input: ResearchRequest): ResearchResponse {
            requests += input
            // Intentionally ignores cancellation: delayed evidence must still fail the commit fence.
            withContext(NonCancellable) { release.await() }
            returned++
            return ResearchResponse(2, input.requestId, input.profileRevision,
                RetrievalSnapshot("fixture-${requests.size}", input.requestId, listOf(source)),
                answer = ResearchAnswer("Let's review the linked recipe together.", emptyList(), emptyList()),
                provider = "synthetic")
        }
        override suspend fun generate(instructions: String, input: String, structured: Boolean, maxTokens: Int,
            task: String, history: List<Message>, status: (String) -> Unit, stream: (String) -> Unit): String =
            error("Food flow must use model.research, not legacy generation")
    }

    private fun seed(data: AppData) {
        val gate = RequestGate()
        val request = gate.begin(data.revision)
        LocalStore(app).save(data) { move -> gate.commit(request, move) }
    }

    private fun start(model: ResearchModel, handle: SavedStateHandle = SavedStateHandle()): PoodlesViewModel =
        PoodlesViewModel(app, handle, model).also { vm -> await { !vm.state.value.loading }; assertFalse(vm.state.value.storageError) }

    private fun oldRecipe(): Recipe {
        val origin = ResultOrigin("previous-request", 0,
            retrieval = RetrievalSnapshot("previous-snapshot", "previous-request", listOf(source)))
        return requireNotNull(PublishedRecipeParser.parse(source, origin))
    }

    private fun obstruct(): File = File(app.filesDir, "poodles-v1.json.pending").apply {
        check(!exists()) { "Only create a new test-owned obstruction" }
        check(mkdir())
        resolve("phase4-test-obstruction").writeText("Synthetic disk-write failure")
    }

    @Test fun successfulRecipePersistsOriginClearsSubmittedDraftAndBusySendsStayIsolated() {
        val check = FeatureConversation(draft = "Ingredients: rice, salt.", error = "Previous check error",
            messages = listOf(Message("You", "Earlier food check")))
        val workout = FeatureConversation(draft = "No jumping", selectedDate = "2026-10-05", scrollIndex = 3)
        val companion = listOf(Message("You", "Private companion conversation"))
        seed(AppData(profile = profile, messages = companion,
            features = mapOf(Feature.FOOD_CHECK to check, Feature.WORKOUT to workout)))
        val model = ResearchModel(hold = true)
        val vm = start(model)
        vm.editRecipeRequest("tiramisu recipe")
        vm.sendFood(Feature.RECIPE)
        try {
            await { model.requests.size == 1 }
            repeat(3) { vm.sendFood(Feature.RECIPE); vm.sendFood(Feature.FOOD_CHECK) }
            assertEquals(1, model.requests.size)
            assertNull(vm.state.value.draft)
            assertEquals(check, vm.featureStates.value.getValue(Feature.FOOD_CHECK))
            vm.editFoodDraft(Feature.FOOD_CHECK, "Ingredients: rice, milk.")
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy }
        val recipe = requireNotNull(vm.state.value.draft)
        val state = vm.featureStates.value.getValue(Feature.RECIPE)
        val disk = LocalStore(app).read()
        assertEquals("", state.draft)
        assertEquals("", vm.recipeRequest.value)
        assertEquals(listOf("You", "Poodles"), state.messages.map { it.role })
        assertEquals("tiramisu recipe", state.messages.first().text)
        assertEquals(recipe, disk.recipeDraft)
        assertEquals(state, disk.features.getValue(Feature.RECIPE))
        assertEquals(model.requests.single().requestId, recipe.origin!!.requestId)
        assertEquals(disk.revision, recipe.origin!!.profileRevision)
        assertEquals(recipe.origin, recipe.sourced!!.origin)
        assertEquals(source.excerpt, recipe.origin!!.retrieval!!.sources.single().excerpt)
        assertTrue(FoodEvidence.valid(recipe.origin!!, recipe.sourced!!.ingredients.flatMap { it.evidence }))
        assertEquals("Ingredients: rice, milk.", vm.featureStates.value.getValue(Feature.FOOD_CHECK).draft)
        assertEquals(check.messages, vm.featureStates.value.getValue(Feature.FOOD_CHECK).messages)
        assertEquals(workout, vm.featureStates.value.getValue(Feature.WORKOUT))
        assertEquals(companion, vm.state.value.data.messages)
        vm.saveDraft()
        await { vm.state.value.draft == null }
        assertEquals(listOf(recipe), LocalStore(app).read().recipes)
        assertEquals(recipe, vm.featureStates.value.getValue(Feature.RECIPE).result!!.recipe)
        val restored = start(ResearchModel())
        assertEquals(recipe, restored.state.value.data.recipes.single())
        assertEquals(state, restored.featureStates.value.getValue(Feature.RECIPE))
    }

    @Test fun failedCompletionAndFailedSaveRetainRecoverableDraftAndPreviousResult() {
        val previous = oldRecipe()
        val initial = FeatureConversation(result = FeatureResult(previous.origin!!, recipe = previous),
            subject = "tiramisu recipe", messages = listOf(Message("Poodles", "Previous result")))
        seed(AppData(profile = profile, recipeDraft = previous, features = mapOf(Feature.RECIPE to initial)))
        val model = ResearchModel(hold = true)
        val vm = start(model)
        vm.editRecipeRequest("tiramisu recipe for two")
        vm.sendFood(Feature.RECIPE)
        await { model.requests.size == 1 }
        val pending = obstruct()
        try {
            model.release.complete(Unit)
            await { !vm.state.value.busy }
            val failed = vm.featureStates.value.getValue(Feature.RECIPE)
            assertNotNull(failed.error)
            assertEquals("tiramisu recipe for two", failed.draft)
            assertEquals(initial.result, failed.result)
            assertEquals(initial.messages, failed.messages)
            assertEquals(previous, vm.state.value.draft)
            assertEquals(previous, LocalStore(app).read().recipeDraft)
            assertEquals(initial.result, LocalStore(app).read().features.getValue(Feature.RECIPE).result)
            assertTrue(LocalStore(app).read().recipes.isEmpty())
        } finally { pending.deleteRecursively() }
        vm.sendFood(Feature.RECIPE)
        await { !vm.state.value.busy }
        val recovered = requireNotNull(vm.state.value.draft)
        assertNotEquals(previous.id, recovered.id)
        assertEquals(2.0, recovered.sourced!!.servings, 0.0)
        assertEquals(2, model.requests.size)
        assertNotEquals(model.requests[0].requestId, model.requests[1].requestId)
        val saveObstruction = obstruct()
        try {
            vm.saveDraft()
            await { vm.state.value.error != null }
            assertEquals(recovered, vm.state.value.draft)
            assertEquals(recovered, LocalStore(app).read().recipeDraft)
            assertTrue(LocalStore(app).read().recipes.isEmpty())
            assertEquals(recovered, vm.featureStates.value.getValue(Feature.RECIPE).result!!.recipe)
        } finally { saveObstruction.deleteRecursively() }
        vm.saveDraft()
        await { vm.state.value.draft == null }
        assertEquals(listOf(recovered), LocalStore(app).read().recipes)
    }

    @Test fun noncooperativeResearchAfterProfileChangeCannotPublishOrPersistOldRecipe() {
        val previous = oldRecipe()
        seed(AppData(profile = profile, recipeDraft = previous))
        val model = ResearchModel(hold = true)
        val vm = start(model)
        vm.editRecipeRequest("tiramisu recipe")
        vm.sendFood(Feature.RECIPE)
        try {
            await { model.requests.size == 1 }
            vm.saveProfile(vm.state.value.data.profile.copy(name = "Changed profile"))
            await { !vm.state.value.profileSaving }
            assertEquals(1, vm.state.value.data.revision)
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy }
        assertEquals(1, model.returned)
        assertEquals(previous, vm.state.value.draft)
        assertEquals(previous, LocalStore(app).read().recipeDraft)
        assertEquals("Changed profile", LocalStore(app).read().profile.name)
        assertNull(vm.featureStates.value.getValue(Feature.RECIPE).result)
        assertTrue(vm.featureStates.value.getValue(Feature.RECIPE).messages.isEmpty())
        assertNull(LocalStore(app).read().features.getValue(Feature.RECIPE).result)
    }

    @Test fun noncooperativeResearchAfterInputEditCannotClearNewDraftOrCommitOldResult() {
        seed(AppData(profile = profile))
        val handle = SavedStateHandle()
        val model = ResearchModel(hold = true)
        val vm = start(model, handle)
        vm.editRecipeRequest("tiramisu recipe")
        vm.sendFood(Feature.RECIPE)
        try {
            await { model.requests.size == 1 }
            vm.editRecipeRequest("potato salad recipe")
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy }
        assertEquals(1, model.returned)
        val edited = vm.featureStates.value.getValue(Feature.RECIPE)
        assertEquals("potato salad recipe", edited.draft)
        assertNull(edited.result)
        assertNull(edited.activeRequest)
        assertNull(edited.retryRequest)
        assertNull(edited.error)
        assertTrue(edited.messages.isEmpty())
        assertNull(LocalStore(app).read().recipeDraft)
        assertNull(LocalStore(app).read().features.getValue(Feature.RECIPE).result)
        val restoredHandle = SavedStateHandle(mapOf("feature_RECIPE" to handle.get<String>("feature_RECIPE")))
        assertEquals(edited, start(ResearchModel(), restoredHandle).featureStates.value.getValue(Feature.RECIPE))
    }

    @Test fun sourceLookupRequiresExplicitConsentAndOptionalFallbackSurvivesPreferenceSave() {
        val pending = FeatureConversation(subject = "tiramisu recipe", messages = listOf(
            Message("You", "tiramisu recipe"), Message("Poodles", "Please share another source."),
            Message("You", "Make two servings"), Message("Poodles", "Please share another source.")))
        seed(AppData(profile = profile.copy(sourceLookupConsent = false, externalModelConsent = false),
            features = mapOf(Feature.RECIPE to pending)))
        val model = ResearchModel()
        val vm = start(model)
        vm.editRecipeRequest("tiramisu recipe")
        vm.sendFood(Feature.RECIPE)
        await { !vm.state.value.busy }
        assertTrue(model.requests.isEmpty())
        assertNotNull(vm.featureStates.value.getValue(Feature.RECIPE).error)
        assertEquals("tiramisu recipe", vm.featureStates.value.getValue(Feature.RECIPE).draft)
        vm.allowFoodSources(false)
        await { !vm.state.value.profileSaving }
        assertTrue(LocalStore(app).read().profile.sourceLookupConsent)
        assertFalse(LocalStore(app).read().profile.externalModelConsent)
        vm.editRecipeRequest("No peanuts")
        vm.sendFood(Feature.RECIPE)
        await { !vm.state.value.busy }
        assertFalse(model.requests.single().allowExternalModel)
        // Persisted conversation roles are You/Poodles, not API user/assistant roles.
        assertEquals(2.0, requireNotNull(vm.state.value.draft?.sourced).servings, 0.0)
        vm.allowFoodSources(true)
        await { !vm.state.value.profileSaving }
        vm.saveProfile(vm.state.value.data.profile.copy(cuisine = "Indian"))
        await { !vm.state.value.profileSaving }
        val restored = start(model)
        assertTrue(restored.state.value.data.profile.sourceLookupConsent)
        assertTrue(restored.state.value.data.profile.externalModelConsent)
        assertEquals("Indian", restored.state.value.data.profile.cuisine)
        restored.editRecipeRequest("tiramisu recipe")
        restored.sendFood(Feature.RECIPE)
        await { !restored.state.value.busy }
        assertEquals(2, model.requests.size)
        assertTrue(model.requests.last().allowExternalModel)
    }
}
