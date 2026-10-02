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
open class CloudModel {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).followRedirects(false).build()
    @Volatile private var currentCall: Call? = null
    open fun cancel() { currentCall?.cancel() }

    open suspend fun generate(instructions: String, input: String, structured: Boolean, maxTokens: Int,
        task: String, history: List<Message>, image: String?, status: (String) -> Unit, stream: (String) -> Unit): String = withContext(Dispatchers.IO) {
        check(BuildConfig.BACKEND_URL.startsWith("https://") && BuildConfig.APP_ACCESS_TOKEN.length >= 32) {
            "Poodles' connection isn't ready in this version. Please ask for an updated copy."
        }
        val streaming = task == "chat"
        val body = buildJsonObject {
            put("task", if (structured) "structured" else task)
            put("instructions", instructions)
            put("input", input)
            put("maxTokens", maxTokens)
            put("stream", streaming)
            if (image != null) put("image", image)
            putJsonArray("history") { history.takeLast(10).forEach { message -> addJsonObject {
                put("role", if (message.role == "You") "user" else "assistant")
                put("content", message.text.take(1800))
            } } }
        }
        val request = Request.Builder().url(BuildConfig.BACKEND_URL.trimEnd('/') + "/v1/help")
            .header("User-Agent", "MrPoodles/0.2 Android")
            .header("Authorization", "Bearer ${BuildConfig.APP_ACCESS_TOKEN}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        status(if (streaming) "Poodles is listening…" else "Putting a little thought into it…")
        val call = client.newCall(request)
        currentCall = call
        try {
            call.await().use { response ->
                if (!response.isSuccessful) throw IOException(friendlyHttpError(response.code, task == "vision"))
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
            throw IOException(if (failure.message in friendlyErrors) failure.message else "Poodles couldn't connect just now. Check your connection and try again.")
        } finally { if (currentCall === call) currentCall = null }
    }
}

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
    "The photo reader needs a little pause. Try again later, or paste the label text.",
    "The photo couldn't be read right now. Try again later, or paste the label text.",
    "That request was too large. Try a shorter message or label."
)
fun friendlyHttpError(code: Int, photo: Boolean = false): String = when {
    photo && code == 429 -> "The photo reader needs a little pause. Try again later, or paste the label text."
    photo && code >= 500 -> "The photo couldn't be read right now. Try again later, or paste the label text."
    else -> when (code) {
    429 -> "Poodles needs a little pause. Please try again later."
    401, 403 -> "Poodles' connection needs a small update. Ask the person who set up the app."
    400, 413 -> "That request was too large. Try a shorter message or label."
    else -> "Poodles couldn't connect just now. Please try again."
    }
}
fun cleanJsonReply(text: String): String {
    val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val parsed = json.parseToJsonElement(trimmed)
    require(parsed is JsonObject) { "Poodles' draft wasn't complete. Please try again." }
    return trimmed
}
