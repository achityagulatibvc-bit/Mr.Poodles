package com.mrpoodles.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Pure deterministic interleavings and synthetic source fixtures; no provider or persistence claims. */
class Phase6PlanningTest {
    private val start = LocalDate.of(2026, 10, 4)
    private val profile = Profile(restrictions = emptyList())
    private val data = AppData(profile = profile, revision = 4)
    private val foods = listOf("apple", "pear", "banana", "orange", "mango", "grape", "peach", "plum",
        "kiwi", "melon", "papaya", "apricot", "cherry", "strawberry", "blueberry", "raspberry",
        "blackberry", "pineapple", "pomegranate", "guava", "fig")

    private fun sourced(index: Int = 0): Recipe {
        val food = foods[index]
        val text = """
            Serves: 4
            Prep: 5 minutes
            Ingredients
            400 g $food
            200 g cucumber
            Method
            Wash and slice the $food and cucumber. Combine in a clean bowl and serve straight away.
        """.trimIndent()
        val source = RetrievedSource("source-$index", "https://example.org/synthetic-$index", "$food salad",
            "2026-10-04T08:00:00Z", excerpt = text, kind = "recipe")
        val origin = ResultOrigin("request-$index", data.revision,
            retrieval = RetrievalSnapshot("snapshot-$index", "request-$index", listOf(source)))
        return requireNotNull(PublishedRecipeParser.parse(source, origin)).copy(id = "recipe-$index")
    }

    private fun meal(recipe: Recipe = sourced(), date: String = start.toString(), slot: String = "Lunch", portions: Double = 1.0,
        id: String = "meal-$date-$slot"): Meal = Meal(id, date, slot, recipe, profile.mode, data.revision, portions)

    /** Shaped ingredient fixtures exercise quantity arithmetic independently of source validation. */
    private fun quantities(vararg ingredients: NamedIngredient, servings: Double = 1.0): Recipe {
        val recipe = sourced()
        return recipe.copy(sourced = recipe.sourced!!.copy(ingredients = ingredients.toList(), servings = servings))
    }

    private fun fails(message: String, action: () -> Unit) {
        try { action(); fail("Expected rejection containing: $message") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message, true)) }
        catch (error: IllegalStateException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message, true)) }
    }

    @Test fun sourcedAndRescaledRecipesUseDoubleServingBasisRatherThanLegacyIntServings() {
        val original = sourced()
        val originalShopping = PlanningRules.shopping(listOf(meal(original, portions = 2.0)), emptyList())
        assertEquals(200.0, originalShopping.single { it.name == "apple" }.amount!!, 0.000001)
        val scaled = SourcedRecipeRules.scale(original, 0.5)
        assertEquals(0, scaled.servings)
        val scaledShopping = PlanningRules.shopping(listOf(meal(scaled, portions = 2.0)), emptyList())
        assertEquals(originalShopping, scaledShopping)
    }

    @Test fun legacyQuantitiesScaleByRecipeServingsAndPlannedPortions() {
        val recipe = Recipe("Old oats", listOf(Ingredient("oats", 400.0)), servings = 4)
        val row = PlanningRules.shopping(listOf(meal(recipe, portions = 0.5)), emptyList()).single()
        assertEquals("oats", row.name)
        assertEquals(50.0, row.amount!!, 0.0)
        assertTrue(row.note.contains("catalog"))
    }

    @Test fun gramsKilogramsAndLitresMillilitresMergeButVolumeDoesNotBecomeWeight() {
        val recipe = quantities(NamedIngredient("a", "Rice", 0.5, "kg"), NamedIngredient("b", "rice", 250.0, "grams"),
            NamedIngredient("c", "rice", 1.0, "cup"), NamedIngredient("d", "water", 1.0, "litre"),
            NamedIngredient("e", "water", 200.0, "ml"), NamedIngredient("f", "water", 5.0, "g"))
        val rows = PlanningRules.shopping(listOf(meal(recipe)), emptyList())
        assertEquals(4, rows.size)
        assertEquals(750.0, rows.single { it.name.equals("rice", true) && it.unit == "g" }.amount!!, 0.0)
        assertEquals(1.0, rows.single { it.unit == "cup" }.amount!!, 0.0)
        assertEquals(1200.0, rows.single { it.unit == "ml" }.amount!!, 0.0)
        assertEquals(5.0, rows.single { it.name == "water" && it.unit == "g" }.amount!!, 0.0)
    }

    @Test fun unknownAndKnownQuantitiesHaveSeparateStableKeysAndPantryCannotEraseUnknown() {
        val recipe = quantities(NamedIngredient("a", "salt", 10.0, "g"), NamedIngredient("b", "salt", null, "g"),
            NamedIngredient("c", "salt", 2.0, null))
        val pantry = listOf(PantryAmount("salt", "g", 100.0), PantryAmount("salt", "", 100.0))
        val rows = PlanningRules.shopping(listOf(meal(recipe)), pantry)
        assertEquals(3, rows.size)
        assertEquals(3, rows.map { it.key }.distinct().size)
        assertEquals(0.0, rows.single { it.unit == "g" && it.amount != null }.amount!!, 0.0)
        assertNull(rows.single { it.note.startsWith("Quantity unknown") }.amount)
        assertEquals(2.0, rows.single { it.unit == "" }.amount!!, 0.0)
    }

    @Test fun pantryMatchesOnlyExactIngredientIdentityAndCompatibleUnit() {
        val recipe = quantities(NamedIngredient("a", "red lentils", 600.0, "g"), NamedIngredient("b", "lentils", 4.0, "cup"))
        val pantry = listOf(PantryAmount(" RED  LENTILS ", "kg", 0.2), PantryAmount("lentils", "g", 10000.0),
            PantryAmount("red lentils", "cup", 100.0))
        val rows = PlanningRules.shopping(listOf(meal(recipe)), pantry)
        assertEquals(400.0, rows.single { it.name == "red lentils" }.amount!!, 0.0)
        assertEquals(4.0, rows.single { it.name == "lentils" }.amount!!, 0.0)
    }

    @Test fun freeTextPantryAndLegacyCatalogIdsAreNeverGuessedIntoDeductions() {
        val original = data.copy(profile = profile.copy(hostel = profile.hostel.copy(pantry = "lots of apple and cucumber")))
        val rows = PlanningRules.shopping(listOf(meal()), original.pantryAmounts)
        assertEquals(100.0, rows.single { it.name == "apple" }.amount!!, 0.0)
        val legacy = Recipe("Old apple", listOf(Ingredient("apple", 200.0)))
        assertEquals(200.0, PlanningRules.shopping(listOf(meal(legacy)), listOf(PantryAmount("apple", "g", 999.0))).single().amount!!, 0.0)
    }

    @Test fun duplicateOrInvalidPantrySnapshotsDoNotCreateInventedStock() {
        val recipe = quantities(NamedIngredient("a", "rice", 500.0, "g"))
        val rows = PlanningRules.shopping(listOf(meal(recipe)), listOf(PantryAmount("rice", "kg", 0.1), PantryAmount("rice", "g", 100.0)))
        assertEquals(500.0, rows.single().amount!!, 0.0)
        assertTrue(rows.single().note.contains("Duplicate"))
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { amount ->
            assertEquals(500.0, PlanningRules.shopping(listOf(meal(recipe)), listOf(PantryAmount("rice", "g", amount))).single().amount!!, 0.0)
        }
    }

    @Test fun preparationQualifiersStaySeparateAndRequireExactPantryIdentity() {
        val recipe = quantities(NamedIngredient("a", "rice", 100.0, "g", preparation = "cooked"),
            NamedIngredient("b", "rice", 100.0, "g", preparation = "dry"))
        val rows = PlanningRules.shopping(listOf(meal(recipe)), listOf(PantryAmount("rice", "g", 1000.0),
            PantryAmount("rice (cooked)", "g", 25.0)))
        assertEquals(2, rows.size)
        assertEquals(75.0, rows.single { it.name == "rice (cooked)" }.amount!!, 0.0)
        assertEquals(100.0, rows.single { it.name == "rice (dry)" }.amount!!, 0.0)
    }

    @Test fun invalidIngredientAmountsAndPortionsStayUnknown() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -2.0, 0.0).forEach { value ->
            val recipe = quantities(NamedIngredient("a", "rice", value, "g"))
            assertNull(PlanningRules.shopping(listOf(meal(recipe)), emptyList()).single().amount)
            assertTrue(PlanningRules.shopping(listOf(meal(portions = value)), emptyList()).all { it.amount == null })
        }
    }

    @Test fun boughtIdsSurviveOrderServingAndUnitSpellingChangesAndSerialization() {
        val first = quantities(NamedIngredient("a", "rice", 1.0, "kg"), NamedIngredient("b", "water", 1.0, "l"))
        val second = quantities(NamedIngredient("b", "water", 1000.0, "ml"), NamedIngredient("a", "RICE", 1000.0, "grams"))
        val rows = PlanningRules.shopping(listOf(meal(first)), emptyList())
        val other = PlanningRules.shopping(listOf(meal(second, portions = 2.0)), emptyList())
        assertEquals(rows.map { it.key }.toSet(), other.map { it.key }.toSet())
        val checked = PlanningRules.setShoppingBought(data, rows.first().key, true)
        assertEquals(checked, PlanningRules.setShoppingBought(checked, rows.first().key, true))
        val restored = json.decodeFromString<AppData>(json.encodeToString(AppData.serializer(), checked))
        assertTrue(other.any { it.key in restored.boughtShopping })
        assertTrue(PlanningRules.setShoppingBought(restored, rows.first().key, false).boughtShopping.isEmpty())
    }

    @Test fun pantryEditsReplaceCanonicalSnapshotWithoutDoubleCountingAndZeroRemoves() {
        val added = PlanningRules.setPantryAmount(data, "rice", "kg", 1.0)
        assertEquals(listOf(PantryAmount("rice", "g", 1000.0)), added.pantryAmounts)
        val changed = PlanningRules.setPantryAmount(added, "rice", "grams", 400.0)
        assertEquals(listOf(PantryAmount("rice", "g", 400.0)), changed.pantryAmounts)
        assertTrue(PlanningRules.setPantryAmount(changed, "rice", "kg", 0.0).pantryAmounts.isEmpty())
        fails("exact") { PlanningRules.setPantryAmount(data, "rice", "", 1.0) }
        fails("finite") { PlanningRules.setPantryAmount(data, "rice", "g", Double.NaN) }
    }

    @Test fun sevenDaysRequireTwentyOneDistinctSourcedRecipesAndReportActionableShortfall() {
        val recipes = (0..2).map(::sourced)
        val needs = PlanningRules.needs(data, recipes, 7)
        assertEquals(21, needs.required)
        assertEquals(3, needs.available)
        assertEquals(18, needs.missing)
        assertEquals(18, needs.queries.size)
        assertTrue(needs.queries.all { it.contains("published") && it.contains("apple salad") })
        try { PlanningRules.preview(data, recipes, start, 7); fail("Three recipes must not be permuted into a week") }
        catch (error: MissingPlanRecipes) { assertEquals(needs, error.needs) }
        assertTrue(data.meals.isEmpty())
    }

    @Test fun servingVariantsCopiesAndNewRetrievalIdsDoNotFakeVariety() {
        val recipe = sourced()
        val scaled = SourcedRecipeRules.scale(recipe, 2.0)
        assertEquals(1, PlanningRules.suitableRecipes(data, listOf(recipe, scaled, recipe.copy(id = "another"))).size)
        val source = recipe.origin!!.retrieval!!.sources.single().copy(id = "different-source-id", url = "https://example.org/copy")
        val origin = ResultOrigin("another-request", data.revision, retrieval = RetrievalSnapshot("different", "another-request", listOf(source)))
        val copied = requireNotNull(PublishedRecipeParser.parse(source, origin))
        assertEquals(1, PlanningRules.suitableRecipes(data, listOf(recipe, copied)).size)
    }

    @Test fun weekPreviewHasDistinctMealsArbitraryDatesAndDoesNotMutateUntilApply() {
        val recipes = foods.indices.map(::sourced)
        assertTrue(recipes.all { SourcedRecipeRules.validate(it, profile).isEmpty() })
        val historicalStart = LocalDate.of(2024, 2, 27)
        val preview = PlanningRules.preview(data, recipes, historicalStart, 7)
        assertTrue(data.meals.isEmpty())
        assertEquals(21, preview.meals.size)
        assertEquals(21, preview.meals.map { it.recipe.id }.distinct().size)
        assertTrue("2024-02-29" in preview.dates)
        preview.dates.forEach { date -> assertEquals(setOf("Breakfast", "Lunch", "Dinner"), preview.meals.filter { it.date == date }.map { it.slot }.toSet()) }
        val restored = json.decodeFromString<PlanPreview>(json.encodeToString(PlanPreview.serializer(), preview))
        assertEquals(preview, restored)
        assertEquals(preview.meals, PlanningRules.apply(data, restored).meals)
    }

    @Test fun staleProfilePreviewIsRejectedAndCurrentRestrictionsFilterCandidates() {
        val recipes = (0..2).map(::sourced)
        val preview = PlanningRules.preview(data, recipes, start, 1)
        val updated = data.copy(revision = 5, profile = profile.copy(restrictions = listOf(Restriction("apple", "Allergy"))))
        fails("profile changed") { PlanningRules.apply(updated, preview) }
        assertEquals(2, PlanningRules.suitableRecipes(updated, recipes).size)
        // Also revalidate if an erroneous caller changes profile contents without incrementing revision.
        fails("still fit") { PlanningRules.apply(updated.copy(revision = data.revision), preview) }
    }

    @Test fun targetDateAdditionDuringRetrievalRejectsOriginalSnapshotEvenWhenInitiallyEmpty() {
        val captured = data
        val edited = data.copy(meals = listOf(meal(slot = "Snack")))
        val preview = PlanningRules.preview(captured, (0..2).map(::sourced), start, 1)
        fails("meals changed") { PlanningRules.apply(edited, preview) }
        assertEquals("Snack", edited.meals.single().slot)
    }

    @Test fun targetDateRemovalMovePortionAndReplacementEachRejectStalePreview() {
        val original = data.copy(meals = listOf(meal()))
        val preview = PlanningRules.preview(original, (0..2).map(::sourced), start, 1)
        val edits = listOf(
            PlanningRules.removeMeal(original, original.meals.single().id),
            PlanningRules.moveMeal(original, original.meals.single().id, start.plusDays(9).toString(), "Lunch", 1.0),
            PlanningRules.moveMeal(original, original.meals.single().id, start.toString(), "Lunch", 2.0),
            PlanningRules.planRecipe(original, sourced(3), start.toString(), "Lunch")
        )
        edits.forEach { edited -> fails("meals changed") { PlanningRules.apply(edited, preview) } }
    }

    @Test fun unrelatedConcurrentEditsArePreservedIncludingDiaryPantryBoughtAndOtherDates() {
        val original = data.copy(meals = listOf(meal()))
        val preview = PlanningRules.preview(original, (0..2).map(::sourced), start, 1)
        val outside = meal(date = start.plusDays(20).toString(), portions = 2.0)
        val key = PlanningRules.shopping(listOf(outside), emptyList()).first().key
        val edited = original.copy(meals = original.meals + outside,
            intake = listOf(Intake(date = start.toString(), name = "Food actually eaten", kcal = null)),
            pantryAmounts = listOf(PantryAmount("apple", "g", 50.0)), boughtShopping = listOf(key), recipes = listOf(sourced(4)))
        val applied = PlanningRules.apply(edited, preview)
        assertEquals(listOf(outside) + preview.meals, applied.meals)
        assertEquals(edited.copy(meals = applied.meals), applied)
        fails("meals changed") { PlanningRules.apply(applied, preview) }
    }

    @Test fun snapshotComparisonIgnoresOnlyListOrderAndRejectsDuplicateIds() {
        val original = data.copy(meals = listOf(meal(slot = "Lunch"), meal(slot = "Snack")))
        val preview = PlanningRules.preview(original, (0..2).map(::sourced), start, 1)
        assertEquals(3, PlanningRules.apply(original.copy(meals = original.meals.reversed()), preview).meals.size)
        fails("meals changed") { PlanningRules.apply(original.copy(meals = original.meals + original.meals.first()), preview) }
        fails("duplicate") { PlanningRules.apply(original, preview.copy(meals = preview.meals.map { it.copy(id = "same") })) }
    }

    @Test fun individualReplacementRetainsIdentityPortionsAndDiaryAndMoveNeverErasesOccupiedSlot() {
        val lunch = meal(portions = 2.5)
        val dinner = meal(sourced(1), slot = "Dinner")
        val original = data.copy(meals = listOf(lunch, dinner), intake = listOf(Intake(date = start.toString(), name = "Eaten", kcal = 120.0, mealId = lunch.id)))
        val replaced = PlanningRules.planRecipe(original, sourced(2), lunch.date, lunch.slot)
        val current = replaced.meals.single { it.id == lunch.id }
        assertEquals(2.5, current.portions, 0.0)
        assertEquals("recipe-2", current.recipe.id)
        assertEquals(original.intake, replaced.intake)
        assertTrue(dinner in replaced.meals)
        fails("occupied") { PlanningRules.moveMeal(replaced, lunch.id, lunch.date, dinner.slot, 1.0) }
        val moved = PlanningRules.moveMeal(replaced, lunch.id, "2025-01-01", "Snack", 0.5)
        assertEquals(0.5, moved.meals.single { it.id == lunch.id }.portions, 0.0)
        assertEquals(listOf(dinner), PlanningRules.removeMeal(moved, lunch.id).meals)
        assertEquals(original.intake, PlanningRules.removeMeal(moved, lunch.id).intake)
    }

    @Test fun malformedDatesPortionsAndAmbiguousHistoricalSlotsAreRejected() {
        listOf("2026-02-29", "2026-13-01", "26-01-01", "2026-1-1", "0000-01-01").forEach { assertNull(PlanningRules.parseDate(it)) }
        assertNotNull(PlanningRules.parseDate("2024-02-29"))
        val original = data.copy(meals = listOf(meal()))
        listOf(Double.NaN, 0.0, -1.0, 20.01).forEach { amount ->
            fails("servings") { PlanningRules.moveMeal(original, original.meals.single().id, start.toString(), "Lunch", amount) }
        }
        fails("date") { PlanningRules.planRecipe(data, sourced(), "2026-02-30", "Lunch") }
        fails("Choose Breakfast") { PlanningRules.planRecipe(data, sourced(), start.toString(), "Midnight") }
        val duplicate = original.copy(meals = original.meals + meal(id = "second"))
        fails("Several") { PlanningRules.planRecipe(duplicate, sourced(1), start.toString(), "Lunch") }
    }

    @Test fun movingStaleMealsDoesNotPretendTheyWereRevalidated() {
        val old = meal().copy(revision = 1)
        val moved = PlanningRules.moveMeal(data.copy(meals = listOf(old)), old.id, "2026-11-01", "Lunch", 2.0)
        assertEquals(1, moved.meals.single().revision)
    }
}
