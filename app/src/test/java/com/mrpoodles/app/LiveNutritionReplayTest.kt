package com.mrpoodles.app

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Opt-in replay of real public evidence; transport is substituted, not claimed as live Android HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveNutritionReplayTest {
    @Test fun capturedNutritionPassesFinalParserCommitCorrectionAndUndo() {
        val path = System.getenv("POODLES_LIVE_NUTRITION_CAPTURE")
        assumeTrue("Optional live capture is not configured; this is not a live acceptance pass.", !path.isNullOrBlank())
        val captured = json.parseToJsonElement(File(requireNotNull(path)).readText()).jsonObject
        assertEquals("nutrition", captured.getValue("case").jsonPrimitive.content)
        val response = json.decodeFromString<ResearchResponse>(captured.getValue("response").toString())
        val app = RuntimeEnvironment.getApplication()
        LocalStore(app).save(AppData(profile = Profile(onboarding = true, sourceLookupConsent = true)))
        var calls = 0
        val model = object : CloudModel() {
            override suspend fun research(input: ResearchRequest): ResearchResponse {
                calls++
                assertEquals("food_log", input.task)
                // Rebind transport request identity only. Preserve every original source, quote,
                // snapshot ID, retrieval date and model provider from the captured public response.
                return response.copy(requestId = input.requestId, profileRevision = input.profileRevision,
                    snapshot = response.snapshot.copy(requestId = input.requestId))
            }
        }
        val vm = PoodlesViewModel(app, SavedStateHandle(), model)
        val store = ViewModelStore().apply { put("replay", vm) }
        fun await(condition: () -> Boolean) {
            val until = System.currentTimeMillis() + 15000
            while (!condition() && System.currentTimeMillis() < until) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
            }
            assertTrue("Captured evidence did not complete the required state transition.", condition())
        }
        fun send(text: String) {
            vm.editFoodDraft(Feature.FOOD_LOG, text)
            vm.sendFoodLog()
            await { !vm.state.value.busy }
        }
        try {
            await { !vm.state.value.loading }
            send("I ate 100 g banana")
            assertNull(vm.featureStates.value[Feature.FOOD_LOG]?.error)
            val first = vm.state.value.data.intake.singleOrNull()
            assertNotNull("Real captured nutrition was not accepted; review source identity and serving/table coverage.", first)
            val entry = requireNotNull(first)
            assertTrue(requireNotNull(entry.kcal) > 0)
            assertEquals(100.0, requireNotNull(entry.portion).amount, 0.0)
            assertTrue(requireNotNull(entry.estimate).evidence.isNotEmpty())
            entry.estimate.evidence.forEach { ref ->
                assertTrue(response.snapshot.sources.any { it.excerpt.contains(ref.excerpt) })
            }
            assertEquals(entry, LocalStore(app).read().intake.single())
            send("Half")
            val half = vm.state.value.data.intake.single()
            assertEquals(entry.id, half.id)
            assertEquals(entry.kcal!! / 2, half.kcal!!, 0.00001)
            assertEquals(1, calls)
            vm.undoFoodLog()
            await { vm.state.value.data.intake.single().kcal == entry.kcal }
            assertEquals(entry.id, LocalStore(app).read().intake.single().id)
            println("Real evidence replay passed: ${entry.kcal} kcal per 100 g; one intake; correction and Undo persisted. Android transport was substituted.")
        } finally { store.clear() }
    }
}
