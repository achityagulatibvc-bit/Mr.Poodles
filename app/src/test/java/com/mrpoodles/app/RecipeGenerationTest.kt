package com.mrpoodles.app

import org.junit.Assert.*
import org.junit.Test

class RecipeGenerationTest {
    @Test fun requestedIngredientsAreRequiredAndNegatedOnesAreExcluded() {
        val intent = RecipeGeneration.intent("Pasta without banana or oats")
        assertEquals(setOf("pasta"), intent.required)
        assertEquals(setOf("banana", "oats"), intent.excluded)
        assertTrue(RecipeGeneration.intent("No more spinach bowls, but a potato sandwich").excluded.contains("spinach"))
    }
    @Test fun specificIngredientNamesDoNotAlsoRequireTheirGenericNames() {
        assertEquals(setOf("brownrice"), RecipeGeneration.intent("brown rice").required)
        assertEquals(setOf("sweetpotato"), RecipeGeneration.intent("sweet potatoes").required)
    }
    @Test fun renamingOrReportioningARecipeDoesNotPassVarietyCheck() {
        val old = Recipe("Spinach bowl", listOf(Ingredient("spinach", 40.0), Ingredient("rice", 150.0)))
        val repeated = old.copy(title = "A completely new bowl", ingredients = listOf(Ingredient("rice", 170.0), Ingredient("spinach", 35.0), Ingredient("lemon", 5.0)))
        assertFalse(RecipeGeneration.accepts(repeated, RecipeIntent(emptySet(), emptySet()), listOf(old)))
    }
    @Test fun aSpinachBowlCannotAnswerAPastaRequest() {
        val recipe = Recipe("Pasta surprise", listOf(Ingredient("spinach", 40.0), Ingredient("rice", 150.0)))
        assertFalse(RecipeGeneration.accepts(recipe, RecipeGeneration.intent("pasta"), emptyList()))
    }
    @Test fun preparedStepsCannotIntroduceUnknownIngredientsOrHeatingWithoutEquipment() {
        val food = Food("carrot", "Carrot", 170393, "Carrot", listOf("carrot"), true, 41.0, source = "USDA")
        val unknown = Recipe("Carrots", listOf(Ingredient("carrot", 100.0)), preparation = listOf(PreparationStep("mix", listOf("butter"))))
        assertTrue(RecipeRules.validate(unknown, Profile(), listOf(food)).isNotEmpty())
        val heat = unknown.copy(preparation = listOf(PreparationStep("warm", listOf("carrot"))))
        assertTrue(RecipeRules.validate(heat, Profile(), listOf(food)).isNotEmpty())
    }
    @Test fun oldRecipesRemainReadableWithoutNewPreparationFields() {
        val old = json.decodeFromString<Recipe>("""{"title":"Saved meal","ingredients":[{"id":"rice","grams":100}]}""")
        assertTrue(old.preparation.isEmpty())
        assertTrue(RecipeRules.steps(old).contains("ready-to-eat"))
    }
}
