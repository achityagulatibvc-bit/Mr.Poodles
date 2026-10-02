package com.mrpoodles.app

import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private val profile = Profile()
    private val banana = Food("banana", "Banana", 173944, "Bananas, raw", listOf("banana"), true, 89.0, 1.09, 22.84, 0.33, 2.6, "USDA")
    private val foods = listOf(banana, banana.copy(id = "tofu", name = "Tofu", tags = listOf("soy")),
        banana.copy(id = "oats", name = "Oats", tags = listOf("oats"), ready = false))

    @Test fun aliasesMatchCaseAndPunctuation() {
        val result = FoodRules.check("Ingredients: SOYA-protein, whey, cayenne.", profile, true)
        assertEquals(setOf("Lactose", "Soy", "Spice"), result.findings.map { it.trigger }.toSet())
    }
    @Test fun partialWordsDoNotMatch() {
        assertFalse(FoodRules.contains("butterfly pea flower", "butter"))
        assertFalse(FoodRules.contains("soylent", "soy"))
    }
    @Test fun unknownAndIncompleteLabelsNeverReceiveClearVerdict() {
        assertEquals("Needs clarification", FoodRules.check("", profile).verdict)
        assertEquals("Needs clarification", FoodRules.check("rice, spices", profile, true).verdict)
        assertEquals("Needs clarification", FoodRules.check("rice", profile, false).verdict)
    }
    @Test fun absenceOfMatchesIsNotCalledSafe() {
        assertEquals("No listed trigger found", FoodRules.check("water, rice", profile, true).verdict)
    }
    @Test fun lactoseFreeDairyDoesNotAutomaticallyBypassRestriction() {
        val result = FoodRules.check("Lactose-free milk", profile, true)
        assertTrue(result.findings.isNotEmpty())
        assertTrue(result.uncertainties.any { it.contains("Lactose-free") })
    }
    @Test fun newUserRestrictionIsEnforcedAcrossRecipes() {
        val changed = profile.copy(restrictions = profile.restrictions + Restriction("Banana"))
        assertFalse(FoodRules.allowed(banana, changed))
    }
    @Test fun hostelsCannotUseStoveOnlyRecipes() {
        val recipe = Recipe("Warm banana", listOf(Ingredient("banana", 100.0)), method = "warm")
        assertTrue(RecipeRules.validate(recipe, profile, foods).any { it.contains("stove or microwave") })
        assertTrue(RecipeRules.validate(recipe, profile.copy(mode = "Home"), foods).isEmpty())
    }
    @Test fun kettleDoesNotCountAsStove() {
        val recipe = Recipe("Warm banana", listOf(Ingredient("banana", 100.0)), method = "warm")
        assertTrue(RecipeRules.validate(recipe, profile, foods).isNotEmpty())
    }
    @Test fun noFridgeDisallowsSoaking() {
        val recipe = Recipe("Oats", listOf(Ingredient("oats", 50.0)), method = "soak")
        assertTrue(RecipeRules.validate(recipe, profile.copy(hostel = Environment(equipment = listOf("Kettle"))), foods).isNotEmpty())
    }
    @Test fun validatesGeneratedIdsQuantitiesAndDuplicates() {
        assertTrue(RecipeRules.validate(Recipe("Unknown", listOf(Ingredient("imaginary", 50.0))), profile, foods).isNotEmpty())
        assertTrue(RecipeRules.validate(Recipe("Bad amount", listOf(Ingredient("banana", Double.NaN))), profile, foods).isNotEmpty())
        assertTrue(RecipeRules.validate(Recipe("Duplicates", listOf(Ingredient("banana", 50.0), Ingredient("banana", 50.0))), profile, foods).isNotEmpty())
    }
    @Test fun soySubstitutionIsBlockedInBothModes() {
        val recipe = Recipe("Tofu", listOf(Ingredient("tofu", 100.0)), method = "warm")
        listOf("Hostel", "Home").forEach { mode ->
            assertTrue(RecipeRules.validate(recipe, profile.copy(mode = mode), foods).any { it.contains("conflicts") })
        }
    }
    @Test fun portionsScaleByServings() {
        val recipe = Recipe("Banana", listOf(Ingredient("banana", 200.0)), servings = 2)
        assertEquals(44.5, Nutrition.calculate(recipe, foods, .5).kcal, .001)
    }
    @Test fun unknownMacrosRemainUnknown() {
        val recipe = Recipe("Banana", listOf(Ingredient("banana", 100.0)))
        assertNull(Nutrition.calculate(recipe, listOf(banana.copy(protein = null))).protein)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidPortions() {
        Nutrition.calculate(Recipe("Banana", listOf(Ingredient("banana", 100.0))), foods, -1.0)
    }
    @Test fun loggingSamePlannedMealUpdatesRatherThanDuplicates() {
        val first = Intake(date = "2026-10-02", name = "Lunch", kcal = 300.0, mealId = "meal-1")
        val updated = Intake(date = "2026-10-02", name = "Lunch", kcal = 450.0, mealId = "meal-1")
        val entries = IntakeRules.upsert(listOf(first), updated)
        assertEquals(1, entries.size)
        assertEquals(450.0, entries.single().kcal!!, .001)
    }
    @Test fun injuredProfileDoesNotReceiveAutomaticRoutine() {
        assertFalse(WorkoutRules.validate(Workout("Gentle", listOf("walk")), profile.copy(injuries = "Ankle pain")))
        assertFalse(WorkoutRules.validate(Workout("Unknown", listOf("invented")), profile))
        assertTrue(WorkoutRules.validate(Workout("Gentle", listOf("walk")), profile))
    }
}
