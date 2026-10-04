package com.mrpoodles.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic text: mirrors extraction structure, never copies a complete published recipe. */
class Phase7RecipeCoverageTest {
    private val request = RequestIdentity("recipe-coverage", 2)
    private val profile = Profile(mode = "Home", sourceLookupConsent = true,
        restrictions = listOf(Restriction("Lactose", "Intolerance"), Restriction("Soy", "Allergy")),
        home = Environment(equipment = listOf("Fridge", "Stove", "Oven", "Blender", "Kettle"), maxMinutes = 90))

    private fun excerpt() = """
        # Synthetic tomato salad
        Serves 6 for a starter or lunch, or 8-10 with other dishes Easy
        Prep: 20 mins plus 2-3 hrs chilling, no cook

        ## Other ideas
        - 70g sugar mentioned in an earlier suggestion

        This separate introduction is outside the recipe list.

        - 1.25kg-1.5kg ripe tomatoes
         - as many colours, shapes, sizes and flavours as you can find
        - 40g soft herbs
         - we used parsley and mint
        - 5-6 shallots
         diced as finely as you can

        ### For the dressing
        - 2 tbsp olive oil
        - 2 tbsp vinegar
        - 1 tbsp honey

        Nutrition: per serving (6)
        - kcal 123
        low
        - fat 7g

        ## Method
        - ### step 1
        Cut the tomatoes into pieces and combine the herbs with shallots.
        - ### step 2
        Put the vegetables in a bowl. Add ground black pepper. Chill for a few hrs.
        - ### step 3
        Stir the dressing together and pour it over the vegetables.

        ###### Synthetic Author
        This biography is outside the recipe.
    """.trimIndent()

    private fun source(text: String = excerpt()) = RetrievedSource("source", "https://example.org/tomato-salad",
        "Synthetic tomato salad", "2026-10-04T09:00:00Z", author = "Synthetic Author", excerpt = text, kind = "recipe")
    private fun parse(text: String = excerpt()): Recipe {
        val source = source(text)
        val origin = ResultOrigin(request.id, request.profileRevision, retrieval = RetrievalSnapshot("snapshot", request.id, listOf(source)))
        return requireNotNull(PublishedRecipeParser.parse(source, origin)) { "Synthetic published layout was not parsed" }
    }
    private fun rejects(text: String) {
        val source = source(text)
        val origin = ResultOrigin(request.id, request.profileRevision, retrieval = RetrievalSnapshot("snapshot", request.id, listOf(source)))
        assertNull(PublishedRecipeParser.parse(source, origin))
    }
    private class FakeResearch(private val source: RetrievedSource) : CloudModel() {
        var calls = 0
        override suspend fun research(input: ResearchRequest): ResearchResponse {
            calls++
            return ResearchResponse(2, input.requestId, input.profileRevision,
                RetrievalSnapshot("network-$calls", input.requestId, listOf(source)),
                answer = ResearchAnswer("Let's review the source together.", emptyList(), emptyList()), provider = "fixture")
        }
    }

    @Test fun rangeAndAlternativeYieldAreParsedWithExactQuotesAndNoNutritionGuess() {
        val recipe = parse()
        val data = requireNotNull(recipe.sourced)
        assertEquals(6, data.ingredients.size)
        assertEquals(1.25, data.ingredients[0].amount!!, 0.0)
        assertEquals(1.5, data.ingredients[0].amountMax!!, 0.0)
        assertEquals("kg", data.ingredients[0].unit)
        assertEquals(5.0, data.ingredients[2].amount!!, 0.0)
        assertEquals(6.0, data.ingredients[2].amountMax!!, 0.0)
        assertNull(data.ingredients[2].unit)
        assertEquals(6.0, data.servings, 0.0)
        assertEquals("Serves 6 for a starter or lunch, or 8-10 with other dishes Easy", data.originalServingDescription)
        assertTrue(requireNotNull(data.servingBasisNote).contains("first explicitly stated 6.0-serving basis"))
        assertEquals(120, data.waitingMinutes)
        assertEquals(180, data.waitingMinutesMax)
        assertEquals(20, data.activeMinutes)
        assertNull(data.nutrition.kcal)
        assertEquals(3, data.instructions.size)
        val refs = data.ingredients.flatMap { it.evidence } + data.instructionEvidence + data.metadataEvidence
        assertTrue(FoodEvidence.valid(data.origin, refs))
        assertTrue(data.metadataEvidence.any { it.excerpt == "### For the dressing" })
        assertTrue(data.ingredients[0].evidence.any { it.excerpt.trim() == "- as many colours, shapes, sizes and flavours as you can find" })
        assertFalse(refs.any { it.excerpt.contains("70g") || it.excerpt.contains("kcal 123") })
        assertTrue(SourcedRecipeRules.validate(recipe, profile).joinToString(), SourcedRecipeRules.validate(recipe, profile).isEmpty())
    }

    @Test fun serviceReturnsCompatibleRecipeAndScalesBothBoundsWithoutAnotherLookup() = runBlocking {
        val model = FakeResearch(source())
        val service = SourcedFoodService(model)
        val first = requireNotNull(service.recipe(request, profile, FeatureConversation(), "tomato salad").recipe)
        val state = FeatureConversation(subject = "tomato salad", result = FeatureResult(requireNotNull(first.origin), recipe = first))
        val second = requireNotNull(service.recipe(request.copy(id = "two-servings"), profile, state, "Make two servings").recipe)
        val data = requireNotNull(second.sourced)
        assertEquals(1, model.calls)
        assertNotEquals(first.id, second.id)
        assertEquals(2.0, data.servings, 0.0)
        assertEquals(1.25 / 3, data.ingredients[0].amount!!, 0.0000001)
        assertEquals(0.5, data.ingredients[0].amountMax!!, 0.0000001)
        assertEquals(5.0 / 3, data.ingredients[2].amount!!, 0.0000001)
        assertEquals(2.0, data.ingredients[2].amountMax!!, 0.0000001)
        assertEquals(120, data.waitingMinutes)
        assertEquals(180, data.waitingMinutesMax)
        assertEquals(requireNotNull(first.sourced).originalServingDescription, data.originalServingDescription)
        assertEquals(6.0, requireNotNull(first.sourced).servings, 0.0)
        assertTrue(SourcedRecipeRules.validate(second, profile).joinToString(), SourcedRecipeRules.validate(second, profile).isEmpty())
    }

    @Test fun methodOnlyBlackPepperRemainsARestrictionConflict() = runBlocking {
        val spice = profile.copy(restrictions = profile.restrictions + Restriction("Spice", aliases = "black pepper"))
        val recipe = parse()
        assertFalse(requireNotNull(recipe.sourced).ingredients.any { it.name.contains("pepper") })
        assertTrue(SourcedRecipeRules.validate(recipe, spice).any { it.contains("Spice") && it.contains("black pepper") })
        val reply = SourcedFoodService(FakeResearch(source())).recipe(request, spice, FeatureConversation(), "tomato salad")
        assertNull(reply.recipe)
        assertTrue(reply.message.contains("black pepper"))
    }

    @Test fun alteredUpperBoundsDescriptionsAndTimingFailEvidenceValidation() {
        val recipe = parse()
        val data = requireNotNull(recipe.sourced)
        val first = data.ingredients.first()
        for (upper in listOf<Double?>(null, 1.4, 2.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val altered = recipe.copy(sourced = data.copy(ingredients = listOf(first.copy(amountMax = upper)) + data.ingredients.drop(1)))
            assertTrue(SourcedRecipeRules.validate(altered, profile).any { it.contains("range") })
        }
        assertTrue(SourcedRecipeRules.validate(recipe.copy(sourced = data.copy(waitingMinutesMax = null)), profile).any { it.contains("timing") })
        assertTrue(SourcedRecipeRules.validate(recipe.copy(sourced = data.copy(waitingMinutesMax = 240)), profile).any { it.contains("timing") })
        assertTrue(SourcedRecipeRules.validate(recipe.copy(sourced = data.copy(originalServingDescription = "Serves 10")), profile).any { it.contains("serving description") })
        assertTrue(SourcedRecipeRules.validate(recipe.copy(sourced = data.copy(servingBasisNote = "Ten servings selected")), profile).any { it.contains("serving description") })
    }

    @Test fun primaryServingRangesMixedUnitsAndReversedRangesAreNotGuessed() {
        rejects(excerpt().replace("Serves 6 for a starter or lunch, or 8-10 with other dishes Easy", "Serves 6-8 Easy"))
        rejects(excerpt().replace("1.25kg-1.5kg", "1.5kg-1.25kg"))
        rejects(excerpt().replace("1.25kg-1.5kg", "900g-1.5kg"))
        rejects(excerpt().replace("5-6 shallots", "5-unknown shallots"))
        rejects(excerpt().replace("5-6 shallots", "5 to 6 shallots"))
        rejects(excerpt().replace("5-6 shallots", "1 x 6 shallots"))
        val value = parse(excerpt().replace("1.25kg-1.5kg", "1.25-1.5 kg")).sourced!!.ingredients.first()
        assertEquals(1.5, value.amountMax!!, 0.0)
        assertEquals("kg", value.unit)
    }

    @Test fun unsupportedSubsectionOrIngredientCannotHideBehindDressingOnlySuffix() {
        rejects(excerpt().replace("### For the dressing", "### Other tips"))
        rejects(excerpt().replace("- 5-6 shallots", "- unknown required ingredient"))
        rejects(excerpt().replace("- 5-6 shallots", "unreadable required ingredient"))
        rejects(excerpt().replace("- 40g soft herbs", "- 40g soft herbs\n- unlisted powder"))
        val unresolved = parse(excerpt().replace("as many colours, shapes, sizes and flavours as you can find", "we used unknown flavourings"))
        assertTrue(SourcedRecipeRules.validate(unresolved, profile).any { it.contains("clarification") })
    }

    @Test fun vagueWaitingRemainsUnknownWhileExplicitLabeledRangesRemainRanges() {
        val vague = parse(excerpt().replace("plus 2-3 hrs chilling", "plus a few hrs chilling"))
        assertNull(vague.sourced!!.waitingMinutes)
        assertNull(vague.sourced!!.waitingMinutesMax)
        assertTrue(SourcedRecipeRules.validate(vague, profile).any { it.contains("chilling or waiting time") })
        val explicit = parse(excerpt().replace("plus 2-3 hrs chilling, no cook", "no cook\nChill: 120-180 minutes"))
        assertEquals(120, explicit.sourced!!.waitingMinutes)
        assertEquals(180, explicit.sourced!!.waitingMinutesMax)
        rejects(excerpt().replace("2-3 hrs", "3-2 hrs"))
    }

    @Test fun shoppingRangesStayUnknownAndSeparateFromExactTotalsWithNoPantryDeduction() {
        val recipe = parse()
        val meal = Meal(date = "2026-10-04", slot = "Lunch", recipe = recipe, mode = "Home", revision = 2, portions = 2.0)
        val shopping = PlanningRules.shopping(listOf(meal), listOf(PantryAmount("shallots (diced as finely as you can)", "piece", 1.0)))
        val tomatoes = shopping.single { it.name.startsWith("ripe tomatoes") }
        val shallots = shopping.single { it.name.startsWith("shallots") }
        assertNull(tomatoes.amount)
        assertNull(shallots.amount)
        assertTrue(tomatoes.note.contains("range") && tomatoes.note.contains("pantry not deducted"))
        val data = requireNotNull(recipe.sourced)
        val exactTomato = recipe.copy(sourced = data.copy(ingredients = listOf(data.ingredients.first().copy(amountMax = null))))
        val mixed = PlanningRules.shopping(listOf(meal, meal.copy(id = "exact", recipe = exactTomato)), emptyList())
            .filter { it.name.startsWith("ripe tomatoes") }
        assertEquals(2, mixed.size)
        assertEquals(1, mixed.count { it.amount == null })
        assertEquals(1.25 * 1000 / 3, mixed.single { it.amount != null }.amount!!, 0.00001)
    }

    @Test fun rangeSubstitutionRequiresBothOriginalBoundsAndSurvivesScaling() {
        val base = source(excerpt().replace("1.25kg-1.5kg ripe tomatoes", "1.25kg-1.5kg mascarpone")
            .replace("Cut the tomatoes", "Cut the mascarpone"))
        val guide = RetrievedSource("guide", "https://example.org/guide", "Synthetic tomato salad substitutions", "2026-10-04",
            kind = "recipe", excerpt = "# Synthetic tomato salad substitutions\nThis synthetic guidance concerns the cold salad mixture for a unit test.\nReplace mascarpone with coconut cream in equal amounts.")
        val origin = ResultOrigin(request.id, 2, retrieval = RetrievalSnapshot("snapshot", request.id, listOf(base, guide)))
        val original = requireNotNull(PublishedRecipeParser.parse(base, origin))
        val adapted = requireNotNull(SupportedSubstitutions.adapt(original, origin, profile, emptyList()))
        val substitution = requireNotNull(adapted.sourced?.adaptation).substitutions.single()
        assertTrue(SupportedSubstitutions.supports(substitution, origin))
        assertFalse(SupportedSubstitutions.supports(substitution.copy(replacement = substitution.replacement.copy(amountMax = null)), origin))
        assertTrue(SourcedRecipeRules.validate(SourcedRecipeRules.scale(adapted, 2.0), profile).isEmpty())
    }

    @Test fun newFieldsRoundTripAndHistoricalDefaultsStayAbsent() {
        val recipe = parse()
        val saved = json.decodeFromString(Recipe.serializer(), json.encodeToString(Recipe.serializer(), recipe))
        assertEquals(recipe, saved)
        assertTrue(SourcedRecipeRules.validate(saved, profile).isEmpty())
        val old = json.decodeFromString(NamedIngredient.serializer(), """{"key":"old","name":"rice","amount":20,"unit":"g"}""")
        assertNull(old.amountMax)
        val historical = SourcedRecipe(ResultOrigin("old", 1), listOf(old), listOf("Serve the rice."), 1.0)
        assertNull(historical.waitingMinutesMax)
        assertNull(historical.originalServingDescription)
        assertNull(historical.servingBasisNote)
    }
}
