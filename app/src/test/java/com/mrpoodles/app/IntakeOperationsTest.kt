package com.mrpoodles.app

import org.junit.Assert.*
import org.junit.Test

class IntakeOperationsTest {
    private val entry = Intake(id = "food", date = "2026-10-03", name = "Kachori", kcal = null)
    private val create = IntakeOperation("create", entry.id, entry = entry)
    private fun reload(data: AppData) = SnapshotMigration.decode(json.encodeToString(AppData.serializer(), data))

    @Test fun retryAfterReloadDoesNotDuplicateAndCorrectionKeepsIdentity() {
        val first = reload(IntakeOperations.apply(AppData(), create))
        assertEquals(first, IntakeOperations.apply(first, create))
        val correction = IntakeOperation("correct", entry.id, 0, entry.copy(portions = 2.0, kcal = 400.0))
        val corrected = reload(IntakeOperations.apply(first, correction))
        assertEquals(1, corrected.intake.size)
        assertEquals(entry.id, corrected.intake.single().id)
        assertEquals(1, corrected.intake.single().entryRevision)
        assertEquals(400.0, corrected.intake.single().kcal!!, 0.0)
        assertEquals(corrected, IntakeOperations.apply(corrected, correction))
        assertEquals(corrected, IntakeOperations.apply(corrected, create))
        assertNull(corrected.intake.single().protein)
    }

    @Test fun reusedKeyWithChangedPayloadAndOutOfOrderCorrectionsAreRejected() {
        val first = IntakeOperations.apply(AppData(), create)
        assertThrows(IllegalArgumentException::class.java) {
            IntakeOperations.apply(first, create.copy(entry = entry.copy(kcal = 500.0)))
        }
        val corrected = IntakeOperations.apply(first, IntakeOperation("correction-1", entry.id, 0, entry.copy(portions = 2.0)))
        assertThrows(IllegalArgumentException::class.java) {
            IntakeOperations.apply(corrected, IntakeOperation("late-correction", entry.id, 0, entry.copy(portions = 0.5)))
        }
    }

    @Test fun deletionRetainsReceiptsSoDelayedRetriesNeverResurrectFood() {
        val first = IntakeOperations.apply(AppData(), create)
        val remove = IntakeOperation("delete", entry.id, 0)
        val deleted = reload(IntakeOperations.apply(first, remove))
        assertEquals(deleted, IntakeOperations.apply(deleted, create))
        assertEquals(deleted, IntakeOperations.apply(deleted, remove))
        assertTrue(deleted.intake.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { IntakeOperations.apply(deleted, create.copy(id = "different")) }
        // A genuinely separate food on the same day is allowed.
        val separate = entry.copy(id = "another-food")
        assertEquals(listOf(separate), IntakeOperations.apply(deleted, IntakeOperation("new", separate.id, entry = separate)).intake)
    }

    @Test fun oldEntriesCanBeCorrectedWithoutChangingIdsOrLosingNeighbors() {
        val other = entry.copy(id = "other", name = "Tea")
        val existing = AppData(intake = listOf(entry, other))
        val updated = IntakeOperations.apply(existing, IntakeOperation("edit-old", entry.id, 0, entry.copy(kcal = 300.0)))
        assertEquals(other, updated.intake.first())
        assertEquals(entry.id, updated.intake.last().id)
        assertThrows(Exception::class.java) {
            IntakeOperations.apply(existing, IntakeOperation("bad", "bad", entry = entry.copy(id = "bad", date = "not a date")))
        }
    }
}
