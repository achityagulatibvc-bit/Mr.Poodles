package com.mrpoodles.app

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Serializable enum class Feature { RECIPE, FOOD_CHECK, WORKOUT, FOOD_LOG, PLAN }
@Serializable data class RequestIdentity(
    val id: String = UUID.randomUUID().toString(), val profileRevision: Int,
    val inputRevision: Long = 0, val operationId: String? = null
)
@Serializable data class FeatureResult(
    val origin: ResultOrigin, val recipe: Recipe? = null, val workout: Workout? = null,
    val assessment: FoodAssessment? = null, val intakeIds: List<String> = emptyList()
)
@Serializable data class FeatureConversation(
    val draft: String = "", val messages: List<Message> = emptyList(), val inputRevision: Long = 0,
    val activeRequest: RequestIdentity? = null, val retryRequest: RequestIdentity? = null,
    val result: FeatureResult? = null, val error: String? = null,
    val selectedDate: String? = null, val scrollIndex: Int = 0, val scrollOffset: Int = 0,
    val subject: String = "", val exclusions: List<String> = emptyList(),
    val brand: String = "", val variant: String = "", val country: String = "",
    val completeLabel: Boolean = false, val foodKind: String = "product",
    val preparedLog: PreparedFoodLog? = null, val lastFoodLog: PreparedFoodLog? = null,
    val requestDate: String? = null
) {
    fun edit(text: String) = copy(draft = text, inputRevision = inputRevision + 1, activeRequest = null, retryRequest = null, error = null)
    fun begin(request: RequestIdentity): FeatureConversation {
        require(request.inputRevision == inputRevision)
        require(request.id != retryRequest?.id) { "A retry needs a fresh request ID and the same operation ID." }
        return copy(activeRequest = request, retryRequest = request, error = null)
    }
    fun accepts(request: RequestIdentity, profileRevision: Int) = activeRequest == request &&
        request.profileRevision == profileRevision && request.inputRevision == inputRevision
    fun complete(request: RequestIdentity, profileRevision: Int, value: FeatureResult): FeatureConversation {
        if (!accepts(request, profileRevision)) return this
        require(value.origin.requestId == request.id && value.origin.profileRevision == profileRevision)
        require(value.origin.retrieval?.requestId?.let { it == request.id } != false)
        return copy(result = value, activeRequest = null, retryRequest = null, error = null)
    }
    fun fail(request: RequestIdentity, profileRevision: Int, message: String) =
        if (accepts(request, profileRevision)) copy(activeRequest = null, error = message) else this
    fun recover() = if (activeRequest == null) this else copy(activeRequest = null,
        retryRequest = activeRequest, error = "This request was interrupted. Your draft and previous result are still here.")
}

/** Invalidations and the final disk move share this boundary. An old attempt cannot commit later. */
internal class RequestGate {
    private var current: RequestIdentity? = null
    @Synchronized fun begin(revision: Int, inputRevision: Long = 0, operationId: String? = null): RequestIdentity = RequestIdentity(profileRevision = revision, inputRevision = inputRevision, operationId = operationId).also { current = it }
    @Synchronized fun invalidate() { current = null }
    @Synchronized fun finish(request: RequestIdentity) { if (current == request) current = null }
    @Synchronized fun owns(request: RequestIdentity) = current == request
    @Synchronized fun <T> commit(request: RequestIdentity, block: () -> T): T {
        if (current != request) throw kotlinx.coroutines.CancellationException("Request superseded")
        return block()
    }
}
internal class RequestContext(val request: RequestIdentity) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestContext>
}
