package com.mrpoodles.app

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real ViewModel, request fences and atomic snapshots; only research is synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase6FoodFlowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val profile = Profile(sourceLookupConsent = true, rememberChats = true, restrictions = emptyList())
    private val prompt = "Pyaaz kachori khayi hai"
    private val source get() = RetrievedSource("nutrition", "https://tools.myfooddata.com/fixture/kachori",
        "Pyaaz kachori nutrition", "2026-10-04T10:00:00Z",
        author = "Synthetic Fixture Author", publisher = "Synthetic Test Publisher",
        excerpt = requireNotNull(javaClass.getResource("/phase6/kachori-nutrition.txt")).readText(), kind = "nutrition")

    private inner class Model(hold: Boolean = false) : CloudModel() {
        val release = CompletableDeferred<Unit>().also { if (!hold) it.complete(Unit) }
        val requests = CopyOnWriteArrayList<ResearchRequest>()
        val returned = AtomicInteger()
        var failure: IOException? = null
        override fun cancel() = Unit
        override suspend fun research(input: ResearchRequest): ResearchResponse {
            requests += input
            // A late provider result must not bypass cancellation/profile revision fences.
            withContext(NonCancellable) { release.await() }
            returned.incrementAndGet()
            failure?.let { throw it }
            return ResearchResponse(2, input.requestId, input.profileRevision,
                RetrievalSnapshot("fixture-${input.requestId}", input.requestId, listOf(source)),
                answer = ResearchAnswer("Let's look at these sources together.", emptyList(), emptyList()), provider = "synthetic")
        }
        override suspend fun generate(instructions: String, input: String, structured: Boolean, maxTokens: Int,
            task: String, history: List<Message>, status: (String) -> Unit, stream: (String) -> Unit): String =
            error("Food logging must use research, not legacy generation")
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for Phase 6 food flow", condition())
    }

    private fun start(model: Model, data: AppData = AppData(profile = profile)): PoodlesViewModel {
        LocalStore(app).save(data)
        return restore(model)
    }
    private fun restore(model: Model) = PoodlesViewModel(app, SavedStateHandle(), model).also { vm ->
        await { !vm.state.value.loading && Feature.FOOD_LOG in vm.featureStates.value }
        assertFalse(vm.state.value.storageError)
    }
    private fun feature(vm: PoodlesViewModel) = vm.featureStates.value.getValue(Feature.FOOD_LOG)
    private fun send(vm: PoodlesViewModel, text: String = prompt) {
        vm.editFoodDraft(Feature.FOOD_LOG, text)
        vm.sendFoodLog()
    }
    private fun disk() = LocalStore(app).read()
    private fun saved(vm: PoodlesViewModel) = feature(vm).messages.any { it.text.contains("Saved in your diary.") }

    @Test fun sourcedEstimateAutoLogsAndConfirmsOnlyAfterDurableSave() {
        val other = FeatureConversation(draft = "Keep my recipe draft")
        val model = Model(hold = true)
        val vm = start(model, AppData(profile = profile, features = mapOf(Feature.RECIPE to other)))
        val today = LocalDate.now().toString()
        send(vm)
        try {
            await { model.requests.size == 1 }
            assertTrue(vm.state.value.data.intake.isEmpty())
            assertTrue(disk().intake.isEmpty())
            assertFalse(saved(vm))
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy }
        val entry = vm.state.value.data.intake.single()
        assertEquals(today, entry.date)
        assertEquals(200.0, entry.kcal!!, 0.001)
        assertEquals(4.0, entry.protein!!, 0.001)
        assertTrue(entry.portion!!.assumed)
        assertNull(entry.portion.grams)
        assertEquals("food_log", model.requests.single().task)
        assertEquals("pyaaz kachori nutrition per piece", model.requests.single().subject)
        assertEquals(source.excerpt, entry.origin!!.retrieval!!.sources.single().excerpt)
        assertEquals(model.requests.single().requestId, entry.origin.requestId)
        assertEquals(listOf(entry.id), feature(vm).result!!.intakeIds)
        assertEquals("", feature(vm).draft)
        assertNull(feature(vm).preparedLog)
        assertTrue(saved(vm))
        assertEquals(listOf(entry), disk().intake)
        assertEquals(feature(vm), disk().features.getValue(Feature.FOOD_LOG))
        assertEquals(other, disk().features.getValue(Feature.RECIPE))
        val restored = restore(Model())
        assertEquals(listOf(entry), restored.state.value.data.intake)
        assertEquals(feature(vm), feature(restored))
    }

    @Test fun unknownPreparationSurvivesReloadAndRequiresExplicitSave() {
        val model = Model().also { it.failure = IOException("Synthetic offline lookup") }
        val vm = start(model)
        send(vm)
        await { !vm.state.value.busy }
        val prepared = requireNotNull(feature(vm).preparedLog)
        assertNull(prepared.entries.single().kcal)
        assertNull(prepared.entries.single().origin!!.retrieval)
        assertEquals(prompt, feature(vm).draft)
        assertFalse(saved(vm))
        assertTrue(disk().intake.isEmpty())
        assertTrue(disk().intakeOperations.isEmpty())
        assertEquals(prepared, disk().features.getValue(Feature.FOOD_LOG).preparedLog)
        val restored = restore(model)
        restored.saveUnknownFoodLog()
        await { !restored.state.value.busy }
        assertEquals(1, model.requests.size)
        assertEquals(prepared.entries, disk().intake)
        assertNull(disk().intake.single().kcal)
        assertEquals(prepared.operations.map { it.id }, disk().intakeOperations.map { it.operationId })
        assertTrue(saved(restored))
        assertNull(feature(restored).preparedLog)
        restored.saveUnknownFoodLog()
        assertEquals(1, disk().intake.size)
        assertEquals(1, disk().intakeOperations.size)
    }

    @Test fun failedEffectSaveRetainsOriginalOperationsDateAndSourcesAcrossRestartRetry() {
        val model = Model()
        val vm = start(model)
        val day = LocalDate.now().minusDays(2).toString()
        val obstruction = File(app.filesDir, "poodles-v1.json.pending")
        var captured: PreparedFoodLog? = null
        // onCommitted publishes preparation after the atomic move. Block the NEXT write,
        // which applies its effects, without modifying the production persistence seam.
        val observer = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.featureStates.collect { states ->
                val prepared = states[Feature.FOOD_LOG]?.preparedLog
                if (prepared != null && captured == null) {
                    captured = prepared
                    check(obstruction.mkdir())
                    obstruction.resolve("synthetic-obstruction").writeText("Prevent the effect snapshot write")
                }
            }
        }
        try {
            send(vm, "$prompt on $day")
            await { !vm.state.value.busy }
            val prepared = requireNotNull(captured)
            assertNotNull(feature(vm).error)
            assertEquals(prepared, feature(vm).preparedLog)
            assertFalse(saved(vm))
            assertTrue(vm.state.value.data.intake.isEmpty())
            assertTrue(disk().intake.isEmpty())
            assertTrue(disk().intakeOperations.isEmpty())
            assertEquals(prepared, disk().features.getValue(Feature.FOOD_LOG).preparedLog)
        } finally {
            observer.cancel()
            obstruction.resolve("synthetic-obstruction").delete()
            obstruction.delete()
        }
        val original = requireNotNull(captured)
        val restored = restore(model)
        assertNotNull(feature(restored).error)
        restored.sendFoodLog()
        await { !restored.state.value.busy }
        assertEquals("A prepared retry must not repeat research", 1, model.requests.size)
        assertEquals(original.entries, disk().intake)
        assertEquals(day, disk().intake.single().date)
        assertEquals(original, feature(restored).lastFoodLog)
        assertEquals(original.operations.map { it.id }, disk().intakeOperations.map { it.operationId })
        assertEquals(original.entries.single().origin, disk().intake.single().origin)
        assertNull(feature(restored).error)
        assertTrue(saved(restored))
        restored.sendFoodLog()
        assertEquals(1, disk().intake.size)
    }

    @Test fun quantityCorrectionKeepsTargetAndUndoRestoresOriginalNutritionDateAndEvidence() {
        val model = Model()
        val vm = start(model)
        val day = LocalDate.now().minusDays(1).toString()
        send(vm, "$prompt on $day")
        await { !vm.state.value.busy }
        val original = disk().intake.single()
        send(vm, "I ate two")
        await { !vm.state.value.busy }
        val corrected = disk().intake.single()
        assertEquals(original.id, corrected.id)
        assertEquals(day, corrected.date)
        assertEquals(400.0, corrected.kcal!!, 0.001)
        assertEquals(2.0, corrected.portion!!.amount, 0.001)
        assertEquals(1, corrected.entryRevision)
        assertEquals(listOf(original), feature(vm).lastFoodLog!!.previousEntries)
        assertEquals(1, model.requests.size)
        assertEquals(2, disk().intakeOperations.size)
        vm.undoFoodLog()
        await { feature(vm).lastFoodLog == null }
        assertEquals(listOf(original.copy(entryRevision = 2)), disk().intake)
        assertEquals(3, disk().intakeOperations.size)
        assertNull(feature(vm).result)
        vm.undoFoodLog()
        assertEquals(3, disk().intakeOperations.size)
    }

    @Test fun nutritionQuestionPersistsAnswerWithoutAnyDiaryOperation() {
        val existing = Intake(id = "earlier", date = LocalDate.now().toString(), name = "Earlier meal", kcal = 150.0)
        val model = Model()
        val vm = start(model, AppData(profile = profile, intake = listOf(existing)))
        send(vm, "How many calories in pyaaz kachori?")
        await { !vm.state.value.busy }
        assertEquals("food_log", model.requests.single().task)
        assertEquals(listOf(existing), disk().intake)
        assertTrue(disk().intakeOperations.isEmpty())
        assertTrue(feature(vm).result!!.intakeIds.isEmpty())
        assertNull(feature(vm).lastFoodLog)
        assertNull(feature(vm).preparedLog)
        assertFalse(saved(vm))
        assertTrue(feature(vm).messages.last().text.contains("about 200 kcal"))
        assertTrue(feature(vm).messages.last().text.contains("Nothing was added"))
        assertNotNull(feature(vm).result!!.origin.retrieval)
        assertEquals(feature(vm), disk().features.getValue(Feature.FOOD_LOG))
    }

    @Test fun cancellationFencesNoncooperativeResearchAndKeepsDraftWithoutDiaryWrites() {
        val model = Model(hold = true)
        val vm = start(model)
        send(vm)
        try {
            await { model.requests.size == 1 }
            vm.cancel()
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy && model.returned.get() == 1 }
        assertEquals(prompt, feature(vm).draft)
        assertNull(feature(vm).activeRequest)
        assertNull(feature(vm).result)
        assertFalse(saved(vm))
        assertTrue(disk().intake.isEmpty())
        assertTrue(disk().intakeOperations.isEmpty())
        vm.sendFoodLog()
        await { !vm.state.value.busy && model.requests.size == 2 }
        assertNotEquals(model.requests[0].requestId, model.requests[1].requestId)
        assertEquals(1, disk().intake.size)
        assertEquals(1, disk().intakeOperations.size)
    }

    @Test fun profileEditFencesDelayedEstimateAndPersistsNewProfileWithoutDiaryWrites() {
        val model = Model(hold = true)
        val vm = start(model)
        send(vm)
        try {
            await { model.requests.size == 1 }
            vm.saveProfile(profile.copy(name = "Changed profile"))
            await { !vm.state.value.profileSaving }
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy && model.returned.get() == 1 }
        assertEquals(1, disk().revision)
        assertEquals("Changed profile", disk().profile.name)
        assertEquals(prompt, feature(vm).draft)
        assertNull(feature(vm).activeRequest)
        assertNull(feature(vm).result)
        assertFalse(saved(vm))
        assertTrue(vm.state.value.data.intake.isEmpty())
        assertTrue(disk().intake.isEmpty())
        assertTrue(disk().intakeOperations.isEmpty())
    }

    @Test fun repeatedBusySendCreatesOneResearchCallOneEntryAndOneReceipt() {
        val model = Model(hold = true)
        val vm = start(model)
        send(vm)
        try {
            await { model.requests.size == 1 }
            repeat(5) { vm.sendFoodLog(); vm.saveUnknownFoodLog() }
            assertEquals(1, model.requests.size)
            assertTrue(disk().intake.isEmpty())
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy }
        assertEquals(1, model.requests.size)
        assertEquals(1, disk().intake.size)
        assertEquals(1, disk().intakeOperations.size)
        assertEquals(listOf("You", "Poodles"), feature(vm).messages.map { it.role })
        repeat(5) { vm.sendFoodLog() }
        assertEquals(1, model.requests.size)
        assertEquals(1, disk().intakeOperations.size)
    }
}
