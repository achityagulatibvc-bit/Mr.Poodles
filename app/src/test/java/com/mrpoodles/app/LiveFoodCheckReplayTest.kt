package com.mrpoodles.app

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Replays public manufacturer evidence; no live network call or private user data. */
class LiveFoodCheckReplayTest {
    @Test fun capturedManufacturerPagesReturnAnAssessmentWithoutInventingACompleteLabel() = runBlocking {
        val path = System.getenv("POODLES_LIVE_FOOD_CHECK_CAPTURE")
        assumeTrue("Optional manufacturer capture absent; not a live food-check acceptance pass.", !path.isNullOrBlank())
        val capture = json.parseToJsonElement(File(requireNotNull(path)).readText()).jsonObject
        val response = json.decodeFromString<ResearchResponse>(capture.getValue("response").toString())
        response.validate(ResearchRequest(response.requestId, response.profileRevision, "food_check", "MAGGI"))
        assertEquals("retrieval_only", response.provider)
        var calls = 0
        val model = object : CloudModel() {
            override suspend fun research(input: ResearchRequest): ResearchResponse {
                calls++
                assertFalse(input.allowExternalModel)
                return response.copy(requestId = input.requestId, profileRevision = input.profileRevision,
                    snapshot = response.snapshot.copy(requestId = input.requestId)).validate(input)
            }
        }
        val profile = Profile(sourceLookupConsent = true,
            restrictions = listOf(Restriction("Soy", "Allergy"), Restriction("Lactose", "Intolerance")))
        val reply = SourcedFoodService(model).check(RequestIdentity("manufacturer-replay", 3), profile,
            FeatureConversation(brand = "MAGGI", variant = "Masala", country = "India"), "MAGGI 2-Minute Masala Noodles")
        val assessment = requireNotNull(reply.assessment)
        assertEquals(1, calls)
        assertEquals(AssessmentOutcome.NEED_MORE_INFORMATION, assessment.outcome)
        assertTrue(assessment.uncertainties.isNotEmpty())
        assertTrue(reply.message.contains("manufacturer"))
    }
}
