package com.mrpoodles.app

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ResearchTransportTest {
    private val request = ResearchRequest("request-1", 3, "recipe", "tiramisu")
    private val nextRecipe = request.copy(requestId = "request-2", subject = "oats")
    private val workout = request.copy(requestId = "request-3", task = "workout", subject = "beginner workout")
    private val readableSourceMessage = "These pages didn't provide readable source details. Try a different published link or a more specific name. Your draft and previous result are kept."
    private val unsupportedSourceMessage = "That source link isn't supported. Try searching by name instead. Your draft is kept."
    private val unavailableMessage = "The source service couldn't respond just now. Your draft and previous result are kept; please try again."
    private val invalidReplyMessage = "The sources couldn't be checked. Your previous result is kept; please try again."
    private val serverText = "PRIVATE_SERVER_DIAGNOSTIC_DO_NOT_DISPLAY"

    @Test fun successParsesSharedFixtureAndSendsConsistentRequestIdentity() = runBlocking {
        Transport(Reply(200)).use { transport ->
            val input = request.copy(subject = "oats")
            val result = transport.model.research(input)
            assertEquals(fixture(input), result)
            assertEquals("request-1", result.requestId)
            assertEquals(3, result.profileRevision)
            assertEquals("request-1", result.snapshot.requestId)
            assertTrue(result.snapshot.sources.single().cached)
            assertEquals("unverified", result.snapshot.sources.single().completeness)
            assertEquals(listOf(input), transport.requests.toList())
            assertEquals(1, transport.invocations.get())
        }
    }

    @Test fun unreadableSource422DoesNotCacheEvenWithLegacyCooldownHeaders() = runBlocking {
        for (code in listOf("source_incomplete", "source_unavailable")) {
            assertSourceFailureAllowsDifferentRecipe(422, code, readableSourceMessage)
        }
    }

    @Test fun legacy503SourceIncompleteDoesNotCacheProviderCooldown() = runBlocking {
        assertSourceFailureAllowsDifferentRecipe(503, "source_incomplete", readableSourceMessage)
    }

    @Test fun legacy503SourceUnavailableDoesNotCacheProviderCooldown() = runBlocking {
        assertSourceFailureAllowsDifferentRecipe(503, "source_unavailable", readableSourceMessage)
    }

    @Test fun unsupportedSourceErrorsAreNotServiceLimits() = runBlocking {
        for (status in listOf(422, 503)) {
            for (code in listOf("source_not_approved", "invalid_source_url")) {
                assertSourceFailureAllowsDifferentRecipe(status, code, unsupportedSourceMessage)
            }
        }
    }

    @Test fun global429BlocksAnotherRecipeAndWorkoutWithoutAnotherHttpCall() = runBlocking {
        assertGlobalCooldown(429, "free_limit")
    }

    @Test fun providerUnavailable503StillHonorsRetryAfterGlobally() = runBlocking {
        assertGlobalCooldown(503, "provider_unavailable")
    }

    @Test fun recipe429BlocksRecipesButAllowsWorkoutOnSameModel() = runBlocking {
        Transport(errorReply(429, "free_limit", "research_recipe"), Reply(200)).use { transport ->
            val before = System.currentTimeMillis()
            val first = failure(transport.model, request)
            val after = System.currentTimeMillis()
            assertLimit(first, "research_recipe", before, after)
            assertSame(first, failure(transport.model, nextRecipe))
            assertEquals(1, transport.invocations.get())

            // This fixture tests the research envelope, not workout-domain interpretation.
            assertEquals(fixture(workout), transport.model.research(workout))
            assertSame(first, failure(transport.model, nextRecipe.copy(requestId = "request-4")))
            assertEquals(listOf(request, workout), transport.requests.toList())
            assertEquals(2, transport.invocations.get())
        }
    }

    @Test fun malformedErrorBodiesAreSanitizedWithoutLeakingServerText() = runBlocking {
        for (body in listOf("<html>$serverText</html>", "{\"error\":", "{\"error\":\"$serverText\"}")) {
            Transport(Reply(500, body), Reply(200)).use { transport ->
                val error = failure(transport.model, request)
                assertFalse(error is ServiceLimitException)
                assertEquals(unavailableMessage, error.message)
                assertFalse(error.toString().contains(serverText))
                assertFalse(error.stackTraceToString().contains(serverText))
                assertEquals(fixture(nextRecipe), transport.model.research(nextRecipe))
                assertEquals(2, transport.invocations.get())
            }
        }
    }

    @Test fun configurationErrorsPreserveActionableMessage() = runBlocking {
        val replies = listOf("providers_not_configured", "source_configuration", "not_ready").map {
            errorReply(503, it).copy(retryAfter = null, scope = null)
        } + Reply(404, "<html>$serverText</html>")
        for (reply in replies) {
            Transport(reply, Reply(200)).use { transport ->
                val error = failure(transport.model, request)
                assertFalse(error is ServiceLimitException)
                assertEquals("Poodles' source lookup isn't ready yet. Your draft is kept.", error.message)
                assertFalse(error.toString().contains(serverText))
                assertEquals(fixture(nextRecipe), transport.model.research(nextRecipe))
                assertEquals(2, transport.invocations.get())
            }
        }
    }

    @Test fun malformedSuccessBodyIsSanitizedAndDoesNotCacheCooldown() = runBlocking {
        Transport(Reply(200, "<html>$serverText</html>"), Reply(200)).use { transport ->
            val error = failure(transport.model, request)
            assertFalse(error is ServiceLimitException)
            assertEquals(invalidReplyMessage, error.message)
            assertFalse(error.toString().contains(serverText))
            // Coroutine stack-trace recovery may wrap the already-sanitized IOException.
            assertFalse(error.stackTraceToString().contains(serverText))
            assertEquals(fixture(nextRecipe), transport.model.research(nextRecipe))
            assertEquals(2, transport.invocations.get())
        }
    }

    @Test fun successfulHttpReplyStillRejectsStaleRequestOrProfile() = runBlocking {
        val valid = fixture(request)
        val staleReplies = listOf(
            valid.copy(requestId = "old-request"),
            valid.copy(snapshot = valid.snapshot.copy(requestId = "old-request")),
            valid.copy(profileRevision = 2)
        )
        for (stale in staleReplies) {
            Transport(Reply(200, json.encodeToString(ResearchResponse.serializer(), stale)), Reply(200)).use { transport ->
                val error = failure(transport.model, request)
                assertFalse(error is ServiceLimitException)
                assertEquals(invalidReplyMessage, error.message)
                assertEquals(fixture(nextRecipe), transport.model.research(nextRecipe))
                assertEquals(2, transport.invocations.get())
            }
        }
    }

    private suspend fun assertSourceFailureAllowsDifferentRecipe(status: Int, code: String, message: String) {
        Transport(errorReply(status, code), Reply(200)).use { transport ->
            val error = failure(transport.model, request)
            assertFalse("Source-specific errors must not become service limits", error is ServiceLimitException)
            assertEquals(message, error.message)
            assertFalse(error.toString().contains(serverText))
            assertNotEquals(request.subject, nextRecipe.subject)
            assertEquals(fixture(nextRecipe), transport.model.research(nextRecipe))
            assertEquals(listOf(request, nextRecipe), transport.requests.toList())
            assertEquals(2, transport.invocations.get())
        }
    }

    private suspend fun assertGlobalCooldown(status: Int, code: String) {
        Transport(errorReply(status, code)).use { transport ->
            val before = System.currentTimeMillis()
            val first = failure(transport.model, request)
            val after = System.currentTimeMillis()
            assertLimit(first, "research_provider", before, after)
            assertSame(first, failure(transport.model, nextRecipe))
            assertSame(first, failure(transport.model, workout))
            assertEquals(listOf(request), transport.requests.toList())
            assertEquals(1, transport.invocations.get())
        }
    }

    private fun assertLimit(error: IOException, scope: String, before: Long, after: Long) {
        assertTrue(error is ServiceLimitException)
        val limit = error as ServiceLimitException
        assertEquals(scope, limit.scope)
        assertTrue("Retry-After must not be shortened", limit.retryAt >= before + 3_600_000L)
        assertTrue("Retry-After must be based on response handling time", limit.retryAt <= after + 3_600_000L)
        assertTrue(limit.message!!.contains("1 hour(s)"))
        assertTrue(limit.message!!.contains("draft"))
        assertFalse(limit.message!!.contains(serverText))
    }

    private suspend fun failure(model: CloudModel, input: ResearchRequest): IOException {
        try {
            model.research(input)
        } catch (error: IOException) {
            return error
        }
        throw AssertionError("Expected research to fail")
    }

    private fun fixture(input: ResearchRequest): ResearchResponse {
        val original = json.decodeFromString<ResearchResponse>(
            javaClass.getResource("/research-v2-response.json")!!.readText()
        )
        return original.copy(
            requestId = input.requestId,
            profileRevision = input.profileRevision,
            snapshot = original.snapshot.copy(requestId = input.requestId)
        )
    }

    private fun errorReply(status: Int, code: String, scope: String = "research_provider") = Reply(
        status, """{"error":{"code":"$code","message":"$serverText"}}""", "3600", scope
    )

    private data class Reply(val status: Int, val body: String? = null, val retryAfter: String? = null, val scope: String? = null)

    private inner class Transport(vararg replies: Reply) : Closeable {
        val invocations = AtomicInteger()
        val requests = CopyOnWriteArrayList<ResearchRequest>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val index = invocations.getAndIncrement()
            val reply = replies.getOrNull(index) ?: throw IOException("Unexpected test transport invocation")
            val outgoing = chain.request()
            assertEquals("POST", outgoing.method)
            assertEquals("/v2/research", outgoing.url.encodedPath)
            val buffer = Buffer()
            outgoing.body!!.writeTo(buffer)
            val input = json.decodeFromString<ResearchRequest>(buffer.readUtf8())
            requests.add(input)
            val body = reply.body ?: json.encodeToString(ResearchResponse.serializer(), fixture(input))
            // Application interceptors return before DNS, sockets, or any real request.
            Response.Builder().request(outgoing).protocol(Protocol.HTTP_1_1)
                .code(reply.status).message("Test response")
                .body(body.toResponseBody("application/json".toMediaType()))
                .apply {
                    reply.retryAfter?.let { header("Retry-After", it) }
                    reply.scope?.let { header("X-Poodles-Limit", it) }
                }.build()
        }.build()
        val model = CloudModel(client)

        override fun close() {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
