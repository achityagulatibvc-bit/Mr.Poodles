package com.mrpoodles.app

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Replays actual extracted NHS pages through the final service and persistence, without network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveWorkoutReplayTest {
    @Test fun capturedNhsPagesProduceSavedTimedWorkoutAndDeduplicatedCompletion() {
        val directory = System.getenv("POODLES_LIVE_WORKOUT_CAPTURES")
        assumeTrue("Optional live captures absent; not a live workout acceptance pass.", !directory.isNullOrBlank())
        val responses = listOf("warmup", "strength", "cooldown").associate { name ->
            val captured = json.parseToJsonElement(File(directory, "workout-nhs-$name.json").readText()).jsonObject
            val response = json.decodeFromString<ResearchResponse>(captured.getValue("response").toString())
            response.snapshot.sources.single().url to response
        }
        val app = RuntimeEnvironment.getApplication()
        LocalStore(app).save(AppData(profile = Profile(onboarding = true, sourceLookupConsent = true,
            rememberChats = true, hostel = Environment(workoutEquipment = "wall available", workoutSpace = "Clear floor space"))))
        val calls = mutableListOf<String>()
        val model = object : CloudModel() {
            override suspend fun research(input: ResearchRequest): ResearchResponse {
                assertEquals("workout", input.task)
                val url = requireNotNull(input.url)
                calls += url
                val response = responses.getValue(url)
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
            assertTrue("Captured NHS evidence did not complete the required state transition.", condition())
        }
        try {
            await { !vm.state.value.loading }
            vm.editFoodDraft(Feature.WORKOUT, "Use a wall")
            vm.sendWorkout()
            await { !vm.state.value.busy }
            assertNull(vm.featureStates.value[Feature.WORKOUT]?.error)
            val workout = vm.state.value.data.workout
            assertNotNull("The captured NHS extraction did not produce a usable session; review parser coverage.", workout)
            val sourced = requireNotNull(workout?.sourced)
            assertEquals(NhsWorkoutArticles.urls, calls)
            assertEquals(900, requireNotNull(sourced.timePlan).totalSeconds)
            assertEquals(listOf(360, 240, 300), sourced.timePlan.stages.map { it.seconds })
            assertTrue(SourcedWorkoutRules.validate(requireNotNull(workout), vm.state.value.data.profile).isEmpty())
            (sourced.warmUp + sourced.movements + sourced.cooldown).flatMap { it.evidence }.forEach { ref ->
                assertTrue(responses.values.any { response -> response.snapshot.sources.any { it.excerpt.contains(ref.excerpt) } })
            }
            vm.saveWorkout()
            await { vm.state.value.data.savedWorkouts.size == 1 }
            vm.completeWorkout()
            await { vm.state.value.data.workoutCompletions.size == 1 }
            val sequence = vm.state.value.successSequence
            vm.completeWorkout()
            await { vm.state.value.successSequence > sequence }
            assertEquals(1, LocalStore(app).read().workoutCompletions.size)
            assertEquals(3, calls.size)
            println("Real NHS evidence replay passed: three sources, bounded 15-minute program, saved session and deduplicated completion. Android transport was substituted.")
        } finally { store.clear() }
    }
}
