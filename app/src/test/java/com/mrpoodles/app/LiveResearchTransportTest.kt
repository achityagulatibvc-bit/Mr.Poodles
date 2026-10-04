package com.mrpoodles.app

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in: two synthetic requests through the configured production Android transport. */
class LiveResearchTransportTest {
    @Test fun rejectedSourceDoesNotBlockARealRecipeOnTheSameClient() = runBlocking {
        assumeTrue("Set POODLES_LIVE_TRANSPORT=1 to authorize the bounded production check.",
            System.getenv("POODLES_LIVE_TRANSPORT") == "1")
        val model = CloudModel()
        try {
            model.research(ResearchRequest(UUID.randomUUID().toString(), 0, "recipe", "Synthetic unsupported source",
                url = "https://unapproved.example.org/recipe"))
            fail("Unsupported source must be rejected before retrieval")
        } catch (error: IOException) {
            assertFalse("A rejected source is not a global quota", error is ServiceLimitException)
            assertTrue(error.message.orEmpty().contains("isn't supported"))
        }
        val profile = Profile(mode = "Home", sourceLookupConsent = true, externalModelConsent = false,
            restrictions = listOf(Restriction("Lactose", "Intolerance"), Restriction("Soy", "Allergy")),
            home = Environment(listOf("Fridge", "Stove", "Oven", "Blender", "Kettle"), maxMinutes = 90))
        val reply = SourcedFoodService(model).recipe(RequestIdentity(UUID.randomUUID().toString(), 0),
            profile, FeatureConversation(), "Garden tomato salad")
        val recipe = requireNotNull(reply.recipe) { "The real retrieved recipe did not pass client validation: ${reply.message}" }
        assertEquals("Garden tomato salad", recipe.title)
        assertTrue(SourcedRecipeRules.validate(recipe, profile).isEmpty())
        assertTrue(requireNotNull(recipe.sourced).ingredients.isNotEmpty())
    }
}
