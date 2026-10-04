package com.mrpoodles.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The app carries a limited, revocable access token, never the provider API key. */
open class CloudModel(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).followRedirects(false).build()
) {
    @Volatile private var currentCall: Call? = null
    private val cooldowns = java.util.concurrent.ConcurrentHashMap<String, ServiceLimitException>()
    open fun cancel() { currentCall?.cancel() }

    /** Kept separate from legacy generation so old backends never masquerade as retrieved evidence. */
    open suspend fun research(input: ResearchRequest): ResearchResponse = withContext(Dispatchers.IO) {
        check(BuildConfig.BACKEND_URL.startsWith("https://") && BuildConfig.APP_ACCESS_TOKEN.length >= 32) {
            "Poodles' source lookup isn't ready in this version. Please ask for an updated copy."
        }
        val bucket = "research_${input.task}"
        cooldowns.entries.removeIf { it.value.retryAt <= System.currentTimeMillis() }
        (cooldowns["research_provider"] ?: cooldowns[bucket])?.let { throw it }
        val call = client.newCall(Request.Builder().url(BuildConfig.BACKEND_URL.trimEnd('/') + "/v2/research")
            .header("Authorization", "Bearer ${BuildConfig.APP_ACCESS_TOKEN}")
            .post(json.encodeToString(ResearchRequest.serializer(), input).toRequestBody("application/json".toMediaType())).build())
        currentCall = call
        try {
            call.await().use { response ->
                val errorCode = if (response.isSuccessful) null else runCatching {
                    json.parseToJsonElement(response.peekBody(8192).string()).jsonObject["error"]
                        ?.jsonObject?.get("code")?.jsonPrimitive?.content
                }.getOrNull()
                val sourceFailure = errorCode in setOf("source_incomplete", "source_unavailable", "source_not_approved", "invalid_source_url")
                // Older backends tagged unreadable pages as 503 with a provider cooldown.
                // Those are request-specific failures, not a reason to block every source lookup.
                if (response.code == 429 || response.code == 503 && response.header("Retry-After") != null && !sourceFailure) {
                    val limit = ServiceLimits.parse(response.header("X-Poodles-Limit"), response.header("Retry-After"))
                    cooldowns[if (limit.scope == "research_provider") limit.scope else bucket] = limit
                    throw limit
                }
                if (!response.isSuccessful) throw ResearchLookupException(when {
                    errorCode in setOf("source_not_approved", "invalid_source_url") ->
                        "That source link isn't supported. Try searching by name instead. Your draft is kept."
                    sourceFailure -> "These pages didn't provide readable source details. Try a different published link or a more specific name. Your draft and previous result are kept."
                    response.code in setOf(401, 403) -> "Poodles' connection needs a small update. Ask the person who set up the app."
                    response.code in setOf(400, 413) -> "That source request couldn't be sent. Shorten the message or check your saved preference details. Your draft is kept."
                    response.code == 404 || errorCode in setOf("providers_not_configured", "source_configuration", "not_ready") ->
                        "Poodles' source lookup isn't ready yet. Your draft is kept."
                    else -> "The source service couldn't respond just now. Your draft and previous result are kept; please try again."
                })
                val body = response.body ?: throw IOException("The research reply was empty.")
                val buffer = okio.Buffer()
                body.source().use { source ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (source.read(buffer, 8192) == -1L) break
                        check(buffer.size <= 256000) { "The research reply was too large." }
                    }
                }
                val decoded = json.decodeFromString<ResearchResponse>(buffer.readUtf8())
                val result = if (input.task == "workout") {
                    // Invalid optional video metadata must not discard valid article evidence.
                    decoded.copy(video = null).validate(input)
                    val validVideo = runCatching { decoded.validate(input).video }.getOrNull()
                    decoded.copy(video = validVideo)
                } else decoded.validate(input)
                currentCoroutineContext().ensureActive()
                result
            }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is ServiceLimitException || error is ResearchLookupException) throw error
            throw IOException("The sources couldn't be checked. Your previous result is kept; please try again.")
        } finally { if (currentCall === call) currentCall = null }
    }

    open suspend fun generate(instructions: String, input: String, structured: Boolean, maxTokens: Int,
        task: String, history: List<Message>, status: (String) -> Unit, stream: (String) -> Unit): String = withContext(Dispatchers.IO) {
        check(BuildConfig.BACKEND_URL.startsWith("https://") && BuildConfig.APP_ACCESS_TOKEN.length >= 32) {
            "Poodles' connection isn't ready in this version. Please ask for an updated copy."
        }
        val bucket = ServiceLimits.bucket(task)
        cooldowns.entries.removeIf { it.value.retryAt <= System.currentTimeMillis() }
        (cooldowns["all"] ?: cooldowns[bucket])?.let { throw it }
        val streaming = task == "chat"
        val body = buildJsonObject {
            put("task", if (structured && task == "text") "structured" else task)
            put("instructions", instructions)
            put("input", input)
            put("maxTokens", maxTokens)
            put("stream", streaming)
            putJsonArray("history") { history.takeLast(10).forEach { message -> addJsonObject {
                put("role", if (message.role == "You") "user" else "assistant")
                put("content", message.text.take(1800))
            } } }
        }
        val request = Request.Builder().url(BuildConfig.BACKEND_URL.trimEnd('/') + "/v1/help")
            .header("User-Agent", "MrPoodles/0.3 Android")
            .header("Authorization", "Bearer ${BuildConfig.APP_ACCESS_TOKEN}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        status(if (streaming) "Poodles is listening…" else "Putting a little thought into it…")
        val call = client.newCall(request)
        currentCall = call
        try {
            call.await().use { response ->
                if (response.code == 429 || response.code == 503 && response.header("Retry-After") != null) {
                    val limit = ServiceLimits.parse(response.header("X-Poodles-Limit"), response.header("Retry-After"))
                    cooldowns[when { limit.scope == "provider_daily" -> "all"; limit.scope == "provider_minute" || limit.scope.startsWith("research_") -> bucket; else -> limit.scope.substringBefore('_') }] = limit
                    throw limit
                }
                if (!response.isSuccessful) throw IOException(friendlyHttpError(response.code))
                val content = response.body ?: throw IOException("The reply didn't arrive. Please try again.")
                if (!streaming) {
                    val payload = json.parseToJsonElement(content.string()).jsonObject
                    val text = payload["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                    check(text.isNotBlank()) { "The reply was empty. Please try again." }
                    if (structured) cleanJsonReply(text) else text
                } else {
                    val reply = StringBuilder()
                    var lastUpdate = 0L
                    var finished = false
                    content.source().use { source ->
                        while (!source.exhausted()) {
                            currentCoroutineContext().ensureActive()
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") { finished = true; break }
                            if (data.isBlank()) continue
                            val event = json.parseToJsonElement(data).jsonObject
                            if (event["error"] != null) throw IOException("The connection paused before the reply finished. Please try again.")
                            val choice = event["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                            if (choice["finish_reason"]?.jsonPrimitive?.content in listOf("error", "length")) throw IOException("The reply was interrupted. Please try again.")
                            choice["delta"]?.jsonObject?.get("content")?.let { token ->
                                if (token !is JsonNull) reply.append(token.jsonPrimitive.content)
                            }
                            check(reply.length <= 16000) { "That reply was too long. Please try a shorter question." }
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 70) { stream(reply.toString()); lastUpdate = now }
                        }
                    }
                    check(finished && reply.isNotBlank()) { "The connection ended before the reply finished. Please try again." }
                    stream(reply.toString())
                    reply.toString()
                }
            }
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            if (failure is ServiceLimitException) throw failure
            throw IOException(if (failure.message in friendlyErrors) failure.message else "Poodles couldn't connect just now. Check your connection and try again.")
        } finally { if (currentCall === call) currentCall = null }
    }
}

private class ResearchLookupException(message: String) : IOException(message)

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() } else response.close()
        }
    })
}

private val friendlyErrors = setOf(
    "Poodles needs a little pause. Please try again later.",
    "Poodles' connection needs a small update. Ask the person who set up the app.",
    "Poodles couldn't connect just now. Please try again.",
    "That request was too large. Try a shorter message or label."
)
fun friendlyHttpError(code: Int): String = when (code) {
    429 -> "Poodles needs a little pause. Please try again later."
    401, 403 -> "Poodles' connection needs a small update. Ask the person who set up the app."
    400, 413 -> "That request was too large. Try a shorter message or label."
    else -> "Poodles couldn't connect just now. Please try again."
}
fun cleanJsonReply(text: String): String {
    val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val parsed = json.parseToJsonElement(trimmed)
    require(parsed is JsonObject) { "Poodles' draft wasn't complete. Please try again." }
    return trimmed
}
