package com.mrpoodles.app

import org.junit.Assert.*
import org.junit.Test

class FoundationModelsTest {
    private fun fixture(name: String) = javaClass.getResource("/snapshots/$name.json")!!.readText()

    @Test fun historicalFixturePreservesEveryLegacyFieldAndGetsStableDefaultIdentities() {
        val text = fixture("historical-v1")
        val legacy = json.decodeFromString<AppData>(text)
        val migrated = SnapshotMigration.decode(text)
        assertEquals(legacy.profile, migrated.profile)
        assertEquals(legacy.revision, migrated.revision)
        assertEquals(legacy.recipes, migrated.recipes)
        assertEquals(legacy.meals, migrated.meals)
        assertEquals(legacy.intake, migrated.intake)
        assertEquals(legacy.messages, migrated.messages)
        assertEquals(legacy.checks, migrated.checks)
        assertEquals(legacy.workout!!.copy(id = migrated.workout!!.id), migrated.workout)
        assertEquals("2026-09-28", migrated.workout.completedDate)
        assertEquals(legacy.recentRecipes.single().copy(id = migrated.recentRecipes.single().id), migrated.recentRecipes.single())
        assertEquals(migrated, SnapshotMigration.decode(text))
        assertEquals(migrated, SnapshotMigration.decode(json.encodeToString(AppData.serializer(), migrated)))
        assertNull(migrated.intake.last().kcal)
        assertNull(migrated.intake.first().protein)
        assertEquals(1.0, migrated.meals.single().portions, 0.0)
        assertTrue(migrated.recoveredRecords.isEmpty())
    }

    @Test fun malformedRecordsAreRetainedAsRawDataAndValidNeighborsRemainAvailable() {
        val data = SnapshotMigration.decode(fixture("damaged-v1"))
        assertEquals("Preserve my profile", data.profile.name)
        assertEquals(listOf("unknown-food", "bad-serving", "good"), data.recipes.map { it.id })
        assertEquals("retired-food", data.recipes.first().ingredients.single().id)
        assertEquals(-20.0, data.recipes[1].ingredients.single().grams, 0.0)
        assertEquals(2, data.recoveredRecords.size)
        assertTrue(data.recoveredRecords.first().raw.toString().contains("not a list"))
        assertEquals(data, SnapshotMigration.decode(json.encodeToString(AppData.serializer(), data)))
        assertNull(Nutrition.calculateOrNull(data.recipes.first(), emptyList()))
        assertNull(Nutrition.calculateOrNull(data.recipes[1], emptyList()))
        assertNull(Nutrition.calculateOrNull(Recipe("Empty", emptyList()), emptyList()))
    }

    @Test fun futureSchemasAndBrokenLedgersBlockRatherThanSilentlyLoseData() {
        assertThrows(IllegalArgumentException::class.java) { SnapshotMigration.decode("{\"schemaVersion\":99}") }
        assertThrows(Exception::class.java) { SnapshotMigration.decode("{\"intakeOperations\":[{\"operationId\":\"important\"}]}") }
        assertThrows(Exception::class.java) { SnapshotMigration.decode("{\"profile\":false}") }
    }

    @Test fun sourcedModelsRoundTripWithoutInventingNutritionOrMergingPrivateConversations() {
        val source = RetrievedSource("s1", "https://example.org/recipe", "Example fixture", "2026-10-03T00:00:00Z", excerpt = "Recipe text")
        val origin = ResultOrigin("r1", 7, listOf(UserAssertion("No coconut", "m1")), RetrievalSnapshot("snapshot", "r1", listOf(source)))
        val evidence = EvidenceReference("snapshot", "s1", "Recipe text")
        val ingredient = NamedIngredient("flour", "Flour", 2.0, "cup", evidence = listOf(evidence))
        val recipe = Recipe("Example adaptation", emptyList(), sourced = SourcedRecipe(origin, listOf(ingredient), listOf("Mix"), 2.0,
            waitingMinutes = 120, adaptation = RecipeAdaptation("AI adaptation", listOf(Substitution(ingredient, ingredient.copy(name = "Oat flour"), "User request", listOf(evidence))))))
        val workout = Workout("Example session", emptyList(), sourced = SourcedWorkout(origin, emptyList(),
            listOf(WorkoutMovement("Walk", "Walk gently", seconds = 60, evidence = listOf(evidence))), emptyList(),
            VideoReference("https://www.youtube.com/watch?v=fixture", "Fixture", "Fixture channel", evidence)))
        val feature = FeatureConversation(draft = "Keep typing", messages = listOf(Message("You", "Recipe question")),
            result = FeatureResult(origin, recipe = recipe), selectedDate = "2026-09-20", scrollIndex = 3, scrollOffset = 12)
        val data = AppData(recipes = listOf(recipe), savedWorkouts = listOf(workout), recipeDraft = recipe,
            workoutCompletions = listOf(WorkoutCompletion(workout.id, "2026-10-03")),
            features = mapOf(Feature.RECIPE to feature, Feature.WORKOUT to FeatureConversation(draft = "No jumping")))
        val restored = SnapshotMigration.decode(json.encodeToString(AppData.serializer(), data))
        assertEquals(data, restored)
        assertNull(restored.recipes.single().sourced!!.nutrition.kcal)
        assertEquals(EstimateStatus.UNKNOWN, restored.recipes.single().sourced!!.nutrition.status)
        assertTrue(restored.features.getValue(Feature.WORKOUT).messages.isEmpty())
        assertNull(Nutrition.calculateOrNull(recipe, emptyList()))
    }

    @Test fun staleAttemptProfileAndInputCannotReplaceResultOrError() {
        val first = RequestIdentity("first", 1)
        val second = RequestIdentity("second", 1)
        val initial = FeatureConversation(draft = "A recipe").begin(first)
        val newer = initial.begin(second)
        val result = FeatureResult(ResultOrigin(first.id, 1))
        assertEquals(newer, newer.complete(first, 1, result))
        assertEquals(newer, newer.fail(first, 1, "Old failure"))
        assertEquals(initial, initial.complete(first, 2, result))
        val edited = initial.edit("Different recipe")
        assertEquals(edited, edited.complete(first, 1, result))
        val complete = initial.complete(first, 1, result)
        assertEquals(result, complete.result)
        assertNull(complete.activeRequest)
        val recovered = initial.recover()
        assertNull(recovered.activeRequest)
        assertEquals(first, recovered.retryRequest)
        assertEquals("A recipe", recovered.draft)
        assertThrows(IllegalArgumentException::class.java) { recovered.begin(first) }
        assertEquals(result, complete.begin(second).fail(second, 1, "Offline").result)
    }
}
