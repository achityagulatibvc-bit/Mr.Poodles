package com.mrpoodles.app

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real dispatcher, research service, request fences and local persistence together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase5WorkoutFlowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val profile = Profile(sourceLookupConsent = true, rememberChats = true,
        hostel = Environment(workoutEquipment = "wall available"))
    private val prompt = "Beginner no equipment workout"
    private val source get() = RetrievedSource("article", "https://example.org/beginner-workout",
        "Beginner quiet no-equipment calisthenics workout", "2026-10-04T10:00:00Z",
        author = "Synthetic Fixture Author", publisher = "Synthetic test publication",
        excerpt = requireNotNull(javaClass.getResource("/phase5/beginner-workout.txt")).readText(), kind = "workout")
    private val video = ResearchVideo("https://www.youtube.com/watch?v=abcdefghijk",
        "Standing knee raises technique", "Synthetic channel", "TECHNIQUE_REFERENCE", "Metadata only")

    private inner class ResearchModel(hold: Boolean = false) : CloudModel() {
        val release = CompletableDeferred<Unit>().also { if (!hold) it.complete(Unit) }
        val requests = CopyOnWriteArrayList<ResearchRequest>()
        val returned = AtomicInteger()
        var failure: IOException? = null
        override fun cancel() = Unit
        override suspend fun research(input: ResearchRequest): ResearchResponse {
            requests += input
            // Simulate a provider that delivers a result even after cancellation.
            withContext(NonCancellable) { release.await() }
            returned.incrementAndGet()
            failure?.let { throw it }
            return ResearchResponse(2, input.requestId, input.profileRevision,
                RetrievalSnapshot("fixture-${input.requestId}", input.requestId, listOf(source)), video,
                ResearchAnswer("Let's review the linked movement instructions.", emptyList(), emptyList()), "synthetic")
        }
        override suspend fun generate(instructions: String, input: String, structured: Boolean, maxTokens: Int,
            task: String, history: List<Message>, status: (String) -> Unit, stream: (String) -> Unit): String =
            error("Workout flow must use research, not legacy generation")
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for Phase 5 workout flow", condition())
    }

    private fun seed(data: AppData) {
        val gate = RequestGate()
        val request = gate.begin(data.revision)
        LocalStore(app).save(data) { move -> gate.commit(request, move) }
    }

    private fun start(model: ResearchModel, handle: SavedStateHandle = SavedStateHandle()) =
        PoodlesViewModel(app, handle, model).also { vm ->
            await { !vm.state.value.loading }
            assertFalse(vm.state.value.storageError)
        }

    private fun previous(): Workout = runBlocking {
        requireNotNull(SourcedWorkoutService(ResearchModel()).reply(RequestIdentity(profileRevision = 0),
            profile, FeatureConversation(), prompt).workout)
    }

    private fun conversation(workout: Workout) = FeatureConversation(
        subject = workout.sourced!!.subject, result = FeatureResult(workout.origin!!, workout = workout),
        messages = listOf(Message("You", prompt), Message("Poodles", "Previous sourced session")))

    private fun feature(vm: PoodlesViewModel) = vm.featureStates.value.getValue(Feature.WORKOUT)

    @Test fun sourcedResultPersistsAndRepeatedSaveKeepsOneWorkoutWithoutTouchingOtherChats() {
        val recipe = FeatureConversation(draft = "Tiramisu recipe", error = "Earlier recipe error")
        val food = FeatureConversation(draft = "Ingredients: rice, salt.")
        val companion = listOf(Message("You", "Private companion conversation"))
        seed(AppData(profile = profile, messages = companion,
            features = mapOf(Feature.RECIPE to recipe, Feature.FOOD_CHECK to food)))
        val model = ResearchModel(hold = true)
        val vm = start(model)
        vm.editFoodDraft(Feature.WORKOUT, prompt)
        vm.sendWorkout()
        try {
            await { model.requests.size == 1 }
            repeat(3) { vm.sendWorkout(); vm.sendFood(Feature.RECIPE); vm.sendFood(Feature.FOOD_CHECK) }
            assertEquals(1, model.requests.size)
            assertEquals(recipe, vm.featureStates.value.getValue(Feature.RECIPE))
            assertEquals(food, vm.featureStates.value.getValue(Feature.FOOD_CHECK))
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy }
        val workout = requireNotNull(vm.state.value.data.workout)
        val result = requireNotNull(feature(vm).result)
        assertEquals(workout, result.workout)
        assertEquals("", feature(vm).draft)
        assertEquals(listOf("You", "Poodles"), feature(vm).messages.map { it.role })
        assertEquals("workout", model.requests.single().task)
        assertEquals(model.requests.single().requestId, result.origin.requestId)
        assertEquals(vm.state.value.data.revision, result.origin.profileRevision)
        assertEquals(workout.sourced!!.origin, result.origin)
        val retainedSource = result.origin.retrieval!!.sources.first()
        assertEquals(source.copy(id = retainedSource.id), retainedSource)
        assertEquals(video.url, workout.sourced.video!!.url)
        assertTrue(WorkoutRules.validate(workout, profile))
        assertTrue(workout.exerciseIds.isEmpty())
        assertEquals(feature(vm), LocalStore(app).read().features.getValue(Feature.WORKOUT))
        val sequence = vm.state.value.successSequence
        repeat(3) { vm.saveWorkout() }
        await { vm.state.value.successSequence == sequence + 3 }
        val disk = LocalStore(app).read()
        assertEquals(listOf(workout), disk.savedWorkouts)
        assertEquals(companion, disk.messages)
        assertEquals(recipe, disk.features.getValue(Feature.RECIPE))
        assertEquals(food, disk.features.getValue(Feature.FOOD_CHECK))
        val restored = start(ResearchModel())
        assertEquals(workout, restored.state.value.data.workout)
        assertEquals(result, feature(restored).result)
        assertEquals(listOf(workout), restored.state.value.data.savedWorkouts)
    }

    @Test fun repeatedCompletionDeduplicatesTodayAcrossReloadAndRetainsOtherHistory() {
        val old = previous()
        val today = LocalDate.now().toString()
        val history = listOf(WorkoutCompletion(old.id, LocalDate.now().minusDays(1).toString()),
            WorkoutCompletion("another-workout", today))
        seed(AppData(profile = profile, workout = old, savedWorkouts = listOf(old),
            features = mapOf(Feature.WORKOUT to conversation(old)), workoutCompletions = history))
        val vm = start(ResearchModel())
        val sequence = vm.state.value.successSequence
        repeat(4) { vm.completeWorkout() }
        await { vm.state.value.successSequence == sequence + 4 }
        val disk = LocalStore(app).read()
        assertEquals(history, disk.workoutCompletions.take(history.size))
        assertEquals(history.map { it.workoutId to it.date } + (old.id to today),
            disk.workoutCompletions.map { it.workoutId to it.date })
        assertEquals(today, disk.workout!!.completedDate)
        assertEquals(old.origin, disk.workout.origin)
        val restored = start(ResearchModel())
        restored.completeWorkout()
        await { restored.state.value.successSequence == 1 }
        assertEquals(disk.workoutCompletions, LocalStore(app).read().workoutCompletions)
    }

    @Test fun stoppedNoncooperativeLookupCannotReplacePreviousResultAndRetryUsesFreshIdentity() {
        val old = previous()
        val initial = conversation(old)
        seed(AppData(profile = profile, workout = old, features = mapOf(Feature.WORKOUT to initial)))
        val model = ResearchModel(hold = true)
        val vm = start(model)
        vm.editFoodDraft(Feature.WORKOUT, prompt)
        vm.sendWorkout()
        try {
            await { model.requests.size == 1 }
            vm.cancel()
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy && model.returned.get() == 1 }
        assertEquals(old, vm.state.value.data.workout)
        assertEquals(initial.result, feature(vm).result)
        assertEquals(initial.messages, feature(vm).messages)
        assertEquals(prompt, feature(vm).draft)
        assertNull(feature(vm).activeRequest)
        assertEquals(old, LocalStore(app).read().workout)
        assertEquals(initial.result, LocalStore(app).read().features.getValue(Feature.WORKOUT).result)
        vm.sendWorkout()
        await { !vm.state.value.busy && model.requests.size == 2 }
        assertNotEquals(model.requests[0].requestId, model.requests[1].requestId)
        assertEquals(model.requests[1].requestId, feature(vm).result!!.origin.requestId)
        assertEquals(initial.messages.size + 2, feature(vm).messages.size)
    }

    @Test fun profileEditFencesDelayedResultWhilePersistingNewMovementLimits() {
        val old = previous()
        val initial = conversation(old)
        seed(AppData(profile = profile, workout = old, features = mapOf(Feature.WORKOUT to initial)))
        val model = ResearchModel(hold = true)
        val vm = start(model)
        vm.editFoodDraft(Feature.WORKOUT, prompt)
        vm.sendWorkout()
        try {
            await { model.requests.size == 1 }
            vm.saveProfile(profile.copy(injuries = "Wrist pain"))
            await { !vm.state.value.profileSaving }
            assertEquals(1, vm.state.value.data.revision)
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy && model.returned.get() == 1 }
        assertEquals(old, vm.state.value.data.workout)
        assertEquals(initial.result, feature(vm).result)
        assertEquals(initial.messages, feature(vm).messages)
        assertEquals(prompt, feature(vm).draft)
        assertNull(feature(vm).activeRequest)
        val disk = LocalStore(app).read()
        assertEquals("Wrist pain", disk.profile.injuries)
        assertEquals(1, disk.revision)
        assertEquals(old, disk.workout)
        assertEquals(initial.result, disk.features.getValue(Feature.WORKOUT).result)
        assertFalse(WorkoutRules.validate(old, disk.profile))
    }

    @Test fun editingInFlightDraftPreservesNewTextAcrossSavedStateRecreation() {
        val old = previous()
        val initial = conversation(old)
        seed(AppData(profile = profile, workout = old, features = mapOf(Feature.WORKOUT to initial)))
        val handle = SavedStateHandle()
        val model = ResearchModel(hold = true)
        val vm = start(model, handle)
        vm.editFoodDraft(Feature.WORKOUT, prompt)
        vm.sendWorkout()
        try {
            await { model.requests.size == 1 }
            vm.editFoodDraft(Feature.WORKOUT, "Only 15 minutes")
        } finally { model.release.complete(Unit) }
        await { !vm.state.value.busy && model.returned.get() == 1 }
        val edited = feature(vm)
        assertEquals("Only 15 minutes", edited.draft)
        assertEquals(initial.result, edited.result)
        assertEquals(initial.messages, edited.messages)
        assertNull(edited.activeRequest)
        assertNull(edited.retryRequest)
        assertNull(edited.error)
        assertEquals(old, LocalStore(app).read().workout)
        val restoredHandle = SavedStateHandle(mapOf("feature_WORKOUT" to handle.get<String>("feature_WORKOUT")))
        val restored = start(ResearchModel(), restoredHandle)
        assertEquals(edited, feature(restored))
        assertEquals(old, restored.state.value.data.workout)
    }

    @Test fun offlineFailureKeepsResultDraftAndHistoryThenRetryPersistsOneNewTurn() {
        val old = previous()
        val initial = conversation(old)
        seed(AppData(profile = profile, workout = old, savedWorkouts = listOf(old),
            features = mapOf(Feature.WORKOUT to initial)))
        val model = ResearchModel().also { it.failure = IOException("Synthetic offline failure") }
        val vm = start(model)
        vm.editFoodDraft(Feature.WORKOUT, prompt)
        vm.sendWorkout()
        await { !vm.state.value.busy && model.requests.size == 1 }
        val failed = feature(vm)
        assertEquals("Synthetic offline failure", failed.error)
        assertEquals(prompt, failed.draft)
        assertEquals(initial.result, failed.result)
        assertEquals(initial.messages, failed.messages)
        assertNull(failed.activeRequest)
        assertEquals(model.requests.single().requestId, failed.retryRequest!!.id)
        assertEquals(old, LocalStore(app).read().workout)
        assertEquals(failed, LocalStore(app).read().features.getValue(Feature.WORKOUT))
        val restored = start(model)
        assertEquals(failed, feature(restored))
        model.failure = null
        restored.sendWorkout()
        await { !restored.state.value.busy && model.requests.size == 2 }
        val recovered = feature(restored)
        assertNull(recovered.error)
        assertNull(recovered.retryRequest)
        assertEquals("", recovered.draft)
        assertEquals(initial.messages.size + 2, recovered.messages.size)
        assertNotEquals(model.requests[0].requestId, model.requests[1].requestId)
        assertEquals(model.requests[1].requestId, recovered.result!!.origin.requestId)
        assertEquals(recovered.result.workout, LocalStore(app).read().workout)
        assertEquals(listOf(old), LocalStore(app).read().savedWorkouts)
    }

    @Test fun shelfSelectionRetainsEvidenceAndDraftAndFollowupUsesSelectedArticle() {
        val saved = previous()
        val other = previous()
        val initial = conversation(other).copy(draft = "Only 15 minutes", error = "Earlier failure",
            selectedDate = "2026-10-05", scrollIndex = 3)
        seed(AppData(profile = profile, workout = other, savedWorkouts = listOf(saved),
            features = mapOf(Feature.WORKOUT to initial)))
        val model = ResearchModel()
        val vm = start(model)
        vm.selectWorkout(saved.id)
        await { vm.state.value.successSequence == 1 }
        val selected = feature(vm)
        assertEquals(saved, vm.state.value.data.workout)
        assertEquals(saved, selected.result!!.workout)
        assertEquals(saved.origin, selected.result.origin)
        assertEquals(saved.sourced!!.subject, selected.subject)
        assertEquals(initial.draft, selected.draft)
        assertEquals(initial.messages, selected.messages)
        assertEquals(initial.selectedDate, selected.selectedDate)
        assertEquals(initial.scrollIndex, selected.scrollIndex)
        assertNull(selected.error)
        assertEquals(selected, LocalStore(app).read().features.getValue(Feature.WORKOUT))
        assertTrue(model.requests.isEmpty())
        vm.sendWorkout()
        await { !vm.state.value.busy }
        val adjusted = requireNotNull(vm.state.value.data.workout)
        assertEquals(15, adjusted.sourced!!.durationMinutes)
        assertNotEquals(saved.id, adjusted.id)
        assertNotEquals(saved.origin!!.requestId, adjusted.origin!!.requestId)
        assertEquals(saved.origin.retrieval!!.sources.first(), adjusted.origin.retrieval!!.sources.first())
        assertEquals(saved.sourced.video!!.url, adjusted.sourced.video!!.url)
        assertTrue(model.requests.isEmpty())
        assertEquals(listOf(saved), LocalStore(app).read().savedWorkouts)
        assertEquals(adjusted, feature(vm).result!!.workout)
    }

    @Test fun staleProfileWorkoutCannotBeSavedOrCompletedEvenWhenItsArticleIsValid() {
        val old = previous()
        seed(AppData(profile = profile, workout = old, features = mapOf(Feature.WORKOUT to conversation(old))))
        val vm = start(ResearchModel())
        vm.saveProfile(profile.copy(name = "Updated name"))
        await { !vm.state.value.profileSaving }
        assertTrue(WorkoutRules.validate(old, vm.state.value.data.profile))
        assertNotEquals(old.revision, vm.state.value.data.revision)
        vm.saveWorkout()
        await { vm.state.value.error != null }
        assertTrue(vm.state.value.error!!.contains("profile changed"))
        assertTrue(LocalStore(app).read().savedWorkouts.isEmpty())
        vm.dismissError()
        vm.completeWorkout()
        await { vm.state.value.error != null }
        assertTrue(vm.state.value.error!!.contains("movement limits"))
        val disk = LocalStore(app).read()
        assertTrue(disk.workoutCompletions.isEmpty())
        assertEquals(old, disk.workout)
        assertEquals("Updated name", disk.profile.name)
    }
}
