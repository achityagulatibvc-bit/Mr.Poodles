package com.mrpoodles.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Synthetic article/metadata fixtures exercise attribution, not a live-provider acceptance claim. */
class Phase5WorkoutTest {
    private val profile = Profile(sourceLookupConsent = true, hostel = Environment(workoutSpace = "Small room; quiet exercises; wall available"))
    private val fixture get() = requireNotNull(javaClass.getResource("/phase5/beginner-workout.txt")).readText()
    private fun source(text: String = fixture) = RetrievedSource("article", "https://www.nhs.uk/live-well/exercise/fixture-workout/",
        "Beginner quiet no-equipment calisthenics workout", "2026-10-04T10:00:00Z", author = "Synthetic Fixture Author",
        publisher = "Synthetic test publication", excerpt = text, kind = "workout")
    private val video = ResearchVideo("https://www.youtube.com/watch?v=abcdefghijk", "Standing knee raises technique", "Synthetic channel", "TECHNIQUE_REFERENCE", "Metadata only")
    private inner class Model : CloudModel() {
        val requests = mutableListOf<ResearchRequest>()
        var sources = listOf(source())
        var metadata: ResearchVideo? = video
        var failure: Exception? = null
        var mutate: (ResearchResponse) -> ResearchResponse = { it }
        override suspend fun research(input: ResearchRequest): ResearchResponse {
            requests += input
            failure?.let { throw it }
            return mutate(ResearchResponse(2, input.requestId, input.profileRevision,
                RetrievalSnapshot("request-snapshot", input.requestId, sources), metadata,
                ResearchAnswer("Let's look at these sources together.", emptyList(), emptyList()), "synthetic"))
        }
        override suspend fun generate(instructions: String, input: String, structured: Boolean, maxTokens: Int,
            task: String, history: List<Message>, status: (String) -> Unit, stream: (String) -> Unit): String = error("Legacy fixed-library generation must not be called")
    }
    private fun request() = RequestIdentity(profileRevision = 7)
    private fun initial(model: Model = Model(), text: String = "Beginner no equipment workout", p: Profile = profile): Workout = runBlocking {
        requireNotNull(SourcedWorkoutService(model).reply(request(), p, FeatureConversation(), text).workout)
    }
    private fun conversation(workout: Workout) = FeatureConversation(subject = workout.sourced!!.subject,
        result = FeatureResult(workout.origin!!, workout = workout))
    private fun changed(workout: Workout, value: SourcedWorkout) = workout.copy(sourced = value)
    private fun nhsSource(url: String): RetrievedSource {
        val file = when (url) {
            NhsWorkoutArticles.WARMUP -> "nhs-warmup-layout.txt"
            NhsWorkoutArticles.STRENGTH -> "nhs-strength-layout.txt"
            else -> "nhs-cooldown-layout.txt"
        }
        val title = when (url) {
            NhsWorkoutArticles.WARMUP -> "How to warm up before exercising - NHS"
            NhsWorkoutArticles.STRENGTH -> "Strength exercises - NHS"
            else -> "How to stretch after exercising - NHS"
        }
        return RetrievedSource("reused-provider-id", url, title, "2026-10-04T12:00:00Z", publisher = "NHS",
            excerpt = requireNotNull(javaClass.getResource("/phase5/$file")).readText(), kind = "workout")
    }
    private inner class NhsModel : CloudModel() {
        val requests = mutableListOf<ResearchRequest>()
        var initialSources = listOf(RetrievedSource("ace-index", "https://www.acefitness.org/resources/everyone/exercise-library/",
            "ACE exercise library", "2026-10-04T12:00:00Z", kind = "workout",
            excerpt = "Synthetic incomplete ACE index fixture. Search exercises by body part and equipment. An exercise name and dose are not complete technique instructions. ".repeat(2)))
        var modify: (RetrievedSource) -> RetrievedSource = { it }
        var failUrl: String? = null
        var metadata: ResearchVideo? = null
        override suspend fun research(input: ResearchRequest): ResearchResponse {
            requests += input
            if (input.url == failUrl && failUrl != null) throw IOException("Synthetic supplementary timeout")
            val sources = input.url?.let { listOf(modify(nhsSource(it))) } ?: initialSources
            return ResearchResponse(2, input.requestId, input.profileRevision, RetrievalSnapshot("lookup-${requests.size}", input.requestId, sources),
                video = metadata, answer = ResearchAnswer("Let's review these movement instructions together.", emptyList(), emptyList()), provider = "synthetic")
        }
    }

    @Test fun completeArticleProducesNamedMovementsWithRealQuotesAndConstraints() {
        val model = Model()
        val workout = initial(model, "Beginner no equipment workout 15min")
        assertTrue(workout.exerciseIds.isEmpty())
        val value = workout.sourced!!
        assertEquals(listOf("Wall push-ups", "Standing knee raises", "Heel raises"), value.movements.map { it.name })
        assertEquals(15, value.durationMinutes)
        assertEquals(1, value.warmUp.size)
        assertEquals(1, value.cooldown.size)
        assertEquals(30, value.movements[1].restSeconds)
        assertEquals(listOf("Keep your torso upright and move gently."), value.movements[1].formCues)
        assertEquals("Lift the knee a smaller distance.", value.movements[1].easierAlternative)
        assertTrue(SourcedWorkoutRules.validate(workout, profile).isEmpty())
        assertEquals("workout", model.requests.single().task)
        assertEquals(listOf("None"), model.requests.single().constraints.equipment)
        assertEquals("Beginner", model.requests.single().constraints.experience)
        assertEquals(15, model.requests.single().constraints.durationMinutes)
        assertFalse(model.requests.single().allowExternalModel)
        assertEquals(workout.origin, value.origin)
        assertTrue(value.programmingNotes.any { it.contains("not an article-verified session length") })
    }

    @Test fun partialArticleAndNoSourcesAskQuestionWithoutReplacingPrevious() = runBlocking {
        val old = initial()
        val state = conversation(old)
        for (sources in listOf(emptyList(), listOf(source(fixture.substringBefore("## Cooldown"))),
            listOf(source("A beginner workout with no equipment. ".repeat(5))))) {
            val model = Model().also { it.sources = sources }
            val reply = SourcedWorkoutService(model).reply(request(), profile, state, "Find a new beginner workout")
            assertNull(reply.workout)
            assertTrue(reply.message.contains("?"))
            assertEquals(old, state.result!!.workout)
        }
    }

    @Test fun snippetOrTruncatedInstructionCannotBecomeMovement() = runBlocking {
        for (text in listOf(fixture.substringBefore("## Cooldown") + "## Cooldown\n### Shoulder rolls\nRoll your shoulders for 30 seconds.",
            fixture.replace("Lower your shoulders and breathe normally.", "Lower your shoulders and breathe normally..."))) {
            val reply = SourcedWorkoutService(Model().also { it.sources = listOf(source(text)) }).reply(request(), profile, FeatureConversation(), "Beginner workout")
            assertNull(reply.workout)
        }
    }

    @Test fun injuriesAndRehabilitationRequestsAskBeforeLookup() = runBlocking {
        val model = Model()
        listOf(profile.copy(injuries = "knee injury") to "Beginner workout", profile to "My wrist hurts; make it easier",
            profile to "rehabilitation after surgery").forEach { (p, text) ->
            val reply = SourcedWorkoutService(model).reply(request(), p, FeatureConversation(), text)
            assertNull(reply.workout)
            assertTrue(reply.message.contains("clinician"))
        }
        assertTrue(model.requests.isEmpty())
    }

    @Test fun sourceConsentRequiredAndFallbackIsExplicit() = runBlocking {
        val model = Model()
        try {
            SourcedWorkoutService(model).reply(request(), profile.copy(sourceLookupConsent = false), FeatureConversation(), "Beginner workout")
            fail("Consent must be checked")
        } catch (_: IllegalStateException) { }
        assertTrue(model.requests.isEmpty())
        initial(model, p = profile.copy(externalModelConsent = true))
        assertTrue(model.requests.single().allowExternalModel)
        assertTrue(model.requests.single().constraints.restrictions.isEmpty())
    }

    @Test fun videoMetadataIsOnlyATechniqueReferenceAndNeverWatched() {
        val value = initial().sourced!!
        assertEquals(VideoMatch.TECHNIQUE_REFERENCE, value.video!!.match)
        assertEquals(video.url, value.video.url)
        assertTrue(value.video.verificationNote.contains("not watched"))
        assertEquals("youtube_metadata", value.origin.retrieval!!.sources.last().kind)
    }

    @Test fun invalidDeletedUnrelatedMissingAndFollowAlongVideosAreOmitted() {
        listOf(null, video.copy(url = "https://evil.example/watch?v=abcdefghijk"), video.copy(title = "Deleted video"),
            video.copy(title = "Private video"), video.copy(title = "A cooking lesson"), video.copy(channel = ""),
            video.copy(match = "FOLLOW_ALONG"), video.copy(url = "https://www.youtube.com/watch?v=short")).forEach { metadata ->
            val result = initial(Model().also { it.metadata = metadata })
            assertNull(result.sourced!!.video)
            assertTrue(SourcedWorkoutRules.validate(result, profile).isEmpty())
        }
    }

    @Test fun inventedResearchClaimCannotPassEvenIfArticleLooksValid() = runBlocking {
        val model = Model().also { m -> m.mutate = { response -> response.copy(answer = response.answer.copy(claims = listOf(
            ResearchClaim("Invented 100 reps", "source", listOf(EvidenceReference(response.snapshot.id, "article", "Invented 100 reps")))))) } }
        try { SourcedWorkoutService(model).reply(request(), profile, FeatureConversation(), "Beginner workout"); fail("Forged claim accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun inventedInstructionDoseFormAlternativeAndReferenceAreRejected() {
        val workout = initial()
        val sourced = workout.sourced!!
        val movement = sourced.movements.first()
        listOf(movement.copy(instructions = "Invented complete instructions."), movement.copy(sets = 4), movement.copy(reps = "999999999999999999"),
            movement.copy(formCues = listOf("A claim absent from the article")), movement.copy(easierAlternative = "Invented exercise"),
            movement.copy(evidence = listOf(movement.evidence.single().copy(sourceId = "forged"))),
            movement.copy(evidence = listOf(movement.evidence.single().copy(snapshotId = "old-request")))).forEach { forged ->
            assertTrue(SourcedWorkoutRules.validate(changed(workout, sourced.copy(movements = listOf(forged) + sourced.movements.drop(1))), profile).isNotEmpty())
        }
    }

    @Test fun modelAdjustmentIsNotPublishedExerciseEvidence() = runBlocking {
        val model = Model().also { m ->
            m.sources = listOf(source("Beginner quiet no equipment workout. ".repeat(5)))
            m.mutate = { it.copy(answer = it.answer.copy(claims = listOf(ResearchClaim(fixture.take(590), "ai_adjustment", emptyList())))) }
        }
        assertNull(SourcedWorkoutService(model).reply(request(), profile, FeatureConversation(), "Beginner workout").workout)
    }

    @Test fun quietNoJumpingAndEquipmentConflictsFailClosed() = runBlocking {
        for (altered in listOf(fixture.replace("Lift one knee slowly", "Jump up and lift one knee slowly"),
            fixture.replace("place your fingertips on a wall", "place your fingertips on a chair"),
            fixture.replace("Lift one knee slowly", "Lift one knee while holding dumbbells"))) {
            val reply = SourcedWorkoutService(Model().also { it.sources = listOf(source(altered)) }).reply(request(), profile, FeatureConversation(), "Beginner no equipment workout")
            assertNull(reply.workout)
        }
    }

    @Test fun profileChangeRevalidatesRoomEquipmentAndInjuries() {
        val workout = initial()
        assertTrue(SourcedWorkoutRules.validate(workout, profile.copy(injuries = "Wrist pain")).isNotEmpty())
        val wrongOrigin = workout.copy(origin = workout.origin!!.copy(profileRevision = 99))
        assertTrue(SourcedWorkoutRules.validate(wrongOrigin, profile).isNotEmpty())
    }

    @Test fun shortDurationAndEasierEditsAreLocalReboundAndLabeled() = runBlocking {
        val model = Model()
        val original = initial(model)
        val service = SourcedWorkoutService(model)
        val id = request()
        val short = requireNotNull(service.reply(id, profile, conversation(original), "Only 15 minutes").workout)
        val easier = requireNotNull(service.reply(request(), profile, conversation(short), "Make it easier").workout)
        assertEquals(1, model.requests.size)
        assertEquals(15, short.sourced!!.durationMinutes)
        assertEquals(id.id, short.origin!!.requestId)
        assertNotEquals(original.id, short.id)
        assertNotEquals(original.origin!!.retrieval!!.id, short.origin.retrieval!!.id)
        assertEquals(original.origin.retrieval!!.sources.first().retrievedAt, short.origin.retrieval!!.sources.first().retrievedAt)
        assertTrue(easier.sourced!!.reducedDose)
        assertEquals("5", easier.sourced.movements.first().reps)
        assertEquals(1, easier.sourced.movements.first().sets)
        assertEquals(20, easier.sourced.movements[1].seconds)
        assertEquals(30, easier.sourced.movements[1].restSeconds)
        assertTrue(SourcedWorkoutRules.validate(easier, profile).isEmpty())
        assertEquals(original.sourced!!.movements.first().instructions, easier.sourced.movements.first().instructions)
    }

    @Test fun difficultPushupsAreOmittedWithoutInventingSubstitutes() = runBlocking {
        val model = Model()
        val old = initial(model)
        val result = requireNotNull(SourcedWorkoutService(model).reply(request(), profile, conversation(old), "Push-ups are difficult").workout)
        assertEquals(1, model.requests.size)
        assertEquals(listOf("Standing knee raises", "Heel raises"), result.sourced!!.movements.map { it.name })
        assertTrue(result.sourced.avoidPushUps)
        assertTrue(result.sourced.programmingNotes.any { it.contains("omitted") })
        assertTrue(SourcedWorkoutRules.validate(result, profile).isEmpty())
    }

    @Test fun quietFollowupStaysLocalAndMissingRestStaysUnknown() = runBlocking {
        val model = Model().also { it.sources = listOf(source(fixture.replace(" Rest 30 seconds.", ""))) }
        val old = initial(model)
        val result = requireNotNull(SourcedWorkoutService(model).reply(request(), profile, conversation(old), "No jumping, quiet hostel").workout)
        assertTrue(result.sourced!!.quiet)
        assertTrue(result.sourced.noJumping)
        assertNull(result.sourced.movements.first().restSeconds)
        assertEquals(1, model.requests.size)
    }

    @Test fun unsupportedCompoundEditAsksRatherThanSilentlyIgnoringIt() = runBlocking {
        val model = Model()
        val old = initial(model)
        val reply = SourcedWorkoutService(model).reply(request(), profile, conversation(old), "Make it easier and add handstands")
        assertNull(reply.workout)
        assertEquals(1, model.requests.size)
        assertTrue(reply.message.contains("?"))
    }

    @Test fun wrongTopicAndStaleSubjectCannotReuseOldWorkout() = runBlocking {
        val model = Model()
        val old = initial(model)
        val state = conversation(old).copy(subject = "Swimming workout")
        val reply = SourcedWorkoutService(model).reply(request(), profile, state, "Only 15 minutes")
        assertNull(reply.workout)
        assertEquals(2, model.requests.size)
    }

    @Test fun timeoutAndCancellationPropagateWithoutChangingPreviousResult() = runBlocking {
        val old = initial()
        val state = conversation(old)
        for (error in listOf(IOException("offline"), CancellationException("stop"))) {
            val model = Model().also { it.failure = error }
            try { SourcedWorkoutService(model).reply(request(), profile, state, "Find a new workout"); fail("Failure was hidden") }
            catch (caught: Exception) { assertSame(error, caught) }
            assertSame(old, state.result!!.workout)
        }
    }

    @Test fun supersededResultRejectedBySharedRequestFence() {
        val old = initial()
        val id = request()
        val started = conversation(old).begin(id)
        val result = FeatureResult(old.origin!!, workout = old)
        assertEquals(started, started.complete(id, id.profileRevision + 1, result))
        val edited = started.edit("Make it easier")
        assertEquals(edited, edited.complete(id, id.profileRevision, result))
        assertSame(old, edited.result!!.workout)
    }

    @Test fun serializedAdditiveFieldsAndOriginalReferencesRoundTrip() {
        val old = initial()
        val decoded = json.decodeFromString<Workout>(json.encodeToString(Workout.serializer(), old))
        assertEquals(old, decoded)
        assertTrue(SourcedWorkoutRules.validate(decoded, profile).isEmpty())
        assertNull(json.decodeFromString<Workout>("""{"title":"Legacy","exerciseIds":["march"]}""").sourced)
    }

    @Test fun restAndEasierOptionDoseCannotMasqueradeAsMainExerciseDose() = runBlocking {
        val bad = fixture.replace("Continue for 30 seconds for 2 sets.", "Continue slowly and breathe normally.")
            .replace("Rest 30 seconds.", "Rest for 30 seconds.")
            .replace("Easier: Lift the knee a smaller distance.", "Easier: Lift the knee a smaller distance for 20 seconds.")
        val model = Model().also { it.sources = listOf(source(bad)) }
        val result = SourcedWorkoutService(model).reply(request(), profile, FeatureConversation(), "Beginner workout").workout
        // A missing block is not synthesized from rest time or the easier-option paragraph.
        assertFalse(result?.sourced?.movements.orEmpty().any { it.name == "Standing knee raises" })
    }

    @Test fun availableEquipmentDoesNotAuthorizeDifferentEquipment() = runBlocking {
        val altered = fixture.replace("Lift one knee slowly", "Lift one knee while holding dumbbells")
        val p = profile.copy(hostel = profile.hostel.copy(workoutEquipment = "Resistance bands"))
        val model = Model().also { it.sources = listOf(source(altered)) }
        assertNull(SourcedWorkoutService(model).reply(request(), p, FeatureConversation(), "Beginner workout").workout)
    }

    @Test fun profileNoJumpingIsEnforcedEvenWhenQuietIsNotWritten() = runBlocking {
        val altered = fixture.replace("Lift one knee slowly", "Jump up and lift one knee slowly")
        val p = profile.copy(mode = "Home", home = profile.home.copy(workoutSpace = "No jumping"))
        val model = Model().also { it.sources = listOf(source(altered)) }
        assertNull(SourcedWorkoutService(model).reply(request(), p, FeatureConversation(), "Beginner workout").workout)
    }

    @Test fun wrongRequestIdentityAndForgedVideoEvidenceAreRejected() = runBlocking {
        val model = Model().also { m -> m.mutate = { it.copy(requestId = "another-request") } }
        try { SourcedWorkoutService(model).reply(request(), profile, FeatureConversation(), "Beginner workout"); fail("Stale response accepted") }
        catch (_: IllegalArgumentException) { }
        val old = initial()
        val value = old.sourced!!
        val altered = value.copy(video = value.video!!.copy(channel = "Invented channel"))
        assertTrue(SourcedWorkoutRules.validate(changed(old, altered), profile).isNotEmpty())
    }

    @Test fun fifteenMinuteEditChangesWorkloadInsteadOfOnlyTheLabel() = runBlocking {
        val model = Model()
        val long = initial(model, "Beginner no equipment workout 30 minutes")
        val short = requireNotNull(SourcedWorkoutService(model).reply(request(), profile, conversation(long), "Only15minutes").workout)
        assertEquals(1, model.requests.size)
        val before = requireNotNull(long.sourced!!.timePlan)
        val after = requireNotNull(short.sourced!!.timePlan)
        assertEquals(900, after.totalSeconds)
        assertEquals(listOf("warmup", "main", "cooldown"), after.stages.map { it.phase })
        assertEquals(listOf(180, 540, 180), after.stages.map { it.seconds })
        assertTrue(after.stages.sumOf { stage -> stage.blocks.sumOf { it.workSeconds } } < before.stages.sumOf { stage -> stage.blocks.sumOf { it.workSeconds } })
        assertEquals(5, after.stages[1].blocks[0].repCap)
        assertEquals(8, before.stages[1].blocks[0].repCap)
        assertEquals(1, after.stages[1].blocks[0].setCap)
        assertEquals(2, short.sourced.movements[0].sets) // Unchanged article dose, explicitly overridden in the program.
        assertEquals(long.sourced.movements.map { it.instructions }, short.sourced.movements.map { it.instructions })
        assertEquals(long.sourced.cooldown.map { it.instructions }, short.sourced.cooldown.map { it.instructions })
        assertTrue(SourcedWorkoutRules.validate(short, profile).isEmpty())
    }

    @Test fun everyScheduleAccountsForWorkRestTransitionRecoveryAndCooldown() {
        for (minutes in listOf(5, 10, 15, 20, 30, 60)) {
            val workout = initial(text = "Beginner no equipment workout $minutes minutes")
            val value = workout.sourced!!
            val plan = requireNotNull(value.timePlan)
            assertEquals(minutes * 60, plan.totalSeconds)
            assertEquals(plan.totalSeconds, plan.stages.sumOf { it.seconds })
            plan.stages.zip(listOf(value.warmUp, value.movements, value.cooldown)).forEach { (stage, movements) ->
                assertTrue(stage.blocks.isNotEmpty())
                assertTrue(stage.recoverySeconds >= 0)
                assertEquals(stage.seconds, stage.recoverySeconds + stage.blocks.sumOf { it.workSeconds + it.restSeconds + it.transitionSeconds })
                stage.blocks.forEach { block ->
                    val original = movements[block.movementIndex]
                    assertEquals(1, block.setCap)
                    assertTrue(block.workSeconds in 1..60)
                    assertEquals(original.restSeconds ?: 30, block.restSeconds)
                    assertEquals(10, block.transitionSeconds)
                    assertTrue(original.seconds == null || block.doseSecondsCap!! <= original.seconds)
                }
            }
            assertTrue(SourcedWorkoutRules.validate(workout, profile).isEmpty())
        }
    }

    @Test fun fiveMinutesOmitsMainExercisesBeforeStealingCooldownTime() = runBlocking {
        val old = initial()
        val short = requireNotNull(SourcedWorkoutService(Model()).reply(request(), profile, conversation(old), "Only 5 minutes").workout)
        val value = short.sourced!!
        val plan = requireNotNull(value.timePlan)
        assertEquals(300, plan.totalSeconds)
        assertEquals(60, plan.stages.last().seconds)
        assertEquals(listOf(0, 1), plan.stages[1].blocks.map { it.movementIndex })
        assertEquals(3, value.movements.size) // Full article references survive; only two movements are scheduled.
        assertEquals(old.sourced!!.cooldown.map { it.instructions }, value.cooldown.map { it.instructions })
        assertTrue(WorkoutProgramming.checked(value) != null)
    }

    @Test fun forgedCapsOrDoseCannotPassIndependentScheduleValidation() {
        val workout = initial(text = "Beginner no equipment workout 15 minutes")
        val value = workout.sourced!!
        val plan = value.timePlan!!
        val stage = plan.stages[1]
        val block = stage.blocks[0]
        val forgedBlocks = listOf(block.copy(workSeconds = 999), block.copy(setCap = 2), block.copy(repCap = 50),
            block.copy(doseSecondsCap = 45), block.copy(restSeconds = 0), block.copy(transitionSeconds = 0), block.copy(movementIndex = 99))
        val forgedPlans = forgedBlocks.map { forged ->
            plan.copy(stages = plan.stages.toMutableList().also { it[1] = stage.copy(blocks = listOf(forged) + stage.blocks.drop(1)) })
        } + listOf(plan.copy(totalSeconds = 901), plan.copy(version = 2), plan.copy(stages = plan.stages.dropLast(1)),
            plan.copy(stages = plan.stages.toMutableList().also { it[1] = stage.copy(recoverySeconds = stage.recoverySeconds + 1) }))
        forgedPlans.forEach { forged ->
            val broken = value.copy(timePlan = forged)
            assertTrue(SourcedWorkoutRules.validate(changed(workout, broken), profile).isNotEmpty())
            assertNull(WorkoutProgramming.checked(broken))
        }
        assertTrue(SourcedWorkoutRules.validate(changed(workout, value.copy(durationMinutes = 5)), profile).isNotEmpty())
        assertTrue(SourcedWorkoutRules.validate(changed(workout, value.copy(timePlan = null)), profile).isNotEmpty())
    }

    @Test fun matchingTotalDoesNotExcuseTakingTimeFromCooldown() {
        val workout = initial(text = "Beginner no equipment workout 15 minutes")
        val value = workout.sourced!!
        val plan = value.timePlan!!
        val swapped = plan.copy(stages = plan.stages.map { stage -> when (stage.phase) {
            "main" -> stage.copy(seconds = stage.seconds + 60, recoverySeconds = stage.recoverySeconds + 60)
            "cooldown" -> stage.copy(seconds = stage.seconds - 60, recoverySeconds = stage.recoverySeconds - 60)
            else -> stage
        } })
        assertEquals(plan.totalSeconds, swapped.stages.sumOf { it.seconds })
        assertTrue(SourcedWorkoutRules.validate(changed(workout, value.copy(timePlan = swapped)), profile).isNotEmpty())
    }

    @Test fun unknownCadenceAndRestStayUnknownDespiteAiTimeLimits() {
        val model = Model().also { it.sources = listOf(source(fixture.replace(" Rest 30 seconds.", ""))) }
        val value = initial(model, "Beginner no equipment workout 15 minutes").sourced!!
        val movement = value.movements[0]
        val cap = value.timePlan!!.stages[1].blocks[0]
        assertNull(movement.seconds)
        assertNull(movement.restSeconds)
        assertNull(cap.doseSecondsCap)
        assertEquals(45, cap.workSeconds) // A maximum work window, not an inferred time for five reps.
        assertEquals(5, cap.repCap)
        assertEquals(30, cap.restSeconds) // Independently labeled AI allowance, never added to source facts.
    }

    @Test fun unsupportedPublishedDoseCannotBeLaunderedThroughReducedCaps() = runBlocking {
        val pending = FeatureConversation(subject = "Beginner workout")
        val instruction = "Only 15 minutes and make it easier"
        assertNotNull(SourcedWorkoutService(Model()).reply(request(), profile, pending, instruction).workout)
        for (bad in listOf(fixture.replace("8 reps", "99 reps"), fixture.replace("2 sets", "7 sets"),
            fixture.replace("for 30 seconds", "for 999 seconds"), fixture.replace("8 reps", "8-2 reps"))) {
            val model = Model().also { it.sources = listOf(source(bad)) }
            val reply = SourcedWorkoutService(model).reply(request(), profile, pending, instruction)
            assertNull(reply.workout)
            assertEquals(1, model.requests.size)
        }
    }

    @Test fun publishedLongRestIsNeverCutJustToFitTheSchedule() = runBlocking {
        val model = Model().also { it.sources = listOf(source(fixture.replace("Rest 20 seconds.", "Rest 300 seconds."))) }
        val reply = SourcedWorkoutService(model).reply(request(), profile, FeatureConversation(), "Beginner workout 5 minutes")
        assertNull(reply.workout)
        assertTrue(reply.message.contains("?"))
    }

    @Test fun easierFollowupReducesTimeCapsWithoutLosingTheCooldown() = runBlocking {
        val old = initial(text = "Beginner no equipment workout 15 minutes")
        val easier = requireNotNull(SourcedWorkoutService(Model()).reply(request(), profile, conversation(old), "Make it easier").workout)
        val plan = requireNotNull(easier.sourced!!.timePlan)
        assertEquals(900, plan.totalSeconds)
        assertTrue(plan.stages.all { stage -> stage.blocks.all { it.workSeconds <= 20 } })
        assertEquals(180, plan.stages.last().seconds)
        assertTrue(SourcedWorkoutRules.validate(easier, profile).isEmpty())
        assertEquals(easier, json.decodeFromString<Workout>(json.encodeToString(Workout.serializer(), easier)))
    }

    @Test fun broadBeginnerCalisthenicsMetadataIsAReferenceNotExactFollowAlong() {
        // This metadata shape was observed in the earlier live probe; this test itself is synthetic.
        val returned = ResearchVideo("https://www.youtube.com/watch?v=8gQbgyTlS-8",
            "How to Start Calisthenics at Home For Beginners (No Equipment)", "Pierre Dalati", "TECHNIQUE_REFERENCE", "Metadata only")
        val value = initial(Model().also { it.metadata = returned }, "Beginner no equipment workout 15 minutes").sourced!!
        assertEquals(returned.url, value.video!!.url)
        assertEquals(VideoMatch.TECHNIQUE_REFERENCE, value.video.match)
        assertTrue(value.video.verificationNote.contains("not watched"))
        assertTrue(value.video.verificationNote.contains("not an exact follow-along"))
        assertTrue(value.video.verificationNote.contains("noise level"))
        assertTrue(value.movements.none { FoodRules.contains(returned.title, it.name) })
    }

    @Test fun broadVideoNeedsSupportedExperienceEquipmentAndTopicNotJustWorkoutKeyword() {
        val accepted = "Beginner no equipment bodyweight workout"
        assertNotNull(initial(Model().also { it.metadata = video.copy(title = accepted) }).sourced!!.video)
        listOf("Beginner no equipment yoga workout", "Advanced no equipment calisthenics workout", "Beginner dumbbell workout",
            "Beginner bodyweight workout music playlist", "Beginner calisthenics diet review", "No equipment workout", "Beginner no equipment jumping workout",
            "Deleted video: Beginner no equipment workout").forEach { title ->
            assertNull(initial(Model().also { it.metadata = video.copy(title = title) }).sourced!!.video)
        }
        val intermediate = Model().also { model ->
            model.sources = listOf(source(fixture.replace("beginner", "intermediate", ignoreCase = true)).copy(title = "Intermediate bodyweight workout"))
            model.metadata = video.copy(title = accepted)
        }
        assertNull(initial(intermediate, "No equipment workout", profile.copy(experience = "Intermediate")).sourced!!.video)
    }

    @Test fun missingFormLabelsAndEasierVariantsRemainOriginalInstructionsNotInventedAdvice() {
        val text = fixture.replace(Regex("(?m)^(?:Form|Easier):[^\\n]*\\n?"), "")
        val value = initial(Model().also { it.sources = listOf(source(text)) }, "Beginner no equipment workout 15 minutes").sourced!!
        (value.warmUp + value.movements + value.cooldown).forEach { movement ->
            assertTrue(movement.formCues.isEmpty())
            assertNull(movement.easierAlternative)
            assertTrue(text.contains(movement.instructions))
            assertTrue(movement.evidence.single().excerpt.contains(movement.instructions))
        }
        assertNotNull(WorkoutProgramming.checked(value))
    }

    @Test fun reviewedNhsLayoutsKeepExactHeadingQuotesAndDoseRanges() {
        val sources = NhsWorkoutArticles.urls.mapIndexed { index, url -> nhsSource(url).copy(id = "nhs-$index") }
        val snapshot = RetrievalSnapshot("nhs-layout", "request", sources)
        val blocks = sources.flatMap { PublishedWorkoutParser.blocks(it, snapshot) }
        assertEquals(listOf("March on the spot", "Knee bends", "Wall press-up", "Buttock stretch", "Hamstring stretch", "Inner thigh stretch"), blocks.map { it.movement.name })
        assertEquals(180, blocks[0].movement.seconds)
        assertEquals("10", blocks[1].movement.reps)
        assertEquals(3, blocks[2].movement.sets)
        assertEquals("5 to 10", blocks[2].movement.reps)
        assertEquals("Attempt 3 sets of 5 to 10 repetitions.", blocks[2].movement.publishedDose)
        assertTrue(blocks.last().movement.publishedDose!!.contains("15 to 20 seconds"))
        blocks.forEach { block ->
            val ref = block.movement.evidence.single()
            assertTrue(sources.single { it.id == ref.sourceId }.excerpt.contains(ref.excerpt))
            assertTrue(ref.excerpt.contains(block.movement.name))
            assertNull(block.movement.restSeconds)
            assertNull(block.movement.easierAlternative)
            assertFalse(block.movement.instructions.contains("!["))
        }
        assertFalse(blocks[2].movement.instructions.contains("feet")) // Not in the published wall technique text.
    }

    @Test fun incompleteAceIndexUsesAtMostThreeQuotaBoundNhsSupplements() = runBlocking {
        val model = NhsModel()
        val id = request()
        val reply = SourcedWorkoutService(model).reply(id, profile, FeatureConversation(), "Beginner no equipment workout 15 minutes")
        val workout = requireNotNull(reply.workout)
        val value = workout.sourced!!
        assertEquals(4, model.requests.size)
        assertNull(model.requests[0].url)
        assertEquals(NhsWorkoutArticles.urls, model.requests.drop(1).map { it.url })
        assertTrue(model.requests.all { it.requestId == id.id && it.profileRevision == id.profileRevision && it.task == "workout" })
        assertTrue(model.requests.all { !it.allowExternalModel && it.constraints.equipment == listOf("None") })
        assertEquals(3, value.origin.retrieval!!.sources.size)
        assertEquals(3, value.origin.retrieval.sources.map { it.id }.distinct().size)
        assertEquals(listOf("Wall press-up"), value.movements.map { it.name })
        assertEquals(listOf("Inner thigh stretch"), value.cooldown.map { it.name })
        assertEquals(listOf(360, 240, 300), value.timePlan!!.stages.map { it.seconds })
        assertEquals(900, value.timePlan.totalSeconds)
        assertEquals(180, value.timePlan.stages[0].blocks.first().workSeconds)
        assertEquals(5, value.timePlan.stages[1].blocks.single().repCap)
        assertEquals(1, value.timePlan.stages[1].blocks.single().setCap)
        assertEquals(30, value.timePlan.stages[1].blocks.single().restSeconds)
        assertTrue(value.wallAvailable)
        assertTrue(SourcedWorkoutRules.validate(workout, profile).isEmpty())
        assertTrue((value.warmUp + value.movements + value.cooldown).none { SourcedWorkoutRules.equipment.containsMatchIn(it.instructions) })
    }

    @Test fun missingWallAsksBeforeSupplementaryCallsAndConfirmationUsesThreeUrls() = runBlocking {
        val noWall = profile.copy(hostel = profile.hostel.copy(workoutSpace = "Small quiet room"))
        val model = NhsModel()
        val first = SourcedWorkoutService(model).reply(request(), noWall, FeatureConversation(), "Beginner no equipment workout 20 minutes")
        assertNull(first.workout)
        assertTrue(first.message.contains("Use a wall"))
        assertEquals(1, model.requests.size)
        val reply = SourcedWorkoutService(model).reply(request(), noWall, FeatureConversation(subject = first.subject), "Use a wall")
        val value = requireNotNull(reply.workout).sourced!!
        assertEquals(4, model.requests.size)
        assertEquals(NhsWorkoutArticles.urls, model.requests.drop(1).map { it.url })
        assertEquals(20, value.durationMinutes)
        assertTrue(value.origin.assertions.any { it.text == "Use a wall" })
        assertTrue(SourcedWorkoutRules.validate(reply.workout!!, noWall).isEmpty())
        assertTrue(SourcedWorkoutRules.validate(reply.workout.copy(sourced = value.copy(wallAvailable = false)), noWall).isNotEmpty())
    }

    @Test fun directSupportedWallRequestSkipsTheGenericSearch() = runBlocking {
        val model = NhsModel()
        val reply = SourcedWorkoutService(model).reply(request(), profile, FeatureConversation(), "Beginner workout 15 minutes use a wall")
        assertNotNull(reply.workout)
        assertEquals(NhsWorkoutArticles.urls, model.requests.map { it.url })
    }

    @Test fun alreadyMatchedThreePagesComposeWithoutExtraLookups() = runBlocking {
        val model = NhsModel().also { it.initialSources = NhsWorkoutArticles.urls.mapIndexed { index, url -> nhsSource(url).copy(id = "matched-$index") } }
        val reply = SourcedWorkoutService(model).reply(request(), profile, FeatureConversation(), "Beginner no equipment workout 15 minutes")
        assertNotNull(reply.workout)
        assertEquals(1, model.requests.size)
    }

    @Test fun adaptersRejectDifferentHostPathMissingTechniqueAndMissingGuidance() {
        val original = nhsSource(NhsWorkoutArticles.STRENGTH)
        val snapshot = RetrievalSnapshot("nhs-rejection", "request", listOf(original))
        listOf(original.copy(url = "https://example.org/live-well/exercise/strength-exercises/"),
            original.copy(url = NhsWorkoutArticles.STRENGTH + "?unreviewed=1"),
            original.copy(url = "https://www.nhs.uk/live-well/exercise/other-strength/"),
            original.copy(excerpt = original.excerpt.replace("**C.** Slowly return to the start.", "")),
            original.copy(excerpt = original.excerpt.replace("these strength exercises are gentle and easy to follow", "these exercises are listed here"))).forEach {
            assertTrue(NhsWorkoutArticles.blocks(it, snapshot.copy(sources = listOf(it))).isEmpty())
        }
    }

    @Test fun dosesAloneNeverCompleteTheReviewedWallExercise() = runBlocking {
        val model = NhsModel().also { m -> m.modify = { source -> if (source.url == NhsWorkoutArticles.STRENGTH)
            source.copy(excerpt = source.excerpt.replace("With your back straight, slowly bend your arms, keeping your elbows by your side.", "")) else source } }
        val reply = SourcedWorkoutService(model).reply(request(), profile, FeatureConversation(), "Beginner workout 15 minutes use a wall")
        assertNull(reply.workout)
        assertEquals(3, model.requests.size)
    }

    @Test fun missingCooldownTechniqueAndWrongSupplementUrlAreNotFilledIn() = runBlocking {
        for (wrongUrl in listOf(false, true)) {
            val model = NhsModel().also { m -> m.modify = { source -> if (source.url != NhsWorkoutArticles.COOLDOWN) source else if (wrongUrl)
                source.copy(url = "https://www.nhs.uk/live-well/exercise/unrelated/") else
                source.copy(excerpt = source.excerpt.replace("2. Put the soles of your feet together.", "")) } }
            val reply = SourcedWorkoutService(model).reply(request(), profile, FeatureConversation(), "Beginner workout 15 minutes use a wall")
            assertNull(reply.workout)
            assertEquals(3, model.requests.size)
        }
    }

    @Test fun shortNhsRequestDoesNotCutItsPublishedWarmupGuidance() = runBlocking {
        val model = NhsModel()
        val reply = SourcedWorkoutService(model).reply(request(), profile, FeatureConversation(), "Beginner workout 5 minutes use a wall")
        assertNull(reply.workout)
        assertTrue(reply.message.contains("at least 12 minutes"))
        assertTrue(model.requests.isEmpty())
    }

    @Test fun supplementaryFailurePreservesTheConversationAndNeverFallsBackToUnsourcedClaims() = runBlocking {
        val previous = initial()
        val state = conversation(previous)
        val model = NhsModel().also { it.failUrl = NhsWorkoutArticles.STRENGTH }
        try {
            SourcedWorkoutService(model).reply(request(), profile, state, "Beginner workout 15 minutes use a wall")
            fail("The supplementary timeout must reach the caller's retained-result error path")
        } catch (_: IOException) { }
        assertSame(previous, state.result!!.workout)
        assertEquals(2, model.requests.size)
    }

    @Test fun sourcedNhsFollowupRebindsAllReferencesAndKeepsItsWarmup() = runBlocking {
        val model = NhsModel()
        val service = SourcedWorkoutService(model)
        val original = requireNotNull(service.reply(request(), profile, FeatureConversation(), "Beginner workout 15 minutes use a wall").workout)
        val next = requireNotNull(service.reply(request(), profile, conversation(original), "Make it easier").workout)
        assertEquals(3, model.requests.size)
        assertNotEquals(original.origin!!.retrieval!!.id, next.origin!!.retrieval!!.id)
        assertEquals(original.origin.retrieval!!.sources.map { it.retrievedAt }, next.origin.retrieval!!.sources.map { it.retrievedAt })
        assertEquals(180, next.sourced!!.timePlan!!.stages[0].blocks.first().workSeconds)
        assertEquals(360, next.sourced.timePlan.stages[0].seconds)
        assertEquals(300, next.sourced.timePlan.stages[2].seconds)
        assertTrue(SourcedWorkoutRules.validate(next, profile).isEmpty())
        assertTrue((next.sourced.warmUp + next.sourced.movements + next.sourced.cooldown).flatMap { it.evidence }.all { it.snapshotId == next.origin.retrieval!!.id })
    }

    @Test fun curatedNhsEligibilityCanSupportReturnedBeginnerBodyweightVideoMetadata() = runBlocking {
        val model = NhsModel().also { it.metadata = video.copy(title = "How to Start Calisthenics at Home For Beginners (No Equipment)") }
        val workout = requireNotNull(SourcedWorkoutService(model).reply(request(), profile, FeatureConversation(), "Beginner workout 15 minutes use a wall").workout)
        assertEquals(model.metadata!!.title, workout.sourced!!.video!!.title)
        assertTrue(workout.sourced.video.verificationNote.contains("not an exact follow-along"))
        assertTrue(SourcedWorkoutRules.validate(workout, profile).isEmpty())
    }

    @Test fun plainExtractedHeadingsAndNonbreakingDoseSpacesKeepVerbatimEvidence() {
        val sources = NhsWorkoutArticles.urls.mapIndexed { index, url -> nhsSource(url).let { source -> source.copy(id = "plain-$index",
            excerpt = source.excerpt.replace("## ", "").replace("# ", "")
                .replace("Attempt 3 sets of 5 to 10 repetitions.", "Attempt\u00a03 sets of\u00a05 to 10 repetitions.")) } }
        val snapshot = RetrievalSnapshot("plain-layout", "request", sources)
        val blocks = sources.flatMap { PublishedWorkoutParser.blocks(it, snapshot) }
        assertEquals(6, blocks.size)
        val wall = blocks.single { it.movement.name == "Wall press-up" }.movement
        assertEquals("5 to 10", wall.reps)
        assertTrue(wall.publishedDose!!.contains('\u00a0'))
        assertTrue(sources[1].excerpt.contains(wall.evidence.single().excerpt))
    }
}
