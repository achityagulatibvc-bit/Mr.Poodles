package com.mrpoodles.app

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** Real ViewModels, raw instance-state strings and atomic disk snapshots; research is synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase7StateFlowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val profile = Profile(sourceLookupConsent = true, rememberChats = true, restrictions = emptyList())
    private val day = LocalDate.now().minusDays(2).toString()
    private val stores = mutableMapOf<PoodlesViewModel, ViewModelStore>()
    private val nutritionSource get() = RetrievedSource("nutrition", "https://tools.myfooddata.com/fixture/kachori",
        "Pyaaz kachori nutrition", "2026-10-04T10:00:00Z",
        author = "Synthetic Fixture Author", publisher = "Synthetic Test Publisher",
        excerpt = requireNotNull(javaClass.getResource("/phase6/kachori-nutrition.txt")).readText(), kind = "nutrition")

    private abstract class FakeModel : CloudModel() {
        val requests = CopyOnWriteArrayList<ResearchRequest>()
        var generationCalls = 0
        override fun cancel() = Unit
        final override suspend fun generate(instructions: String, input: String, structured: Boolean, maxTokens: Int,
            task: String, history: List<Message>, status: (String) -> Unit, stream: (String) -> Unit): String {
            generationCalls++
            error("These state flows must not use legacy generation")
        }
    }

    private class NoNetworkModel : FakeModel() {
        override suspend fun research(input: ResearchRequest): ResearchResponse {
            requests += input
            error("Restoration and exact operation replay must not research again")
        }
    }

    private inner class FoodModel : FakeModel() {
        val release = CompletableDeferred<Unit>()
        override suspend fun research(input: ResearchRequest): ResearchResponse {
            requests += input
            release.await()
            return response(input, nutritionSource)
        }
    }

    private inner class PlannerModel(private val unusableCalls: Int) : FakeModel() {
        val offsetsAtLookup = CopyOnWriteArrayList<Int>()
        override suspend fun research(input: ResearchRequest): ResearchResponse {
            requests += input
            // Observe durable progress inside the provider call, before returning any result.
            offsetsAtLookup += rawDisk().planSearchOffset
            val title = input.subject.removeSuffix(" recipe")
            val food = listOf("banana", "lentils", "pasta")[(requests.size - 1) % 3]
            val excerpt = if (requests.size <= unusableCalls) {
                "Synthetic incomplete recipe fixture. This page discusses $title but does not provide " +
                    "ingredient quantities, serving yield or a complete preparation method. Do not infer a recipe from this page."
            } else {
                """
                    Serves: 4
                    Prep: 5 minutes
                    Ingredients
                    400 g $food
                    200 g cucumber
                    Method
                    Wash and slice the $food and cucumber. Combine in a clean bowl and serve straight away.
                """.trimIndent()
            }
            return response(input, RetrievedSource("recipe-${requests.size}",
                "https://example.org/synthetic-${title.replace(' ', '-')}", title, "2026-10-04T08:00:00Z",
                excerpt = excerpt, kind = "recipe"))
        }
    }

    private fun response(request: ResearchRequest, source: RetrievedSource) = ResearchResponse(
        2, request.requestId, request.profileRevision,
        RetrievalSnapshot("fixture-${request.requestId}", request.requestId, listOf(source)),
        answer = ResearchAnswer("Let's review the retrieved evidence together.", emptyList(), emptyList()), provider = "synthetic")

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for Phase 7 state flow", condition())
    }

    private fun restore(model: FakeModel, handle: SavedStateHandle = SavedStateHandle()): PoodlesViewModel {
        val vm = PoodlesViewModel(app, handle, model)
        stores[vm] = ViewModelStore().apply { put("poodles", vm) }
        await { !vm.state.value.loading && vm.featureStates.value.size == Feature.entries.size }
        assertFalse(vm.state.value.storageError)
        return vm
    }

    private fun start(data: AppData, model: FakeModel, handle: SavedStateHandle = SavedStateHandle()): PoodlesViewModel {
        LocalStore(app).save(data)
        return restore(model, handle)
    }

    private fun retire(vm: PoodlesViewModel) { stores.remove(vm)?.clear() }

    @After fun clearViewModels() {
        stores.values.forEach { it.clear() }
        stores.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // Bypass migration/recovery and privacy filters so assertions inspect bytes actually persisted.
    private fun rawDisk(): AppData = json.decodeFromString(File(app.filesDir, "poodles-v1.json").readText())
    private fun encoded(value: FeatureConversation) = json.encodeToString(FeatureConversation.serializer(), value)
    private fun stored(handle: SavedStateHandle, feature: Feature): FeatureConversation =
        json.decodeFromString(requireNotNull(handle.get<String>("feature_${feature.name}")))
    private fun log(vm: PoodlesViewModel) = vm.featureStates.value.getValue(Feature.FOOD_LOG)
    private fun assertNoNetwork(model: FakeModel) {
        assertTrue("Unexpected research calls: ${model.requests}", model.requests.isEmpty())
        assertEquals(0, model.generationCalls)
    }

    /** Capture the actual handle before preparation, then let the same ViewModel commit normally. */
    private fun completeLogAfterInstanceSnapshot(): Pair<String, AppData> {
        val model = FoodModel()
        val handle = SavedStateHandle()
        val vm = start(AppData(profile = profile), model, handle)
        vm.editFoodDraft(Feature.FOOD_LOG, "Pyaaz kachori khayi hai on $day")
        vm.sendFoodLog()
        val captured: String
        try {
            await { model.requests.size == 1 }
            captured = requireNotNull(handle.get<String>("feature_FOOD_LOG"))
            val pending = json.decodeFromString<FeatureConversation>(captured)
            assertNotNull(pending.activeRequest)
            assertNull(pending.preparedLog)
            assertTrue(rawDisk().intake.isEmpty())
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy }
        assertNull(vm.state.value.error)
        assertNull(log(vm).error)
        val committed = rawDisk()
        assertEquals(1, committed.intake.size)
        assertEquals(200.0, committed.intake.single().kcal!!, 0.001)
        assertEquals(day, committed.intake.single().date)
        assertNotNull(committed.features.getValue(Feature.FOOD_LOG).lastFoodLog)
        assertEquals(1, model.requests.size)
        retire(vm)
        return captured to committed
    }

    @Test fun rawStaleSavedStateRestoresCommittedLogReusesReceiptWithoutNetworkAndKeepsUndo() {
        val (captured, committed) = completeLogAfterInstanceSnapshot()
        val completed = committed.features.getValue(Feature.FOOD_LOG)
        val handle = SavedStateHandle(mapOf("feature_FOOD_LOG" to captured))
        val model = NoNetworkModel()
        val vm = restore(model, handle)
        assertEquals(completed, log(vm))
        assertEquals(completed, stored(handle, Feature.FOOD_LOG))
        assertEquals(committed.intakeOperations, vm.state.value.data.intakeOperations)
        assertTrue(log(vm).messages.any { it.text.contains("Saved in your diary.") })

        // The obsolete retry action is now a no-op because the completed draft is empty.
        vm.sendFoodLog()
        assertFalse(vm.state.value.busy)
        assertEquals(committed, rawDisk())
        val original = requireNotNull(log(vm).lastFoodLog)
        var replayed = false
        vm.applyIntake(original.operations.single()) { replayed = true }
        await { replayed }
        assertEquals(committed.intake, rawDisk().intake)
        assertEquals(committed.intakeOperations, rawDisk().intakeOperations)
        assertEquals(completed, log(vm))
        assertNoNetwork(model)

        vm.undoFoodLog()
        await { log(vm).lastFoodLog == null }
        assertTrue(rawDisk().intake.isEmpty())
        assertNull(log(vm).result)
        assertEquals(committed.intakeOperations, rawDisk().intakeOperations.take(committed.intakeOperations.size))
        assertEquals(committed.intakeOperations.size + 1, rawDisk().intakeOperations.size)
        assertNoNetwork(model)
    }

    @Test fun newerTypedSavedDraftMergesWithDurableCompletionInsteadOfOldRetryState() {
        val (captured, committed) = completeLogAfterInstanceSnapshot()
        val pending = json.decodeFromString<FeatureConversation>(captured)
        val newer = pending.edit("I also ate an apple").copy(selectedDate = day, scrollIndex = 2, scrollOffset = 19)
        val handle = SavedStateHandle(mapOf("feature_FOOD_LOG" to encoded(newer)))
        val model = NoNetworkModel()
        val vm = restore(model, handle)
        val completed = committed.features.getValue(Feature.FOOD_LOG)
        assertEquals(newer.draft, log(vm).draft)
        assertEquals(newer.inputRevision, log(vm).inputRevision)
        assertEquals(2, log(vm).scrollIndex)
        assertEquals(19, log(vm).scrollOffset)
        assertEquals(completed.result, log(vm).result)
        assertEquals(completed.lastFoodLog, log(vm).lastFoodLog)
        assertEquals(completed.messages, log(vm).messages)
        assertNull(log(vm).activeRequest)
        assertNull(log(vm).retryRequest)
        assertNull(log(vm).preparedLog)
        assertNull(log(vm).error)
        assertEquals(log(vm), stored(handle, Feature.FOOD_LOG))
        assertEquals(committed, rawDisk())
        assertNoNetwork(model)
    }

    @Test fun preparedCorrectionFromDiskRetriesExactDateRevisionAndEvidenceWithoutResearch() {
        val request = RequestIdentity("original-attempt", 4, 7, "correction-action")
        val origin = ResultOrigin(request.id, request.profileRevision, listOf(UserAssertion("I ate two")),
            RetrievalSnapshot("original-evidence", request.id, listOf(nutritionSource)))
        val previous = Intake("existing-food", day, "Pyaaz kachori", 200.0, entryRevision = 2,
            portion = Portion(1.0, "piece", "medium kachori"))
        val corrected = previous.copy(kcal = 400.0, portions = 2.0, entryRevision = 3, origin = origin,
            portion = previous.portion!!.copy(amount = 2.0))
        val operation = IntakeOperation("correction-action:update:${previous.id}", previous.id, 2, corrected)
        val prepared = PreparedFoodLog(request, "The same diary entry now has two pieces.",
            listOf(corrected), listOf(operation), listOf(previous), origin)
        val pending = FeatureConversation(draft = "I ate two", inputRevision = 7, activeRequest = request,
            retryRequest = request, requestDate = day)
        val initial = AppData(profile = profile, revision = 4, intake = listOf(previous))
        val expected = IntakeOperations.apply(initial, operation)
        val handle = SavedStateHandle(mapOf("feature_FOOD_LOG" to encoded(pending)))
        val model = NoNetworkModel()
        val vm = start(initial.copy(features = mapOf(Feature.FOOD_LOG to pending.copy(preparedLog = prepared))), model, handle)
        assertEquals(prepared, log(vm).preparedLog)
        assertEquals(day, log(vm).requestDate)
        assertNotNull(log(vm).error)
        vm.sendFoodLog()
        await { !vm.state.value.busy }
        assertNull(vm.state.value.error)
        assertNull(log(vm).error)
        assertEquals(expected.intake, rawDisk().intake)
        assertEquals(expected.intakeOperations, rawDisk().intakeOperations)
        assertEquals(prepared, log(vm).lastFoodLog)
        assertEquals(origin, rawDisk().intake.single().origin)
        assertEquals(day, log(vm).selectedDate)
        assertNull(log(vm).preparedLog)
        assertEquals(log(vm), stored(handle, Feature.FOOD_LOG))
        assertNoNetwork(model)

        var replayed = false
        vm.applyIntake(operation) { replayed = true }
        await { replayed }
        assertEquals(expected.intake, rawDisk().intake)
        assertEquals(expected.intakeOperations, rawDisk().intakeOperations)
        vm.undoFoodLog()
        await { log(vm).lastFoodLog == null }
        assertEquals(listOf(previous.copy(entryRevision = 4)), rawDisk().intake)
        assertNoNetwork(model)
    }

    @Test fun revokingChatStorageSanitizesMemoryRawDiskAndOriginalHandleWithoutLosingCurrentDrafts() {
        val features = Feature.entries.associateWith { feature ->
            FeatureConversation(draft = "Old disk ${feature.name}", inputRevision = 1,
                messages = listOf(Message("You", "Private ${feature.name} history")),
                result = FeatureResult(ResultOrigin("result-${feature.name}", 0)))
        }
        val handle = SavedStateHandle(features.mapKeys { "feature_${it.key.name}" }.mapValues { encoded(it.value) })
        val model = NoNetworkModel()
        val vm = start(AppData(profile = profile, features = features,
            messages = listOf(Message("You", "Private companion history"))), model, handle)
        Feature.entries.forEach { feature ->
            assertTrue(stored(handle, feature).messages.isNotEmpty())
            vm.editFoodDraft(feature, "Current unsaved ${feature.name} draft")
        }
        val current = vm.featureStates.value
        assertEquals(features, rawDisk().features)
        vm.saveProfile(profile.copy(rememberChats = false))
        await { vm.state.value.profileSaveSequence == 1 && !vm.state.value.profileSaving }
        assertNull(vm.state.value.error)
        assertFalse(rawDisk().profile.rememberChats)
        assertTrue(vm.state.value.data.messages.isEmpty())
        assertTrue(rawDisk().messages.isEmpty())
        Feature.entries.forEach { feature ->
            val expected = current.getValue(feature).copy(messages = emptyList())
            assertEquals(expected, vm.featureStates.value.getValue(feature))
            assertEquals(expected, vm.state.value.data.features.getValue(feature))
            assertEquals(expected, rawDisk().features.getValue(feature))
            assertEquals(expected, stored(handle, feature))
        }
        retire(vm)
        val recreated = restore(model, handle)
        Feature.entries.forEach { feature ->
            assertEquals(current.getValue(feature).copy(messages = emptyList()), recreated.featureStates.value.getValue(feature))
            assertTrue(stored(handle, feature).messages.isEmpty())
        }
        assertNoNetwork(model)
    }

    @Test fun startupWithRevokedConsentSanitizesRawStaleSavedHistoriesForEveryFeature() {
        val diskFeatures = Feature.entries.associateWith { FeatureConversation(draft = "Disk ${it.name}", inputRevision = 1) }
        val stale = Feature.entries.associateWith { feature ->
            FeatureConversation(draft = "Newer ${feature.name}", inputRevision = 2,
                messages = listOf(Message("You", "History captured before revocation")))
        }
        val handle = SavedStateHandle(stale.mapKeys { "feature_${it.key.name}" }.mapValues { encoded(it.value) })
        val model = NoNetworkModel()
        val vm = start(AppData(profile = profile.copy(rememberChats = false), features = diskFeatures), model, handle)
        Feature.entries.forEach { feature ->
            val expected = stale.getValue(feature).copy(messages = emptyList())
            assertEquals(expected, vm.featureStates.value.getValue(feature))
            assertEquals(expected, stored(handle, feature))
            assertTrue(rawDisk().features.getValue(feature).messages.isEmpty())
        }
        assertNoNetwork(model)
    }

    @Test fun ordinaryProfileSaveUsesCurrentFeatureDraftsRatherThanStaleDiskCopies() {
        val diskFeatures = Feature.entries.associateWith { FeatureConversation(draft = "Old ${it.name}", inputRevision = 3) }
        val handle = SavedStateHandle()
        val model = NoNetworkModel()
        val vm = start(AppData(profile = profile, features = diskFeatures), model, handle)
        Feature.entries.forEach { vm.editFoodDraft(it, "Keep current ${it.name} draft") }
        val current = vm.featureStates.value
        assertEquals(diskFeatures, rawDisk().features)
        vm.saveProfile(profile.copy(name = "Updated profile"))
        await { vm.state.value.profileSaveSequence == 1 && !vm.state.value.profileSaving }
        assertNull(vm.state.value.error)
        assertEquals(current, vm.featureStates.value)
        assertEquals(current, vm.state.value.data.features)
        assertEquals(current, rawDisk().features)
        Feature.entries.forEach { assertEquals(current.getValue(it), stored(handle, it)) }
        retire(vm)
        assertEquals(current, restore(model).featureStates.value)
        assertNoNetwork(model)
    }

    @Test fun threeUnusableCandidatesAdvanceBeforeLookupAndNextPreviewUsesNewQueriesAfterRecreation() {
        val original = Meal(id = "existing-meal", date = day, slot = "Lunch",
            recipe = Recipe("Existing meal", emptyList()), mode = profile.mode, revision = 0)
        val firstModel = PlannerModel(unusableCalls = 3)
        val vm = start(AppData(profile = profile, meals = listOf(original)), firstModel)
        vm.previewPlan(day, 1)
        await { !vm.state.value.busy }
        val firstQueries = firstModel.requests.map { it.subject }
        assertEquals(3, firstQueries.size)
        assertEquals(3, firstQueries.distinct().size)
        assertEquals(listOf(1, 2, 3), firstModel.offsetsAtLookup)
        assertEquals(3, rawDisk().planSearchOffset)
        assertTrue(rawDisk().recentRecipes.isEmpty())
        assertNull(rawDisk().pendingPlan)
        assertNull(vm.state.value.planPreview)
        assertNotNull(vm.state.value.error)
        assertEquals(listOf(original), rawDisk().meals)
        retire(vm)

        val secondModel = PlannerModel(unusableCalls = 0)
        val recreated = restore(secondModel)
        assertEquals(3, recreated.state.value.data.planSearchOffset)
        recreated.previewPlan(day, 1)
        await { !recreated.state.value.busy }
        val nextQueries = secondModel.requests.map { it.subject }
        assertEquals(3, nextQueries.size)
        assertEquals(3, nextQueries.distinct().size)
        assertTrue("Recreation must not repeat the unusable candidates", firstQueries.toSet().intersect(nextQueries.toSet()).isEmpty())
        assertEquals(listOf(4, 5, 6), secondModel.offsetsAtLookup)
        assertEquals(6, rawDisk().planSearchOffset)
        assertTrue((firstModel.requests + secondModel.requests).all { it.task == "recipe" })
        assertEquals(0, firstModel.generationCalls + secondModel.generationCalls)
        assertNull(recreated.state.value.error)
        val preview = requireNotNull(recreated.state.value.planPreview)
        assertEquals(3, preview.meals.size)
        assertEquals(3, preview.meals.map { it.recipe.title }.distinct().size)
        assertEquals(3, PlanningRules.suitableRecipes(rawDisk(), rawDisk().recentRecipes).size)
        assertEquals(listOf(original), preview.expectedMeals)
        assertEquals(listOf(original), rawDisk().meals)
        assertEquals(preview, rawDisk().pendingPlan)
    }
}
