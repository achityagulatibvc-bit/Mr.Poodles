package com.mrpoodles.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** Synthetic source fixtures. Persistence/VM integration and live-provider checks belong to main. */
class Phase6FoodLoggingTest {
    private val today = LocalDate.of(2026, 10, 4)
    private val profile = Profile(sourceLookupConsent = true, restrictions = emptyList())
    private fun id(operation: String = "meal-1", request: String = "request-1") = RequestIdentity(request, 7, operationId = operation)
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/phase6/$name.txt")).readText()
    private fun source(name: String = "kachori-nutrition", title: String = "Pyaaz kachori nutrition", text: String = fixture(name), key: String = "nutrition") =
        RetrievedSource(key, "https://tools.myfooddata.com/fixture/$key", title, "2026-10-04T10:00:00Z",
            author = "Synthetic Fixture Author", publisher = "Synthetic Test Publisher", excerpt = text, kind = "nutrition")
    private inner class Model : CloudModel() {
        val requests = mutableListOf<ResearchRequest>()
        var failure: Exception? = null
        var sources: ((ResearchRequest) -> List<RetrievedSource>) = { request ->
            when {
                request.subject.contains("banana") -> listOf(source("banana-nutrition", "Banana nutrition"))
                request.subject.contains("chutney") -> listOf(source("chutney-nutrition", "Chutney nutrition"))
                else -> listOf(source())
            }
        }
        var mutate: (ResearchResponse) -> ResearchResponse = { it }
        override suspend fun research(input: ResearchRequest): ResearchResponse {
            requests += input
            failure?.let { throw it }
            val snapshot = RetrievalSnapshot("snapshot", input.requestId, sources(input))
            return mutate(ResearchResponse(2, input.requestId, input.profileRevision, snapshot,
                answer = ResearchAnswer("Let's look at these sources together.", emptyList(), emptyList()), provider = "synthetic"))
        }
    }
    private suspend fun prepare(text: String = "Pyaaz kachori khayi hai", model: Model = Model(),
        conversation: FeatureConversation = FeatureConversation(), existing: List<Intake> = emptyList(),
        identity: RequestIdentity = id(), p: Profile = profile, day: LocalDate = today) =
        FoodLoggingService(model).prepare(identity, p, conversation, text, day, existing)
    private fun apply(batch: PreparedFoodLog, data: AppData = AppData()) = batch.operations.fold(data, IntakeOperations::apply)
    private fun conversation(batch: PreparedFoodLog) = FeatureConversation(result = FeatureResult(batch.origin!!, intakeIds = batch.entries.map { it.id }))

    @Test fun hinglishEatenStatementHasVisibleMediumAssumptionAndFullSources() = runBlocking {
        val model = Model()
        val result = prepare(model = model)
        val entry = result.entries.single()
        assertEquals("pyaaz kachori", entry.name)
        assertEquals(today.toString(), entry.date)
        assertEquals(200.0, entry.kcal!!, 0.001)
        assertEquals(4.0, entry.protein!!, 0.001)
        assertEquals(1.0, entry.portion!!.amount, 0.001)
        assertEquals("medium pyaaz kachori", entry.portion.description)
        assertTrue(entry.portion.assumed)
        assertNull(entry.portion.grams)
        assertTrue(entry.estimate!!.explanation.contains("Assumption"))
        assertEquals(fixture("kachori-nutrition"), entry.origin!!.retrieval!!.sources.single().excerpt)
        assertEquals("Synthetic Fixture Author", entry.origin.retrieval!!.sources.single().author)
        entry.estimate.evidence.forEach { ref ->
            assertEquals(entry.origin.retrieval!!.id, ref.snapshotId)
            assertTrue(entry.origin.retrieval!!.sources.single { it.id == ref.sourceId }.excerpt.contains(ref.excerpt))
        }
        assertEquals("food_log", model.requests.single().task)
        assertEquals("pyaaz kachori nutrition per piece", model.requests.single().subject)
        assertTrue(result.entries.single().id.startsWith("meal-1:"))
        assertTrue(result.operations.single().id.startsWith("meal-1:"))
        assertFalse(result.message.contains("Saved in"))
    }

    @Test fun englishHadAndHindiVerbVariantsLog() = runBlocking {
        for (text in listOf("I ate a pyaaz kachori", "I had pyaaz kachori", "Maine pyaaz kachori khaya", "Pyaaz kachori khai hai")) {
            assertEquals(text, 200.0, prepare(text).entries.single().kcal!!, 0.001)
        }
    }

    @Test fun twoAndHalfCorrectionsKeepEntryAndIncreaseRevision() = runBlocking {
        val first = prepare()
        val data = apply(first)
        val model = Model()
        val two = prepare("I ate two", model, conversation(first), data.intake, id("correction-2"))
        assertEquals(first.entries.single().id, two.entries.single().id)
        assertEquals(400.0, two.entries.single().kcal!!, 0.001)
        assertEquals(1, two.entries.single().entryRevision)
        assertEquals(0, two.operations.single().expectedRevision)
        assertTrue(two.entries.single().portion!!.assumed)
        assertEquals(data.intake, two.previousEntries)
        val updated = apply(two, data)
        val half = prepare("Half", model, conversation(two), updated.intake, id("correction-half"))
        assertEquals(first.entries.single().id, half.entries.single().id)
        assertEquals(200.0, half.entries.single().kcal!!, 0.001)
        assertEquals(1.0, half.entries.single().portion!!.amount, 0.001)
        assertEquals(2, half.entries.single().entryRevision)
        assertEquals(1, apply(half, updated).intake.size)
        assertTrue(model.requests.isEmpty())
    }

    @Test fun explicitHalfIsHalfAPieceRatherThanHalfLastAmount() = runBlocking {
        val first = prepare("I ate two pyaaz kachoris")
        val corrected = prepare("I ate half", conversation = conversation(first), existing = apply(first).intake, identity = id("half-piece"))
        assertEquals(0.5, corrected.entries.single().portion!!.amount, 0.001)
        assertEquals(100.0, corrected.entries.single().kcal!!, 0.001)
    }

    @Test fun chutneyIsAnAdditionalEntryWithExplicitUnknownAction() = runBlocking {
        val first = prepare()
        val extra = prepare("There was chutney too", conversation = conversation(first), existing = apply(first).intake, identity = id("chutney"))
        assertEquals("chutney", extra.entries.single().name)
        assertNotEquals(first.entries.single().id, extra.entries.single().id)
        assertNull(extra.entries.single().kcal)
        assertNull(extra.entries.single().portion!!.grams)
        assertTrue(extra.message.contains("Save with unknown nutrition"))
        assertEquals(2, apply(extra, apply(first)).intake.size)
    }

    @Test fun multipleFoodsProduceOneStableOperationBatch() = runBlocking {
        val result = prepare("I ate two pyaaz kachoris and a banana")
        assertEquals(2, result.entries.size)
        assertEquals(listOf(400.0, 100.0), result.entries.map { it.kcal })
        assertEquals(2, result.operations.map { it.id }.distinct().size)
        assertEquals(500.0, apply(result).intake.sumOf { it.kcal!! }, 0.001)
        assertEquals(2, result.origin!!.retrieval!!.sources.size)
    }

    @Test fun yesterdayAndExplicitDatesUseSuppliedLocalDay() = runBlocking {
        assertEquals("2026-10-03", prepare("I ate a banana yesterday").entries.single().date)
        assertEquals("2026-09-30", prepare("I ate a banana on 2026-09-30").entries.single().date)
        assertEquals("2026-10-02", prepare("I ate a banana on 2 October 2026").entries.single().date)
        assertEquals("2026-10-02", prepare("I ate a banana on October 2, 2026").entries.single().date)
        assertEquals("2025-12-31", prepare("I ate a banana yesterday", day = LocalDate.of(2026, 1, 1)).entries.single().date)
        val original = prepare("I ate a banana")
        val corrected = prepare("I ate two yesterday", conversation = conversation(original), existing = apply(original).intake, identity = id("date-change"))
        assertEquals("2026-10-03", corrected.entries.single().date)
        val halved = prepare("Half", conversation = conversation(corrected), existing = apply(corrected, apply(original)).intake, identity = id("later-half"))
        assertEquals("2026-10-03", halved.entries.single().date)
    }

    @Test fun ambiguousInvalidFutureOrMultipleDatesAskWithoutLookup() = runBlocking {
        val model = Model()
        for (text in listOf("I ate banana on 04/05/2026", "I ate banana kal", "I ate banana tomorrow", "I ate banana on 2026-02-30",
            "I ate banana yesterday and today", "I ate banana on 31 February 2026")) {
            val value = prepare(text, model)
            assertTrue(text, value.entries.isEmpty())
            assertTrue(text, value.operations.isEmpty())
        }
        assertTrue(model.requests.isEmpty())
    }

    @Test fun calorieAndProteinQuestionsAnswerWithoutLogging() = runBlocking {
        for (text in listOf("How many calories in pyaaz kachori?", "How much protein in pyaaz kachori?", "calories in pyaaz kachori")) {
            val result = prepare(text)
            assertTrue(result.entries.isEmpty())
            assertTrue(result.operations.isEmpty())
            assertTrue(result.message.contains("about 200 kcal"))
            assertTrue(result.message.contains("Protein 4 g"))
            assertNotNull(result.origin!!.retrieval)
        }
        assertTrue(prepare("I ate two?").operations.isEmpty())
    }

    @Test fun questionsNegationFutureAndOtherPeopleNeverAuthorizeWrites() = runBlocking {
        for (text in listOf("Should I eat kachori?", "Can I have a banana", "I did not eat a banana", "I didn't eat banana",
            "I will eat banana", "Maine banana nahi khaya", "My friend ate banana", "I want banana", "banana")) {
            assertTrue(text, prepare(text).operations.isEmpty())
        }
    }

    @Test fun massBasisCannotBeAveragedWithPiecesWithoutWeight() = runBlocking {
        val model = Model().also { it.sources = { listOf(source(), source("kachori-per100g", key = "grams")) } }
        val result = prepare(model = model).entries.single()
        assertEquals(200.0, result.kcal!!, 0.001)
        assertNull(result.estimate!!.kcalLow)
        assertEquals(1, result.estimate.evidence.size)
        assertEquals(2, result.origin!!.retrieval!!.sources.size)
        val gramsOnly = Model().also { it.sources = { listOf(source("kachori-per100g")) } }
        assertNull(prepare(model = gramsOnly).entries.single().kcal)
    }

    @Test fun explicitGramsNormalizeMassBasis() = runBlocking {
        val model = Model().also { it.sources = { listOf(source("kachori-per100g")) } }
        val result = prepare("I ate 50 g pyaaz kachori", model).entries.single()
        assertEquals(175.0, result.kcal!!, 0.001)
        assertEquals(50.0, result.portion!!.grams!!, 0.001)
        assertFalse(result.portion.assumed)
    }

    @Test fun spoonAndDifferentSizeSourcesRemainUnknown() = runBlocking {
        assertNull(prepare("I ate 2 tbsp chutney").entries.single().kcal)
        val model = Model().also { it.sources = { listOf(source(text = fixture("kachori-nutrition").replace("medium", "large"))) } }
        assertNull(prepare(model = model).entries.single().kcal)
    }

    @Test fun comparableSourceDisagreementHasRangeNotFalsePrecision() = runBlocking {
        val model = Model().also { it.sources = { listOf(source(), source(text = fixture("kachori-nutrition").replace("200 kcal", "320 kcal"), key = "second")) } }
        val entry = prepare(model = model).entries.single()
        assertEquals(260.0, entry.kcal!!, 0.001)
        assertEquals(200.0, entry.estimate!!.kcalLow!!, 0.001)
        assertEquals(320.0, entry.estimate.kcalHigh!!, 0.001)
        assertTrue(entry.estimate.explanation.contains("Sources disagree"))
        assertEquals(2, entry.estimate.evidence.size)
    }

    @Test fun missingMacrosStayNullIncludingAfterCorrection() = runBlocking {
        val model = Model().also { it.sources = { listOf(source(text = fixture("kachori-nutrition").replace("Protein: 4 g", "Protein: unknown").replace("Total fat: 10 g", "Total fat: unknown"))) } }
        val first = prepare(model = model)
        val entry = first.entries.single()
        assertEquals(200.0, entry.kcal!!, 0.001)
        assertNull(entry.protein)
        assertNull(entry.fat)
        val correction = prepare("I ate two", conversation = conversation(first), existing = apply(first).intake, identity = id("two"))
        assertNull(correction.entries.single().protein)
        assertNull(correction.entries.single().fat)
    }

    @Test fun saturatedFatIsNotTotalFatAndMissingMacroOnAnotherSourceRemainsUnknown() = runBlocking {
        val model = Model().also { it.sources = { listOf(source(text = fixture("kachori-nutrition").replace("Total fat: 10 g", "Saturated fat: 2 g"))) } }
        assertNull(prepare(model = model).entries.single().fat)
        model.sources = { listOf(source(), source(text = fixture("kachori-nutrition").replace("Protein: 4 g", "Protein: unknown"), key = "other")) }
        assertNull(prepare(model = model).entries.single().protein)
    }

    @Test fun modelProseOrAdjustmentCannotInventNutrients() = runBlocking {
        val model = Model().also {
            it.sources = { listOf(source(text = "Pyaaz kachori is discussed here. No measured nutrition or serving basis is available. ".repeat(3))) }
            it.mutate = { response -> response.copy(answer = ResearchAnswer("Definitely 150 calories and 12 grams of protein!", listOf(ResearchClaim("200 kcal", "ai_adjustment", emptyList())), emptyList())) }
        }
        val result = prepare(model = model)
        assertNull(result.entries.single().kcal)
        assertNull(result.entries.single().protein)
        assertFalse(result.message.contains("150"))
        assertTrue(result.entries.single().estimate!!.evidence.isEmpty())
    }

    @Test fun currentQuoteSchemaValidatedBeforeFullSourceParsing() = runBlocking {
        val model = Model().also { it.mutate = { response -> response.copy(answer = response.answer.copy(claims = listOf(
            ResearchClaim("Calories: 200 kcal", "source", listOf(EvidenceReference("snapshot", "nutrition", "Calories: 200 kcal")))))) } }
        assertEquals(200.0, prepare(model = model).entries.single().kcal!!, 0.001)
        model.mutate = { response -> response.copy(answer = response.answer.copy(claims = listOf(
            ResearchClaim("Calories: 999 kcal", "source", listOf(EvidenceReference("snapshot", "nutrition", "Calories: 999 kcal")))))) }
        assertNull(prepare(model = model).entries.single().kcal)
    }

    @Test fun unrelatedSourceAndUnlabelledNumbersCannotBecomeEstimates() = runBlocking {
        val unrelated = Model().also { it.sources = { listOf(source(title = "Samosa nutrition")) } }
        assertNull(prepare(model = unrelated).entries.single().kcal)
        val unlabelled = Model().also { it.sources = { listOf(source(text = fixture("kachori-nutrition").replace("Serving size: 1 medium kachori", "Ingredients and background"))) } }
        assertNull(prepare(model = unlabelled).entries.single().kcal)
    }

    @Test fun lookupFailureRetainsUnknownEntriesButDoesNotMutateExisting() = runBlocking {
        val first = prepare()
        val before = apply(first)
        val model = Model().also { it.failure = IOException("offline") }
        val result = prepare("I ate a banana", model, existing = before.intake, identity = id("offline"))
        assertNull(result.entries.single().kcal)
        assertNull(result.entries.single().origin!!.retrieval)
        assertTrue(result.message.contains("nothing is confirmed saved"))
        assertEquals(1, before.intake.size)
        assertEquals(1, result.operations.size)
        assertTrue(prepare("How many calories in banana?", model).operations.isEmpty())
    }

    @Test fun cancellationIsNotAnUnknownFoodResult() = runBlocking {
        val model = Model().also { it.failure = CancellationException("Stopped") }
        try { prepare(model = model); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }

    @Test fun restrictedActuallyEatenFoodStillHasOperationWithSeparateWarning() = runBlocking {
        val p = profile.copy(restrictions = listOf(Restriction("Onion", aliases = "pyaaz, pyaz")))
        val result = prepare(p = p)
        assertEquals(1, result.operations.size)
        assertEquals(200.0, result.entries.single().kcal!!, 0.001)
        assertTrue(result.message.contains("Separate restriction note: Onion"))
        assertEquals(p, p.copy())
    }

    @Test fun durablePreparedReplayPreservesPayloadDatesAndExpectedRevision() = runBlocking {
        val first = prepare()
        val corrected = prepare("I ate two", conversation = conversation(first), existing = apply(first).intake, identity = id("correction"))
        val durable = json.decodeFromString(PreparedFoodLog.serializer(), json.encodeToString(PreparedFoodLog.serializer(), corrected))
        val model = Model().also { it.failure = IOException("Must not research a prepared retry") }
        val retry = prepare("I ate two", model, conversation(corrected).copy(preparedLog = durable), apply(corrected, apply(first)).intake,
            id("correction", "fresh-attempt"), day = today.plusDays(1))
        assertEquals(durable, retry)
        assertTrue(model.requests.isEmpty())
        val data = apply(corrected, apply(first))
        assertEquals(data, apply(retry, data))
        assertEquals(today.toString(), retry.entries.single().date)
        assertEquals(0, retry.operations.single().expectedRevision)
    }

    @Test fun replayOfMultipleFoodBatchIsANoOpAfterSerialization() = runBlocking {
        val prepared = prepare("I ate pyaaz kachori and banana")
        val saved = apply(prepared)
        val restored = json.decodeFromString(PreparedFoodLog.serializer(), json.encodeToString(PreparedFoodLog.serializer(), prepared))
        assertEquals(saved, apply(restored, saved))
        assertEquals(2, saved.intakeOperations.size)
    }

    @Test fun preparedPreviousEntrySupportsUndoAndDelayedCreateCannotResurrectDeletedFood() = runBlocking {
        val first = prepare()
        val saved = apply(first)
        val changed = prepare("I ate two", conversation = conversation(first), existing = saved.intake, identity = id("change"))
        val edited = apply(changed, saved)
        val original = changed.previousEntries.single()
        val undo = IntakeOperation("undo-change", original.id, edited.intake.single().entryRevision, original)
        val restored = IntakeOperations.apply(edited, undo)
        assertEquals(200.0, restored.intake.single().kcal!!, 0.001)
        assertEquals(2, restored.intake.single().entryRevision)
        val deleted = IntakeOperations.apply(restored, IntakeOperation("undo-create", original.id, 2))
        assertTrue(deleted.intake.isEmpty())
        assertEquals(deleted, apply(first, deleted))
        assertEquals(deleted, apply(changed, deleted))
    }

    @Test fun staleCorrectionAndChangedPayloadRejectedWithoutPublishingPartialBatch() = runBlocking {
        val first = prepare()
        val before = apply(first)
        val a = prepare("I ate two", conversation = conversation(first), existing = before.intake, identity = id("a"))
        val b = prepare("I ate half", conversation = conversation(first), existing = before.intake, identity = id("b"))
        val committed = apply(a, before)
        try { apply(b, committed); fail("Stale revision must fail") } catch (_: IllegalArgumentException) { }
        try { IntakeOperations.apply(committed, a.operations.single().copy(entry = a.entries.single().copy(kcal = 100.0))); fail("Changed retry payload must fail") }
        catch (_: IllegalArgumentException) { }
        assertEquals(400.0, committed.intake.single().kcal!!, 0.001)
    }

    @Test fun noTargetAndMultipleTargetsAskBeforeCorrection() = runBlocking {
        assertTrue(prepare("Half").entries.isEmpty())
        val multiple = prepare("I ate pyaaz kachori and banana")
        val response = prepare("I ate two", conversation = conversation(multiple), existing = apply(multiple).intake, identity = id("edit"))
        assertTrue(response.operations.isEmpty())
        assertTrue(response.message.contains("Which food"))
    }

    @Test fun consentAndFallbackRespectProfileWithoutSharingConversation() = runBlocking {
        val model = Model()
        try { prepare(model = model, p = profile.copy(sourceLookupConsent = false)); fail("Consent required") } catch (_: IllegalStateException) { }
        assertTrue(model.requests.isEmpty())
        prepare(model = model, p = profile.copy(externalModelConsent = true), conversation = FeatureConversation(messages = listOf(Message("You", "private unrelated detail"))))
        assertTrue(model.requests.single().allowExternalModel)
        assertFalse(json.encodeToString(ResearchRequest.serializer(), model.requests.single()).contains("private"))
    }
}
