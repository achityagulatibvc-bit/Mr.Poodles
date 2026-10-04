package com.mrpoodles.app

import org.junit.Assert.*
import org.junit.Test

/** Pure restoration tests use raw serialized instance state, not a pre-sanitized disk read. */
class Phase7RestorationTest {
    private val request = RequestIdentity("lookup-1", 4, 7, "meal-action")
    private val origin = ResultOrigin(request.id, request.profileRevision)
    private val entry = Intake("meal-action:food:0", "2026-10-02", "Kachori", 200.0, origin = origin)
    private val prepared = PreparedFoodLog(request, "Rough estimate", listOf(entry),
        listOf(IntakeOperation("meal-action:add:${entry.id}", entry.id, entry = entry)), origin = origin)
    private val pending = FeatureConversation(draft = "I ate a kachori yesterday", inputRevision = 7,
        activeRequest = request, retryRequest = request, requestDate = "2026-10-03",
        messages = listOf(Message("You", "Private earlier history", id = "old-message")))
    private val completed = pending.copy(draft = "", activeRequest = null, retryRequest = null,
        requestDate = null, lastFoodLog = prepared, result = FeatureResult(origin, intakeIds = listOf(entry.id)),
        selectedDate = entry.date, messages = listOf(Message("Poodles", "Saved in your diary.", id = "confirmation")))
    private val initial = AppData(profile = Profile(rememberChats = true), revision = 4)

    private fun committedData(log: PreparedFoodLog = prepared): AppData =
        log.operations.fold(initial) { data, operation -> IntakeOperations.apply(data, operation) }

    private fun rawSaved(value: FeatureConversation): FeatureConversation =
        json.decodeFromString(json.encodeToString(FeatureConversation.serializer(), value))

    @Test fun rawPendingInstanceStateCannotReplaceCommittedCompletionOrUndo() {
        val data = committedData()
        val restored = FeatureRestoration.restore(Feature.FOOD_LOG, completed, rawSaved(pending), data)
        assertEquals(completed, restored)
        assertNull(restored.retryRequest)
        assertNull(restored.preparedLog)
        assertEquals(prepared, restored.lastFoodLog)
        assertEquals(listOf(entry.id), restored.result!!.intakeIds)
        assertSame(data, prepared.operations.fold(data) { snapshot, op -> IntakeOperations.apply(snapshot, op) })
        val undone = IntakeOperations.apply(data, IntakeOperation("undo", entry.id, entry.entryRevision))
        assertTrue(undone.intake.isEmpty())
    }

    @Test fun newerTypedDraftSurvivesWithoutResurrectingStaleOutcomeOrPreparation() {
        val stale = PreparedFoodLog(request.copy(id = "older", operationId = "older-action"), "Old log", emptyList(), emptyList())
        val saved = pending.copy(draft = "I also ate an apple", inputRevision = 8, activeRequest = null,
            retryRequest = null, preparedLog = null, lastFoodLog = stale, result = FeatureResult(ResultOrigin("older", 3)),
            selectedDate = "2026-10-01", scrollIndex = 3, scrollOffset = 24, error = "Old error")
        val restored = FeatureRestoration.restore(Feature.FOOD_LOG, completed, rawSaved(saved), committedData())
        assertEquals(saved.draft, restored.draft)
        assertEquals(8L, restored.inputRevision)
        assertEquals(saved.selectedDate, restored.selectedDate)
        assertEquals(3, restored.scrollIndex)
        assertEquals(24, restored.scrollOffset)
        assertEquals(completed.result, restored.result)
        assertEquals(completed.messages, restored.messages)
        assertEquals(prepared, restored.lastFoodLog)
        assertNull(restored.preparedLog)
        assertNull(restored.requestDate)
        assertNull(restored.error)
        assertNull(restored.retryRequest)
    }

    @Test fun newerRevisionWithStaleActiveLogicalOperationKeepsDraftButNotRetry() {
        val saved = pending.copy(inputRevision = 9, draft = "New text", preparedLog = prepared)
        val restored = FeatureRestoration.restore(Feature.FOOD_LOG, completed, rawSaved(saved), committedData())
        assertEquals("New text", restored.draft)
        assertEquals(9L, restored.inputRevision)
        assertEquals(completed.result, restored.result)
        assertEquals(prepared, restored.lastFoodLog)
        assertNull(restored.activeRequest)
        assertNull(restored.retryRequest)
        assertNull(restored.preparedLog)
        assertNull(restored.error)
    }

    @Test fun changedRetryPayloadCannotHideOriginalCommittedOperation() {
        val retry = request.copy(id = "lookup-2")
        val changed = prepared.copy(request = retry, entries = listOf(entry.copy(date = "2026-10-04")),
            operations = prepared.operations.map { it.copy(entry = entry.copy(date = "2026-10-04")) })
        val saved = pending.copy(activeRequest = retry, retryRequest = retry, preparedLog = changed)
        assertEquals(completed, FeatureRestoration.restore(Feature.FOOD_LOG, completed, rawSaved(saved), committedData()))
    }

    @Test fun completionRequiresEveryReceiptWithMatchingFingerprintAndTarget() {
        val data = committedData()
        val receipt = data.intakeOperations.single()
        val second = entry.copy(id = "meal-action:food:1", name = "Apple")
        val batch = prepared.copy(entries = prepared.entries + second,
            operations = prepared.operations + IntakeOperation("meal-action:add:${second.id}", second.id, entry = second))
        val candidates = listOf(
            completed to initial,
            completed to data.copy(intakeOperations = listOf(receipt.copy(fingerprint = "wrong"))),
            completed to data.copy(intakeOperations = listOf(receipt.copy(targetId = "wrong"))),
            completed.copy(lastFoodLog = batch) to data,
            completed.copy(lastFoodLog = prepared.copy(operations = emptyList())) to data
        )
        candidates.forEach { (disk, snapshot) ->
            val restored = FeatureRestoration.restore(Feature.FOOD_LOG, disk, rawSaved(pending), snapshot)
            assertEquals(pending.draft, restored.draft)
            assertEquals(request, restored.retryRequest)
            assertNull(restored.lastFoodLog)
            assertNotNull(restored.error)
        }
    }

    @Test fun failedPreparedCorrectionPreservesExactDateRevisionsAndBytes() {
        val previous = entry.copy(entryRevision = 5)
        val corrected = previous.copy(kcal = 400.0, entryRevision = 6)
        val correction = prepared.copy(entries = listOf(corrected), previousEntries = listOf(previous),
            operations = listOf(IntakeOperation("meal-action:update:${entry.id}", entry.id, 5, corrected)))
        val disk = pending.copy(preparedLog = correction)
        val retry = request.copy(id = "lookup-retry")
        val rebuilt = correction.copy(request = retry, operations = correction.operations.map { it.copy(expectedRevision = 6) })
        val failed = pending.copy(activeRequest = null, retryRequest = retry, preparedLog = rebuilt,
            requestDate = "2026-10-04", error = "Disk full")
        val restored = FeatureRestoration.restore(Feature.FOOD_LOG, disk, rawSaved(failed), initial)
        assertSame(correction, restored.preparedLog)
        assertEquals(json.encodeToString(PreparedFoodLog.serializer(), correction),
            json.encodeToString(PreparedFoodLog.serializer(), restored.preparedLog!!))
        assertEquals("2026-10-03", restored.requestDate)
        assertEquals("2026-10-02", restored.preparedLog!!.entries.single().date)
        assertEquals(5, restored.preparedLog!!.operations.single().expectedRevision)
        assertEquals(listOf(previous), restored.preparedLog!!.previousEntries)
        assertEquals(retry, restored.retryRequest)
        assertEquals("Disk full", restored.error)
    }

    @Test fun instanceStateCapturedBeforePreparationRecoversDurablePayload() {
        val disk = pending.copy(preparedLog = prepared)
        val restored = FeatureRestoration.restore(Feature.FOOD_LOG, disk, rawSaved(pending), initial)
        assertSame(prepared, restored.preparedLog)
        assertEquals(request, restored.retryRequest)
        assertNull(restored.activeRequest)
        assertEquals(disk.requestDate, restored.requestDate)
        assertNotNull(restored.error)
    }

    @Test fun instanceStateCapturedBeforeSendRecoversPreparedRetryRatherThanRepeatingLookup() {
        val disk = pending.copy(preparedLog = prepared)
        val saved = pending.copy(activeRequest = null, retryRequest = null, requestDate = null)
        val restored = FeatureRestoration.restore(Feature.FOOD_LOG, disk, rawSaved(saved), initial)
        assertSame(prepared, restored.preparedLog)
        assertEquals(request, restored.retryRequest)
        assertNotNull("Retry must be marked interrupted so the caller reuses preparation", restored.error)
        assertEquals(disk.requestDate, restored.requestDate)
    }

    @Test fun newerEditDoesNotInheritAbandonedPreparation() {
        val disk = pending.copy(preparedLog = prepared)
        val saved = pending.edit("Actually, I ate an apple").copy(preparedLog = null, requestDate = null)
        val restored = FeatureRestoration.restore(Feature.FOOD_LOG, disk, rawSaved(saved), initial)
        assertEquals(saved, restored)
        assertNull(restored.preparedLog)
    }

    @Test fun failedNewLogDoesNotFallBackToPreviousCommittedLog() {
        val next = request.copy(id = "next-request", operationId = "next-action", inputRevision = 8)
        val disk = completed.copy(draft = "I ate an apple", inputRevision = 8,
            activeRequest = next, retryRequest = next)
        val failed = disk.copy(activeRequest = null, error = "Lookup failed")
        assertEquals(failed, FeatureRestoration.restore(Feature.FOOD_LOG, disk, rawSaved(failed), committedData()))
    }

    @Test fun unknownNutritionAwaitingConfirmationKeepsDurablePreparation() {
        val unknown = prepared.copy(entries = listOf(entry.copy(kcal = null)),
            operations = prepared.operations.map { it.copy(entry = entry.copy(kcal = null)) })
        val disk = pending.copy(activeRequest = null, retryRequest = null, preparedLog = unknown)
        val saved = pending.copy(activeRequest = null, retryRequest = null)
        val restored = FeatureRestoration.restore(Feature.FOOD_LOG, disk, rawSaved(saved), initial)
        assertSame(unknown, restored.preparedLog)
        assertNull(restored.error)
        assertNull(restored.retryRequest)
        assertEquals(disk.requestDate, restored.requestDate)
    }

    @Test fun newerIndependentPendingLogIsRecoveredInsteadOfDiscardedForOldCompletion() {
        val next = request.copy(id = "next-request", operationId = "next-action", inputRevision = 8)
        val saved = pending.copy(draft = "I ate an apple", inputRevision = 8, activeRequest = next, retryRequest = next)
        val restored = FeatureRestoration.restore(Feature.FOOD_LOG, completed, rawSaved(saved), committedData())
        assertEquals(saved.draft, restored.draft)
        assertEquals(next, restored.retryRequest)
        assertNotNull(restored.error)
    }

    @Test fun privacyIsAppliedToEveryFeatureAndEveryRestorationPath() {
        val snapshots = listOf<Pair<FeatureConversation?, FeatureConversation?>>(
            null to null, pending to null, null to rawSaved(pending),
            completed to rawSaved(pending), completed to rawSaved(pending.edit("New private draft")),
            pending.copy(preparedLog = prepared) to rawSaved(pending),
            pending.copy(inputRevision = 9) to rawSaved(pending)
        )
        val data = committedData().copy(profile = Profile(rememberChats = false))
        Feature.entries.forEach { feature ->
            snapshots.forEach { (disk, saved) ->
                val restored = FeatureRestoration.restore(feature, disk, saved, data)
                assertTrue("History leaked for $feature", restored.messages.isEmpty())
            }
        }
        assertTrue("Restoration must not modify its input", pending.messages.isNotEmpty())
    }

    @Test fun revokedHistoryStillPreservesDiaryEvidenceCompletionAndUndo() {
        val data = committedData().copy(profile = Profile(rememberChats = false))
        val restored = FeatureRestoration.restore(Feature.FOOD_LOG, completed, rawSaved(pending), data)
        assertEquals(completed.copy(messages = emptyList()), restored)
        assertEquals(origin, restored.lastFoodLog!!.entries.single().origin)
        assertEquals(listOf(entry), data.intake)
    }

    @Test fun unrelatedFeaturesKeepTheirOwnNewerDraftFieldsAndRecoverInterruptedRequests() {
        val data = committedData().copy(features = mapOf(Feature.FOOD_LOG to completed))
        Feature.entries.filter { it != Feature.FOOD_LOG }.forEach { feature ->
            val ownRequest = request.copy(id = feature.name, operationId = null, inputRevision = 8)
            val disk = FeatureConversation(draft = "Older ${feature.name}", inputRevision = 7)
            val saved = FeatureConversation(draft = "Newer ${feature.name}", inputRevision = 8,
                subject = "Own subject", exclusions = listOf("coconut"), brand = "Own brand", country = "India",
                activeRequest = ownRequest, selectedDate = "2026-10-09", scrollIndex = 2, scrollOffset = 18)
            assertEquals(saved.recover(), FeatureRestoration.restore(feature, disk, rawSaved(saved), data))
            assertEquals(saved.recover(), FeatureRestoration.restore(feature, saved, rawSaved(disk), data))
        }
        assertEquals(completed, data.features[Feature.FOOD_LOG])
    }

    @Test fun missingSnapshotsHaveDeterministicDefaultsAndDiskOnlyRecovery() {
        Feature.entries.forEach { feature ->
            assertEquals(FeatureConversation(), FeatureRestoration.restore(feature, null, null, initial))
            assertEquals(pending.recover(), FeatureRestoration.restore(feature, pending, null, initial))
            assertEquals(pending.recover(), FeatureRestoration.restore(feature, null, rawSaved(pending), initial))
        }
    }
}
