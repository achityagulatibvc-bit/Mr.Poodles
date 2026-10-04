package com.mrpoodles.app

/** Reconciles instance state with the snapshot that durably owns diary effects and receipts. */
object FeatureRestoration {
    fun restore(feature: Feature, disk: FeatureConversation?, saved: FeatureConversation?, data: AppData): FeatureConversation {
        val restored = when {
            disk == null -> saved ?: FeatureConversation()
            saved == null -> disk
            feature == Feature.FOOD_LOG -> restoreFoodLog(disk, saved, data)
            saved.inputRevision >= disk.inputRevision -> saved
            else -> disk
        }.recover()
        // Instance state can predate revocation, even when the disk snapshot is already sanitized.
        return if (data.profile.rememberChats) restored else restored.copy(messages = emptyList())
    }

    private fun restoreFoodLog(disk: FeatureConversation, saved: FeatureConversation, data: AppData): FeatureConversation {
        val committed = disk.lastFoodLog?.takeIf {
            disk.activeRequest == null && disk.retryRequest == null && disk.preparedLog == null &&
                disk.result?.intakeIds == it.entries.map { entry -> entry.id } && isCommitted(it, data)
        }
        val savedOperation = pendingOperation(saved)
        if (committed != null && (saved.inputRevision <= disk.inputRevision || savedOperation == null ||
                savedOperation == committed.request.operationId)) {
            // An old in-flight attempt must not rebuild an already committed logical operation.
            // Only newer composer/navigation state may cross this boundary, never stale outcomes.
            return if (saved.inputRevision > disk.inputRevision) disk.copy(
                draft = saved.draft, inputRevision = saved.inputRevision,
                selectedDate = saved.selectedDate, scrollIndex = saved.scrollIndex, scrollOffset = saved.scrollOffset,
                activeRequest = null, retryRequest = null, preparedLog = null, requestDate = null, error = null
            ) else disk
        }

        val latest = if (saved.inputRevision >= disk.inputRevision) saved else disk
        val prepared = disk.preparedLog
        // An instance snapshot may precede preparation or contain a rebuilt retry payload.
        // Reuse the original request, dates, expected revisions, evidence and operation bytes.
        return if (prepared != null && latest.inputRevision == disk.inputRevision &&
            (pendingOperation(latest) == null || pendingOperation(latest) == prepared.request.operationId) &&
            prepared.request.operationId != null) {
            val retained = latest.copy(preparedLog = prepared, requestDate = disk.requestDate)
            if (pendingOperation(latest) == null) retained.copy(activeRequest = disk.activeRequest,
                retryRequest = disk.retryRequest, error = latest.error ?: disk.error) else retained
        } else latest
    }

    private fun pendingOperation(value: FeatureConversation): String? =
        (value.activeRequest ?: value.retryRequest ?: value.preparedLog?.request)?.operationId

    private fun isCommitted(prepared: PreparedFoodLog, data: AppData): Boolean =
        prepared.operations.isNotEmpty() && prepared.operations.all { operation ->
            // Checking presence first prevents this pure lookup from accepting a newly applied effect.
            data.intakeOperations.any { it.operationId == operation.id } &&
                runCatching { IntakeOperations.apply(data, operation) === data }.getOrDefault(false)
        }
}
