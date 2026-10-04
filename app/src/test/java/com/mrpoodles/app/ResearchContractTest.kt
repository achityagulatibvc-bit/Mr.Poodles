package com.mrpoodles.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ResearchContractTest {
    private val request = ResearchRequest("request-1", 3, "recipe", "oats")
    private fun response() = json.decodeFromString<ResearchResponse>(javaClass.getResource("/research-v2-response.json")!!.readText())

    @Test fun retrievalOnlyBackendFixtureKeepsEvidenceWithoutRequiringModelClaims() {
        val result = json.decodeFromString<ResearchResponse>(
            javaClass.getResource("/research-v2-retrieval-only.json")!!.readText()
        ).validate(request)
        assertEquals("retrieval_only", result.provider)
        assertTrue(result.answer.claims.isEmpty())
        assertTrue(result.answer.uncertainties.isNotEmpty())
        assertEquals(response().snapshot.sources.single().excerpt, result.snapshot.sources.single().excerpt)
        assertEquals("unverified", result.snapshot.sources.single().completeness)
        assertThrows(IllegalArgumentException::class.java) {
            result.copy(snapshot = result.snapshot.copy(sources = emptyList())).validate(request)
        }
        assertThrows(IllegalArgumentException::class.java) { result.validate(request.copy(requestId = "old")) }
    }

    @Test fun sharedBackendFixtureDecodesWithSourceFreshnessAndUncertainty() {
        val result = response().validate(request)
        assertEquals("snapshot-1", result.snapshot.id)
        assertTrue(result.snapshot.sources.single().cached)
        assertEquals("unverified", result.snapshot.sources.single().completeness)
        assertEquals("recipe", result.snapshot.sources.single().kind)
        assertNull(result.video)
        assertTrue(result.answer.uncertainties.isNotEmpty())
    }
    @Test fun selectedQuoteBeyondNavigationIsStillCheckedAgainstTheRetrievedPage() {
        val result = response()
        val source = result.snapshot.sources.single()
        result.copy(snapshot = result.snapshot.copy(sources = listOf(source.copy(excerpt = "Navigation ".repeat(100) + source.excerpt)))).validate(request)
    }
    @Test fun oldRequestWrongProfileAndInventedEvidenceCannotEnterFeatureState() {
        val result = response()
        assertThrows(IllegalArgumentException::class.java) { result.validate(request.copy(requestId = "new")) }
        assertThrows(IllegalArgumentException::class.java) { result.validate(request.copy(profileRevision = 4)) }
        val original = result.answer.claims.single()
        val invented = original.copy(evidence = listOf(original.evidence.single().copy(snapshotId = "old")))
        assertThrows(IllegalArgumentException::class.java) { result.copy(answer = result.answer.copy(claims = listOf(invented))).validate(request) }
        assertThrows(IllegalArgumentException::class.java) {
            result.copy(answer = result.answer.copy(claims = listOf(original.copy(text = "zero calories")))).validate(request)
        }
    }
    @Test fun unsafeSourceLinksAndInventedVideoLinksAreRejected() {
        val result = response()
        for (url in listOf("http://example.org", "https://127.0.0.1", "https://localhost", "https://user:password@example.org")) {
            assertThrows(IllegalArgumentException::class.java) {
                result.copy(snapshot = result.snapshot.copy(sources = listOf(result.snapshot.sources.single().copy(url = url)))).validate(request)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            result.copy(video = ResearchVideo("https://youtube.com/invented", "Video", "Channel", "FOLLOW_ALONG", "Watched")).validate(request)
        }
    }
    @Test fun requestHasNoCompanionHistoryAndKeepsAllergyDifferentFromIntolerance() {
        val encoded = json.encodeToJsonElement(ResearchRequest.serializer(), request.copy(constraints = ResearchConstraints(
            restrictions = listOf(ResearchRestriction("Milk", "Allergy"), ResearchRestriction("Lactose", "Intolerance")))))
        assertNull(encoded.jsonObject["url"])
        assertNull(encoded.jsonObject["history"])
        assertNull(encoded.jsonObject["profile"])
        assertFalse(encoded.jsonObject.getValue("allowExternalModel").jsonPrimitive.boolean)
        assertNull(encoded.jsonObject.getValue("constraints").jsonObject["durationMinutes"])
        assertTrue(encoded.toString().contains("Allergy"))
        assertTrue(encoded.toString().contains("Intolerance"))
    }
}
