package com.mrpoodles.app

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.LocalDate

/** A new action gets a new ID; a retry must retain both its ID and original payload. */
@Serializable data class IntakeOperation(
    val id: String, val targetId: String, val expectedRevision: Int? = null, val entry: Intake? = null
)
@Serializable data class IntakeReceipt(val operationId: String, val targetId: String, val fingerprint: String)

object IntakeOperations {
    fun apply(data: AppData, operation: IntakeOperation): AppData {
        require(operation.id.isNotBlank() && operation.targetId.isNotBlank())
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(json.encodeToString(IntakeOperation.serializer(), operation).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        data.intakeOperations.find { it.operationId == operation.id }?.let {
            require(it.fingerprint == fingerprint && it.targetId == operation.targetId) { "This action was already used for different changes." }
            return data
        }
        val old = data.intake.find { it.id == operation.targetId }
        if (operation.expectedRevision == null) {
            require(old == null && data.intakeOperations.none { it.targetId == operation.targetId }) { "This food entry already exists or was removed." }
            require(operation.entry != null)
        } else {
            require(old != null && old.entryRevision == operation.expectedRevision) { "This food entry changed. Review it before saving again." }
        }
        val entry = operation.entry?.also {
            require(it.id == operation.targetId && it.name.isNotBlank())
            LocalDate.parse(it.date)
            require(it.portions.isFinite() && it.portions > 0)
            require(listOf(it.kcal, it.protein, it.carbs, it.fat).all { n -> n == null || n.isFinite() && n >= 0 })
            require(it.mealId == null || data.intake.none { other -> other.mealId == it.mealId && other.id != it.id })
        }?.copy(entryRevision = (old?.entryRevision ?: -1) + 1)
        return data.copy(intake = data.intake.filterNot { it.id == operation.targetId } + listOfNotNull(entry),
            intakeOperations = data.intakeOperations + IntakeReceipt(operation.id, operation.targetId, fingerprint))
    }
}
