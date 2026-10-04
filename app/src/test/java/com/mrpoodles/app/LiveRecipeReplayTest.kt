package com.mrpoodles.app

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in offline replay only. No network call, credentials, capture copy or transcript logging. */
class LiveRecipeReplayTest {
    @Test fun capturedGardenTomatoRecipeAndTwoServingEditRetainPublishedBounds() = runBlocking {
        val path = System.getenv("POODLES_LIVE_RECIPE_CAPTURE")
        assumeTrue("Set POODLES_LIVE_RECIPE_CAPTURE to the public phase7-tomato capture.", !path.isNullOrBlank())
        val capture = json.parseToJsonElement(File(requireNotNull(path)).readText()).jsonObject
        val response = json.decodeFromString(ResearchResponse.serializer(), capture.getValue("response").toString())
        assertTrue(response.snapshot.sources.any { it.url.endsWith("/garden-tomato-salad") })
        var calls = 0
        val model = object : CloudModel() {
            override suspend fun research(input: ResearchRequest): ResearchResponse {
                calls++
                return response.copy(requestId = input.requestId, profileRevision = input.profileRevision,
                    snapshot = response.snapshot.copy(requestId = input.requestId))
            }
        }
        val profile = Profile(mode = "Home", sourceLookupConsent = true,
            restrictions = listOf(Restriction("Lactose", "Intolerance"), Restriction("Soy", "Allergy")),
            home = Environment(listOf("Fridge", "Stove", "Oven", "Blender", "Kettle"), maxMinutes = 90))
        val service = SourcedFoodService(model)
        val id = RequestIdentity("capture-replay", 3)
        val reply = service.recipe(id, profile, FeatureConversation(), "tomato salad")
        assertNotNull("Captured recipe should pass the lactose/soy profile: ${reply.message}", reply.recipe)
        val recipe = requireNotNull(reply.recipe)
        val original = requireNotNull(recipe.sourced)
        assertEquals("Garden tomato salad", recipe.title)
        assertEquals(1.25, original.ingredients.first().amount!!, 0.0)
        assertEquals(1.5, original.ingredients.first().amountMax!!, 0.0)
        assertEquals(120, original.waitingMinutes)
        assertEquals(180, original.waitingMinutesMax)
        assertTrue(SourcedRecipeRules.validate(recipe, profile).isEmpty())
        val state = FeatureConversation(subject = "tomato salad", result = FeatureResult(requireNotNull(recipe.origin), recipe = recipe))
        val edit = service.recipe(id.copy(id = "capture-two"), profile, state, "Make two servings")
        assertNotNull(edit.message, edit.recipe)
        val scaled = requireNotNull(edit.recipe)
        val data = requireNotNull(scaled.sourced)
        assertEquals(1, calls)
        assertEquals(2.0, data.servings, 0.0)
        assertEquals(1.25 / 3, data.ingredients.first().amount!!, 0.0000001)
        assertEquals(0.5, data.ingredients.first().amountMax!!, 0.0000001)
        assertEquals(5.0 / 3, data.ingredients[2].amount!!, 0.0000001)
        assertEquals(2.0, data.ingredients[2].amountMax!!, 0.0000001)
        assertEquals(original.originalServingDescription, data.originalServingDescription)
        assertTrue(SourcedRecipeRules.validate(scaled, profile).isEmpty())
        val spice = profile.copy(restrictions = profile.restrictions + Restriction("Spice", aliases = "black pepper"))
        assertTrue(SourcedRecipeRules.validate(scaled, spice).any { it.contains("black pepper") })
        val shopping = PlanningRules.shopping(listOf(Meal(date = "2026-10-04", slot = "Lunch", recipe = scaled,
            mode = "Home", revision = 3, portions = 2.0)), emptyList())
        assertNull(shopping.single { it.name.startsWith("ripe tomatoes") }.amount)
    }
}
