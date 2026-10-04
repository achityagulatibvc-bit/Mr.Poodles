package com.mrpoodles.app

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic evidence only: these fixtures are not published recipes or real manufacturer labels. */
class Phase4FoodRulesTest {
    private val id = RequestIdentity("phase4-request", 7)
    private val profile = Profile(sourceLookupConsent = true, restrictions = listOf(
        Restriction("Lactose", "Intolerance", "milk, whey, cream, butter, ghee"),
        Restriction("Soy", "Allergy")
    ))
    private val noRestrictions = profile.copy(restrictions = emptyList())
    private val product = FeatureConversation(brand = "Sample", variant = "Original", country = "India")

    private fun fixture(name: String) = javaClass.getResource("/phase4/$name.txt")!!.readText()
    private fun source(name: String = "tiramisu", title: String = "Tiramisu recipe", kind: String = "recipe",
        text: String = fixture(name), sourceId: String = name) = RetrievedSource(sourceId,
        "https://example.org/$sourceId", title, "2026-10-04T08:00:00Z", author = "Synthetic Test Kitchen",
        publisher = "Synthetic fixtures", excerpt = text, kind = kind)
    private fun label(text: String = fixture("rice-label"), sourceId: String = "rice-label",
        title: String = "Sample Rice Original") = source("rice-label", title, "manufacturer", text, sourceId)
    private fun origin(vararg sources: RetrievedSource) = ResultOrigin(id.id, id.profileRevision,
        retrieval = RetrievalSnapshot("fixture-snapshot", id.id, sources.toList()))
    private fun parse(source: RetrievedSource = source(), origin: ResultOrigin = origin(source)) =
        requireNotNull(PublishedRecipeParser.parse(source, origin))
    private fun conversation(recipe: Recipe, subject: String = "tiramisu recipe", exclusions: List<String> = emptyList()) =
        FeatureConversation(result = FeatureResult(requireNotNull(recipe.origin), recipe = recipe), subject = subject, exclusions = exclusions)

    private class FakeResearch(private vararg val batches: List<RetrievedSource>) : CloudModel() {
        val requests = mutableListOf<ResearchRequest>()
        var failure: Throwable? = null
        var transform: (ResearchResponse) -> ResearchResponse = { it }
        override suspend fun research(input: ResearchRequest): ResearchResponse {
            requests += input
            failure?.let { throw it }
            check(requests.size <= batches.size) { "Unexpected research call" }
            return transform(ResearchResponse(2, input.requestId, input.profileRevision,
                RetrievalSnapshot("network-${requests.size}", input.requestId, batches[requests.size - 1]),
                answer = ResearchAnswer("A friendly research acknowledgement.", emptyList(), emptyList()), provider = "synthetic"))
        }
    }

    @Test fun tiramisuDetectsLactoseAndRejectsSoyReplacementBeforeUsingSupportedAlternative() = runBlocking {
        val original = source()
        val guidance = source("tiramisu-substitutions", "Tiramisu substitutions")
        val fake = FakeResearch(listOf(original), listOf(guidance))
        assertTrue(SourcedRecipeRules.validate(parse(original), profile).any { it.contains("Lactose") })
        val reply = SourcedFoodService(fake).recipe(id, profile, FeatureConversation(), "tiramisu recipe")
        val recipe = requireNotNull(reply.recipe)
        val data = requireNotNull(recipe.sourced)
        assertEquals("coconut cream", data.ingredients.first().name)
        assertEquals(250.0, data.ingredients.first().amount!!, 0.0)
        assertTrue(SourcedRecipeRules.validate(recipe, profile).isEmpty())
        val adaptation = requireNotNull(data.adaptation)
        assertEquals("mascarpone", adaptation.substitutions.single().original.name)
        assertEquals("Adapted from the published recipe using cited equal-amount guidance. Texture can differ.", adaptation.explanation)
        assertTrue(recipe.aiGenerated)
        assertNull(data.nutrition.kcal)
        assertEquals(2, fake.requests.size)
        assertEquals(profile.restrictions.map { it.kind }, fake.requests.first().constraints.restrictions.map { it.kind })
        assertEquals(listOf("Kettle", "Fridge"), fake.requests.first().constraints.equipment)
        assertFalse(fake.requests.first().allowExternalModel)
        val references = data.ingredients.flatMap { it.evidence } + data.instructionEvidence + adaptation.substitutions.flatMap { it.evidence }
        assertTrue(FoodEvidence.valid(data.origin, references))
        assertEquals("Synthetic Test Kitchen", data.origin.retrieval!!.sources.first().author)
    }

    @Test fun allReplacementConflictsProduceQuestionRatherThanWeakenProfile() = runBlocking {
        val guidance = source("guide", "Tiramisu substitutions", text = fixture("tiramisu-substitutions")
            .replace("coconut cream", "soy yogurt"))
        val originalProfile = profile.copy()
        val reply = SourcedFoodService(FakeResearch(listOf(source()), listOf(guidance)))
            .recipe(id, profile, FeatureConversation(), "tiramisu recipe")
        assertNull(reply.recipe)
        assertTrue(reply.message.contains('?'))
        assertEquals(originalProfile, profile)
    }

    @Test fun noCoconutPersistsAcrossRetrievalAndFindsDocumentedOatAlternative() = runBlocking {
        val first = requireNotNull(SourcedFoodService(FakeResearch(listOf(source()),
            listOf(source("tiramisu-substitutions", "Tiramisu substitutions"))))
            .recipe(id, profile, FeatureConversation(), "tiramisu recipe").recipe)
        val guide = source("oat-guide", "Tiramisu substitutions", text = fixture("tiramisu-substitutions").replace("coconut cream", "oat cream"))
        val fake = FakeResearch(listOf(source()), listOf(guide))
        val reply = SourcedFoodService(fake).recipe(id.copy(id = "edit"), profile, conversation(first), "No coconut")
        assertEquals(listOf("coconut"), reply.exclusions)
        val edited = requireNotNull(reply.recipe)
        val data = requireNotNull(edited.sourced)
        assertEquals("oat cream", data.ingredients.first().name)
        assertEquals(listOf("coconut"), data.excludedIngredients)
        assertFalse(data.instructions.any { FoodRules.contains(it, "coconut") })
        assertEquals("coconut cream", first.sourced!!.ingredients.first().name)
        assertNotEquals(first.id, edited.id)
    }

    @Test fun noCoconutWithOnlyCoconutGuidanceFailsHelpfully() = runBlocking {
        val fake = FakeResearch(listOf(source()), listOf(source("tiramisu-substitutions", "Tiramisu substitutions")))
        val reply = SourcedFoodService(fake).recipe(id, profile, FeatureConversation(), "tiramisu recipe without coconut")
        assertNull(reply.recipe)
        assertEquals(listOf("coconut"), reply.exclusions)
        assertTrue(reply.message.contains('?'))
    }

    @Test fun twoServingsSurviveSubstitutionRetrievalAndLaterFollowUps() = runBlocking {
        val fake = FakeResearch(listOf(source()), listOf(source("tiramisu-substitutions", "Tiramisu substitutions")))
        val first = requireNotNull(SourcedFoodService(fake).recipe(id, profile, FeatureConversation(), "tiramisu recipe for two").recipe)
        val firstData = requireNotNull(first.sourced)
        assertEquals(2.0, firstData.servings, 0.0)
        assertEquals(125.0, firstData.ingredients.first().amount!!, 0.0)
        assertEquals(4.0, firstData.originalServings!!, 0.0)
        val guide = source("oat-guide", "Tiramisu substitutions", text = fixture("tiramisu-substitutions").replace("coconut cream", "oat cream"))
        val next = requireNotNull(SourcedFoodService(FakeResearch(listOf(source()), listOf(guide)))
            .recipe(id.copy(id = "new"), profile, conversation(first, "tiramisu recipe for two"), "No coconut").recipe)
        val nextData = requireNotNull(next.sourced)
        assertEquals(2.0, nextData.servings, 0.0)
        assertEquals(125.0, nextData.ingredients.first().amount!!, 0.0)
        assertTrue(SourcedRecipeRules.validate(next, profile).isEmpty())
    }

    @Test fun servingRequestBeforeAValidResultIsNotLostDuringRetryWithNewExclusion() = runBlocking {
        val state = FeatureConversation(subject = "tiramisu recipe", messages = listOf(
            Message("user", "tiramisu recipe"), Message("assistant", "Please share another source."),
            Message("user", "Make two servings"), Message("assistant", "Please share another source.")
        ))
        val fake = FakeResearch(listOf(source()), listOf(source("tiramisu-substitutions", "Tiramisu substitutions")))
        val result = requireNotNull(SourcedFoodService(fake).recipe(id, profile, state, "No peanuts").recipe)
        assertEquals(2.0, result.sourced!!.servings, 0.0)
    }

    @Test fun localServingEditRebindsEveryCitationAndCreatesNewIdWithoutResearch() = runBlocking {
        val saved = parse().copy(id = "saved-recipe")
        val fake = FakeResearch()
        val edited = requireNotNull(SourcedFoodService(fake).recipe(id.copy(id = "new-request"), noRestrictions,
            conversation(saved), "Make two servings").recipe)
        assertTrue(fake.requests.isEmpty())
        assertNotEquals(saved.id, edited.id)
        assertEquals(4.0, saved.sourced!!.servings, 0.0)
        val data = requireNotNull(edited.sourced)
        val editedOrigin = requireNotNull(edited.origin)
        val savedSnapshot = requireNotNull(saved.origin?.retrieval)
        val editedSnapshot = requireNotNull(editedOrigin.retrieval)
        assertEquals(2.0, data.servings, 0.0)
        assertEquals(125.0, data.ingredients.first().amount!!, 0.0)
        assertEquals("new-request", editedOrigin.requestId)
        assertNotEquals(savedSnapshot.id, editedSnapshot.id)
        assertEquals(savedSnapshot.sources, editedSnapshot.sources)
        assertTrue(SourcedRecipeRules.validate(edited, noRestrictions).isEmpty())
    }

    @Test fun unsupportedFollowUpDoesNotPretendToHaveAppliedIt() = runBlocking {
        val fake = FakeResearch()
        val reply = SourcedFoodService(fake).recipe(id, noRestrictions, conversation(parse()), "Make it vegan")
        assertNull(reply.recipe)
        assertTrue(reply.message.contains('?'))
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun hostelEquipmentRevalidatesCurrentRecipeAndRemainsRequestLocal() = runBlocking {
        val blenderSource = source(text = fixture("tiramisu").replace("Mix mascarpone", "Use a blender to mix mascarpone"))
        val home = noRestrictions.copy(mode = "Home", home = Environment(equipment = listOf("Blender", "Fridge")))
        val saved = parse(blenderSource)
        assertTrue(SourcedRecipeRules.validate(saved, home).isEmpty())
        val fake = FakeResearch(listOf(blenderSource))
        val reply = SourcedFoodService(fake).recipe(id, home, conversation(saved), "Use hostel equipment")
        assertNull(reply.recipe)
        assertTrue(reply.message.contains("blender"))
        assertEquals(home.hostel.equipment, fake.requests.single().constraints.equipment)
        assertEquals("Home", home.mode)
    }

    @Test fun equipmentVariantsAreRecognizedAndKettleDoesNotStandInForStove() {
        for (method in listOf("Fry the onions.", "Simmer in a skillet.", "Boil the rice.", "Steam the rice.")) {
            assertTrue(SourcedRecipeRules.equipmentIssues(method, listOf("Kettle")).any { it.contains("stove") })
            assertTrue(SourcedRecipeRules.equipmentIssues(method, listOf("Induction cooktop")).isEmpty())
        }
        assertTrue(SourcedRecipeRules.equipmentIssues("Bake in a baking pan.", listOf("Convection oven")).isEmpty())
        assertTrue(SourcedRecipeRules.equipmentIssues("Use a hand-mixer, then chill.", listOf("Hand mixer", "Mini-fridge")).isEmpty())
        assertTrue(SourcedRecipeRules.equipmentIssues("Use the microwave oven.", listOf("Microwave oven")).isEmpty())
        assertTrue(SourcedRecipeRules.equipmentIssues("Use a food processor.", listOf("Blender")).isNotEmpty())
        assertTrue(SourcedRecipeRules.equipmentIssues("Use a pressure cooker.", listOf("Kettle")).isNotEmpty())
    }

    @Test fun wrongDishOrContradictoryHeadingNeverBecomesRequestedRecipe() = runBlocking {
        for (wrong in listOf(source(title = "Potato salad recipe"), source(text = fixture("tiramisu").replace("# Tiramisu", "# Potato salad")))) {
            val reply = SourcedFoodService(FakeResearch(listOf(wrong))).recipe(id, noRestrictions, FeatureConversation(), "tiramisu recipe")
            assertNull(reply.recipe)
            assertTrue(reply.message.contains("matching recipe"))
        }
        val check = SourcedFoodService(FakeResearch(listOf(source(title = "Potato salad recipe"))))
            .check(id, profile, FeatureConversation(foodKind = "dish"), "tiramisu")
        assertEquals(AssessmentOutcome.NEED_MORE_INFORMATION, check.assessment!!.outcome)
        assertTrue(requireNotNull(check.assessment).evidence.isEmpty())
    }

    @Test fun modelProseCannotSupplyIngredientsQuantitiesOrCitations() = runBlocking {
        val fake = FakeResearch(listOf(source()))
        fake.transform = { it.copy(answer = ResearchAnswer("Use 999 grams of invented cream. https://invented.example.org/recipe is the source.", emptyList(), emptyList())) }
        val recipe = requireNotNull(SourcedFoodService(fake).recipe(id, noRestrictions, FeatureConversation(), "tiramisu recipe").recipe)
        assertEquals(250.0, recipe.sourced!!.ingredients.first().amount!!, 0.0)
        assertFalse(recipe.toString().contains("invented"))
        assertEquals("https://example.org/tiramisu", recipe.origin!!.retrieval!!.sources.single().url)
    }

    @Test fun missingQuantitiesTruncatedMethodsAndUnknownRowsFailInsteadOfInventing() {
        for (text in listOf(
            fixture("tiramisu").replace("250 g mascarpone", "mascarpone"),
            fixture("tiramisu").replace("250 g mascarpone", "300-250 g mascarpone"),
            fixture("tiramisu").replace("250 g mascarpone", "1 250g tub mascarpone"),
            fixture("tiramisu").replace("250 g mascarpone", "1,000 g mascarpone"),
            fixture("tiramisu").replace("250 g mascarpone", "250—300 g mascarpone"),
            fixture("tiramisu").replace("20 g sugar", "20 g sugar\nMystery ingredient"),
            fixture("tiramisu").trimEnd().removeSuffix("."),
            fixture("tiramisu").replace("2. Add", "3. Add"),
            fixture("tiramisu").replace("Serves: 4", "Serves: 4-6")
        )) assertNull(text, PublishedRecipeParser.parse(source(text = text), origin(source(text = text))))
    }

    @Test fun explicitFractionsAndUnquantifiedDustingStayFaithfulToSource() {
        val text = fixture("tiramisu").replace("250 g mascarpone", "1½ cups mascarpone").replace("20 g sugar", "20 g sugar\ncocoa for dusting")
        val parsed = parse(source(text = text))
        val data = requireNotNull(parsed.sourced)
        assertEquals(1.5, data.ingredients.first().amount!!, 0.0)
        assertEquals("cups", data.ingredients.first().unit)
        assertNull(data.ingredients.last().amount)
        val scaled = SourcedRecipeRules.scale(parsed, 2.0)
        val scaledData = requireNotNull(scaled.sourced)
        assertEquals(0.75, scaledData.ingredients.first().amount!!, 0.0)
        assertNull(scaledData.ingredients.last().amount)
    }

    @Test fun parserHasNoFixedCatalogIngredientCountOrMethodGate() {
        val text = "# Large test recipe\nServes: 4\nPrep: 10 minutes\nCook: 20 minutes\nIngredients:\n" +
            (1..40).joinToString("\n") { "$it g ingredient $it" } + "\nMethod:\n1. Bake the ingredients in an oven for twenty minutes."
        val parsed = parse(source(title = "Large test recipe", text = text))
        assertEquals(40, parsed.sourced!!.ingredients.size)
        assertTrue(SourcedRecipeRules.validate(parsed, noRestrictions.copy(hostel = Environment(listOf("Oven")))).isEmpty())
    }

    @Test fun activeCookAndChillTimesUseTheirOwnLabeledValues() {
        val text = fixture("tiramisu").replace("Prep: 15 minutes\nChill: 2 hours", "Prep: 15 minutes Cook: 5 minutes Chill: 2 hours")
        val data = parse(source(text = text)).sourced!!
        assertEquals(15, data.activeMinutes)
        assertEquals(5, data.cookingMinutes)
        assertEquals(120, data.waitingMinutes)
        val overflowing = source(text = fixture("tiramisu").replace("Prep: 15 minutes", "Prep: 71582789 hours"))
        assertNull(PublishedRecipeParser.parse(overflowing, origin(overflowing)))
    }

    // Invented prose and quantities; reproduces only the reported Markdown layout, not the article.
    private fun terminalListRecipe() = """
        # Synthetic layered dessert
        Serves 4 Easy
        Prep: 18 mins Plus chilling

        ## Ideas to consider
        - 99g sugar suggested in an unrelated earlier tip

        These earlier tips are separate from the ingredient list below.

        - 180ml double cream
        - 140g mascarpone
        - 200ml coffee
          made with 2 tsp coffee granules and 200ml boiling water
        - 90g sponge fingers

        Nutrition: per serving
        kcal 500
        fat 30g
        carbs 40g

        ## Method
        - ### step 1
        Fold double cream into mascarpone.
        Keep the mixture in one bowl.

        - ### step 2
        Layer sponge fingers with coffee and the cream mixture.
        Refrigerate for a few hours or overnight.

        ###### Synthetic Author
        This invented biography is outside the recipe method.
    """.trimIndent()

    @Test fun terminalMarkdownListKeepsIngredientsContinuationsAndMethodEvidenceSeparateFromTipsAndNutrition() {
        val source = source(title = "Synthetic layered dessert", text = terminalListRecipe())
        val recipe = parse(source)
        val data = requireNotNull(recipe.sourced)
        assertEquals(listOf("double cream", "mascarpone", "coffee", "sponge fingers"), data.ingredients.map { it.name })
        assertEquals(listOf(180.0, 140.0, 200.0, 90.0), data.ingredients.map { it.amount })
        assertEquals(4.0, data.servings, 0.0)
        assertEquals(18, data.activeMinutes)
        assertNull(data.waitingMinutes)
        assertNull(data.nutrition.kcal)
        assertEquals(2, data.instructions.size)
        assertEquals("Fold double cream into mascarpone.\nKeep the mixture in one bowl.", data.instructions.first())
        assertFalse(data.instructions.any { it.contains("biography") || it.contains("Synthetic Author") })
        val coffee = data.ingredients[2]
        assertEquals("made with 2 tsp coffee granules and 200ml boiling water", coffee.preparation)
        assertEquals(2, coffee.evidence.size)
        assertTrue(coffee.evidence.any { it.excerpt.startsWith("  made with") })
        val refs = data.ingredients.flatMap { it.evidence } + data.metadataEvidence + data.instructionEvidence
        assertTrue(FoodEvidence.valid(data.origin, refs))
        assertFalse(refs.any { it.excerpt.contains("99g") || it.excerpt.contains("kcal 500") })
        val issues = SourcedRecipeRules.validate(recipe, profile)
        assertTrue(issues.any { it.contains("Lactose") })
        assertTrue(issues.any { it.contains("chilling or waiting time") })
        assertTrue(issues.any { it.contains("prepared ingredient") })
        assertFalse(issues.any { it.contains("original complete recipe sections") })
    }

    @Test fun terminalListAlsoSupportsDirectMethodBoundaryAndOnlyExactDifficultyTags() {
        val withoutNutrition = terminalListRecipe().replace("Nutrition: per serving\nkcal 500\nfat 30g\ncarbs 40g\n", "")
        for (tag in listOf("Easy", "More effort", "A challenge", "")) {
            val parsed = parse(source(text = withoutNutrition.replace("Serves 4 Easy", "Serves 4 $tag")))
            assertEquals(4, parsed.sourced!!.ingredients.size)
        }
        for (servingText in listOf("Serves 4-6 Easy", "Serves 4 Almost easy", "Serves 4 Easy 8", "Serves Easy")) {
            val source = source(text = terminalListRecipe().replace("Serves 4 Easy", servingText))
            assertNull(servingText, PublishedRecipeParser.parse(source, origin(source)))
        }
    }

    @Test fun unknownOrInterruptedTerminalRowsCannotBeDroppedToAcceptAnIngredientListSuffix() {
        for (text in listOf(
            terminalListRecipe().replace("- 180ml double cream", "- double cream"),
            terminalListRecipe().replace("- 180ml double cream", "- -180ml double cream"),
            terminalListRecipe().replace("- 140g mascarpone", "unreadable ingredient"),
            terminalListRecipe().replace("- 140g mascarpone", "- mystery paste"),
            terminalListRecipe().replace("Nutrition: per serving", "An unrelated article paragraph follows.\nNutrition: per serving"),
            terminalListRecipe().replace("kcal 500", "- 20g milk powder\nkcal 500"),
            terminalListRecipe().replace("- ### step 2", "- ### step 3")
        )) {
            val source = source(text = text)
            assertNull(text, PublishedRecipeParser.parse(source, origin(source)))
        }
    }

    @Test fun vagueWaitingTextStaysUnknownEvenWhenOnlyMetadataRequiresChilling() {
        for (waiting in listOf("overnight", "a few hours", "several hours")) {
            val text = terminalListRecipe().replace("Refrigerate for a few hours or overnight.", "Serve the dessert in small bowls.")
                .replace("Prep: 18 mins Plus chilling", "Prep: 18 mins\nChill: $waiting")
            val recipe = parse(source(text = text))
            val data = requireNotNull(recipe.sourced)
            assertNull(data.waitingMinutes)
            assertTrue(data.metadataEvidence.any { it.excerpt == "Chill: $waiting" })
            assertTrue(SourcedRecipeRules.validate(recipe, noRestrictions).any { it.contains("chilling or waiting time") })
        }
    }

    @Test fun continuationIngredientsRemainRestrictionEvidenceAndEmbeddedQuantitiesCannotBeSilentlyScaled() {
        val source = source(text = terminalListRecipe().replace("coffee granules", "soy powder"))
        val recipe = parse(source)
        val coffee = requireNotNull(recipe.sourced).ingredients[2]
        assertTrue(coffee.preparation.contains("soy powder"))
        assertTrue(SourcedRecipeRules.validate(recipe, profile).any { it.contains("Soy (Allergy)") })
        val scaled = SourcedRecipeRules.scale(recipe, 2.0)
        assertEquals(100.0, requireNotNull(scaled.sourced).ingredients[2].amount!!, 0.0)
        assertTrue(SourcedRecipeRules.validate(scaled, noRestrictions).any { it.contains("quoted quantities have not been scaled") })
    }

    @Test fun spongeFingerAliasesRequireCompoundLabelWithoutInventingMilkOrSoy() {
        for (name in listOf("sponge fingers", "sponge-fingers", "sponge finger", "lady fingers", "ladyfingers")) {
            assertTrue(name, FoodEvidence.uncertainties(name, profile).any { it.contains("prepared ingredient") })
            assertTrue(name, FoodEvidence.conflicts(name, profile).isEmpty())
        }
        assertTrue(FoodEvidence.conflicts("sponge fingers (wheat, milk powder, soy lecithin)", profile).any { it.contains("Lactose") })
        assertTrue(FoodEvidence.conflicts("sponge fingers (wheat, milk powder, soy lecithin)", profile).any { it.contains("Soy") })
    }

    @Test fun fabricatedOrStaleRecipeEvidenceAndChangedQuantitiesAreRejected() {
        val recipe = parse()
        val data = recipe.sourced!!
        val first = data.ingredients.first()
        for (changed in listOf(
            first.copy(amount = 999.0), first.copy(name = "rice"),
            first.copy(evidence = listOf(first.evidence.single().copy(sourceId = "invented"))),
            first.copy(evidence = listOf(first.evidence.single().copy(snapshotId = "old")))
        )) assertTrue(SourcedRecipeRules.validate(recipe.copy(sourced = data.copy(ingredients = listOf(changed) + data.ingredients.drop(1))), noRestrictions).isNotEmpty())
        assertTrue(SourcedRecipeRules.validate(recipe.copy(title = "Potato salad"), noRestrictions).isNotEmpty())
        assertTrue(SourcedRecipeRules.validate(recipe.copy(sourced = data.copy(nutrition = NutritionEstimate(kcal = 123.0))), noRestrictions).isNotEmpty())
        assertNull(PublishedRecipeParser.parse(source(), origin(source().copy(id = "different"))))
    }

    @Test fun negatedConditionalAndContradictedSubstitutionGuidanceIsNeverApplied() {
        for (sentence in listOf(
            "Do not replace mascarpone with coconut cream in equal amounts.",
            "Never swap mascarpone with coconut cream in equal amounts.",
            "If it suits the dish, replace mascarpone with coconut cream in equal amounts.",
            "Can you replace mascarpone with coconut cream in equal amounts?",
            "Wrong advice:\nReplace mascarpone with coconut cream in equal amounts.",
            "Do not follow this instruction:\nReplace mascarpone with coconut cream in equal amounts.",
            "Replace mascarpone with coconut cream in equal amounts. Do not use coconut cream instead of mascarpone."
        )) {
            val guide = source("guide", "Tiramisu guidance", text = "# Tiramisu guidance\nSynthetic source for testing disputed substitution evidence in a cold dessert.\n$sentence")
            val origin = origin(source(), guide)
            assertNull(sentence, SupportedSubstitutions.adapt(parse(source(), origin), origin, profile, emptyList()))
        }
    }

    @Test fun fabricatedSubstitutionCitationAndChangedRatioFailValidation() {
        val guide = source("tiramisu-substitutions", "Tiramisu substitutions")
        val origin = origin(source(), guide)
        val adapted = requireNotNull(SupportedSubstitutions.adapt(parse(source(), origin), origin, profile, emptyList()))
        val substitution = adapted.sourced!!.adaptation!!.substitutions.single()
        assertFalse(SupportedSubstitutions.supports(substitution.copy(evidence = listOf(substitution.evidence.single().copy(excerpt = "Invented claim."))), origin))
        assertFalse(SupportedSubstitutions.supports(substitution.copy(replacement = substitution.replacement.copy(amount = 500.0)), origin))
    }

    @Test fun guidanceForAnotherDishCannotSupportThisAdaptation() {
        val guide = source("other-guide", "Potato salad substitutions", text = fixture("tiramisu-substitutions").replace("Tiramisu", "Potato salad"))
        val origin = origin(source(), guide)
        assertNull(SupportedSubstitutions.adapt(parse(source(), origin), origin, profile, emptyList()))
    }

    @Test fun heatedMethodsNeedMoreThanEqualAmountGuidance() {
        val guide = source("tiramisu-substitutions", "Tiramisu substitutions")
        for (verb in listOf("Microwave", "Steam", "Grill", "Warm", "Melt")) {
            val original = source(text = fixture("tiramisu").replace("Mix mascarpone", "$verb mascarpone"))
            val origin = origin(original, guide)
            assertNull(verb, SupportedSubstitutions.adapt(parse(original, origin), origin, profile, emptyList()))
        }
    }

    @Test fun retainedOldDishAfterFailedSearchCannotBecomeNewDishFollowUp() = runBlocking {
        val oldSource = source(title = "Potato salad recipe", text = fixture("tiramisu").replace("Tiramisu", "Potato salad"))
        val oldRecipe = parse(oldSource)
        val fake = FakeResearch(listOf(source()))
        val reply = SourcedFoodService(fake).recipe(id, noRestrictions,
            conversation(oldRecipe, "tiramisu recipe"), "Make two servings")
        assertEquals(1, fake.requests.size)
        assertEquals("Tiramisu", requireNotNull(reply.recipe).title)
        assertEquals("Potato salad", oldRecipe.title)
    }

    @Test fun pendingEquipmentEditSurvivesLocalServingEditAndLaterValidation() = runBlocking {
        val originalSource = source(text = fixture("tiramisu").replace("Mix mascarpone", "Use a blender to mix mascarpone"))
        val originalOrigin = origin(originalSource).copy(assertions = listOf(UserAssertion("Use home equipment")))
        val previous = parse(originalSource, originalOrigin)
        val home = noRestrictions.copy(mode = "Home", home = Environment(listOf("Blender", "Fridge")))
        val state = conversation(previous).copy(messages = listOf(Message("user", "Use hostel equipment")))
        val fake = FakeResearch(listOf(source()))
        val reply = SourcedFoodService(fake).recipe(id, home, state, "Make two servings")
        assertEquals(1, fake.requests.size)
        val data = requireNotNull(reply.recipe?.sourced)
        assertTrue(data.origin.assertions.any { it.text == "Use hostel equipment" })
        assertFalse(data.instructions.any { it.contains("blender") })
        assertTrue(SourcedRecipeRules.validate(requireNotNull(reply.recipe), home).isEmpty())
    }

    @Test fun exclusionsUseKnownAliasesAndNeverSilentlyRestoreOriginalMilk() {
        assertTrue(FoodEvidence.excluded("tofu", "soy"))
        assertTrue(FoodEvidence.excluded("mascarpone", "dairy"))
        assertFalse(FoodEvidence.excluded("coconut cream", "dairy"))
        val recipe = parse()
        assertTrue(SourcedRecipeRules.validate(recipe.copy(sourced = requireNotNull(recipe.sourced).copy(
            excludedIngredients = listOf("dairy"))), noRestrictions).any { it.contains("excluded") })
    }

    @Test fun sourceConsentIsRequiredAndExternalModelConsentIsNeverAssumed() {
        val fake = FakeResearch()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SourcedFoodService(fake).recipe(id, profile.copy(sourceLookupConsent = false), FeatureConversation(), "tiramisu recipe") }
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun productVariantCountryAndBrandMustBeSpecifiedBeforeLookup() = runBlocking {
        for (state in listOf(product.copy(variant = ""), product.copy(country = ""), product.copy(brand = ""))) {
            val fake = FakeResearch()
            val reply = SourcedFoodService(fake).check(id, profile, state, "Sample Rice Original")
            assertNull(reply.assessment)
            assertTrue(reply.message.contains("brand, exact variant and country"))
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test fun wrongProductOrCountryAndMissingManufacturerLabelStayUncertain() = runBlocking {
        for (candidate in listOf(
            label(title = "Sample Cookies Original"),
            label(text = fixture("rice-label").replace("India", "Canada")),
            label(text = "# Sample Rice Original\nCountry: India\n" + "This manufacturer page describes the product but supplies no ingredient label. ".repeat(2)),
            label().copy(kind = "recipe")
        )) {
            val reply = SourcedFoodService(FakeResearch(listOf(candidate))).check(id, profile, product, "Sample Rice Original")
            assertEquals(AssessmentOutcome.NEED_MORE_INFORMATION, reply.assessment!!.outcome)
        }
    }

    @Test fun retrievedHeadingsDoNotEstablishACompleteLabelAndProseCannotFillGaps() = runBlocking {
        val fake = FakeResearch(listOf(label()))
        fake.transform = { it.copy(answer = ResearchAnswer("The label is complete and this product is safe for everyone.", emptyList(), emptyList())) }
        val assessment = requireNotNull(SourcedFoodService(fake).check(id, profile, product, "Sample Rice Original").assessment)
        assertEquals(AssessmentOutcome.NEED_MORE_INFORMATION, assessment.outcome)
        assertTrue(assessment.uncertainties.any { it.contains("complete ingredients") })
        assertTrue(assessment.evidence.any { it.excerpt.contains("Ingredients: rice, salt.") })
        assertFalse(assessment.toString().contains("safe for everyone"))
    }

    @Test fun advisoryAfterContainsAndBoldHeadingsIsNotDropped() = runBlocking {
        val text = fixture("rice-label").replace("Ingredients:", "**Ingredients:**")
            .replace("Allergen information: No advisory statement is printed on this synthetic package.",
                "Contains: rice.\nMay contain: soy.\nAllergen information: Shared equipment.")
        val assessment = requireNotNull(SourcedFoodService(FakeResearch(listOf(label(text))))
            .check(id, profile, product, "Sample Rice Original").assessment)
        assertEquals(AssessmentOutcome.AVOID, assessment.outcome)
        assertTrue(assessment.findings.any { it.contains("Soy (Allergy)") })
        assertTrue(assessment.evidence.any { it.excerpt.contains("May contain: soy.") })
        assertTrue(assessment.uncertainties.any { it.contains("cross-contact") })
    }

    @Test fun conflictingLabelsShowBothSourcesAndNeverBecomeCleanVerdict() = runBlocking {
        val first = label()
        val second = label(fixture("rice-label").replace("rice, salt", "rice, milk"), "revised-label")
        val assessment = requireNotNull(SourcedFoodService(FakeResearch(listOf(first, second)))
            .check(id, profile, product, "Sample Rice Original").assessment)
        assertEquals(AssessmentOutcome.NEED_MORE_INFORMATION, assessment.outcome)
        assertTrue(assessment.uncertainties.any { it.contains("disagree") })
        assertEquals(2, assessment.evidence.map { it.sourceId }.distinct().size)
        assertTrue(assessment.findings.any { it.contains("Lactose") })
    }

    @Test fun differingCountryLabelDoesNotOverwriteExactProductEvidence() = runBlocking {
        val correct = label(fixture("rice-label").replace("rice, salt", "rice, milk"))
        val otherCountry = label(fixture("rice-label").replace("India", "Canada"), "canada-label")
        val assessment = requireNotNull(SourcedFoodService(FakeResearch(listOf(otherCountry, correct)))
            .check(id, profile, product, "Sample Rice Original").assessment)
        assertEquals(AssessmentOutcome.AVOID, assessment.outcome)
        assertEquals(1, assessment.evidence.map { it.sourceId }.distinct().size)
        assertEquals("India", assessment.country)
    }

    @Test fun advisoryOnlySourceAndAmbiguousMarketCannotBeIgnored() = runBlocking {
        val advisoryOnly = label(fixture("rice-label").replace("Ingredients: rice, salt.\n", "")
            .replace("No advisory statement is printed on this synthetic package.", "May contain soy."), "advisory-only")
        val conflicting = requireNotNull(SourcedFoodService(FakeResearch(listOf(label(), advisoryOnly)))
            .check(id, profile, product, "Sample Rice Original").assessment)
        assertEquals(AssessmentOutcome.NEED_MORE_INFORMATION, conflicting.outcome)
        assertTrue(conflicting.findings.any { it.contains("Soy") })
        assertEquals(2, conflicting.evidence.map { it.sourceId }.distinct().size)
        val markets = label(fixture("rice-label").replace("Country: India", "Country: India and Canada")
            .replace("rice, salt", "rice, milk"))
        val ambiguous = requireNotNull(SourcedFoodService(FakeResearch(listOf(markets)))
            .check(id, profile, product, "Sample Rice Original").assessment)
        assertEquals(AssessmentOutcome.NEED_MORE_INFORMATION, ambiguous.outcome)
    }

    @Test fun pastedLabelsNeedCompletenessAndUnknownAliasesOrBrokenUnicodeStayUncertain() = runBlocking {
        for (text in listOf("Ingredients: rice, [unclear]", "Ingredients: rice, lecithin", "Ingredients: rice, E322",
            "Ingredients: rice, mіlk", "Ingredients: rice, �", "Ingredients: rice, ...", "Ingredients: rice, [salt",
            "Ingredients: rice, flavor unavailable", "Ingredients: biscuit, rice", "Ingredients: none")) {
            val fake = FakeResearch()
            val assessment = requireNotNull(SourcedFoodService(fake).check(id, profile, product.copy(completeLabel = true), text).assessment)
            assertEquals(text, AssessmentOutcome.NEED_MORE_INFORMATION, assessment.outcome)
            assertTrue(fake.requests.isEmpty())
        }
        val complete = requireNotNull(SourcedFoodService(FakeResearch()).check(id, profile,
            product.copy(completeLabel = true), "Ingredients: rice, salt.").assessment)
        assertEquals(AssessmentOutcome.NO_LISTED_CONFLICT_FOUND, complete.outcome)
        assertNull(complete.origin.retrieval)
        val incomplete = requireNotNull(SourcedFoodService(FakeResearch()).check(id, profile, product, "Ingredients: rice, salt.").assessment)
        assertEquals(AssessmentOutcome.NEED_MORE_INFORMATION, incomplete.outcome)
    }

    @Test fun unicodeCompatibilityAndInvisibleCharactersCannotHideKnownAllergens() {
        for (text in listOf("Ingredients: rice, ｍｉｌｋ.", "Ingredients: rice, m\u200Bilk.")) {
            val result = FoodEvidence.assess(text, profile, ResultOrigin(id.id, 7), "Provided ingredients", true)
            assertEquals(AssessmentOutcome.AVOID, result.outcome)
        }
    }

    @Test fun lactoseFreeDairyAndCaseinDifferFromMilkAllergyEvenWithLegacyAliases() {
        val lactose = profile.copy(restrictions = listOf(profile.restrictions.first()))
        val milk = profile.copy(restrictions = listOf(Restriction("Milk", "Allergy")))
        for (text in listOf("Ingredients: lactose-free milk, rice.", "Ingredients: lactose‑free milk, rice.",
            "Ingredients: casein, rice.", "Ingredients: ghee, rice.")) {
            assertEquals(text, AssessmentOutcome.NEED_MORE_INFORMATION,
                FoodEvidence.assess(text, lactose, ResultOrigin(id.id, 7), "Provided ingredients", true).outcome)
            assertEquals(text, AssessmentOutcome.AVOID,
                FoodEvidence.assess(text, milk, ResultOrigin(id.id, 7), "Provided ingredients", true).outcome)
        }
        assertEquals(AssessmentOutcome.AVOID, FoodEvidence.assess("Ingredients: lactose-free milk, whey.", lactose,
            ResultOrigin(id.id, 7), "Provided ingredients", true).outcome)
        assertTrue(FoodEvidence.conflicts("coconut cream, cocoa butter, rice milk", milk).isEmpty())
    }

    @Test fun unknownRestrictionDictionaryCannotProduceCleanResultAndCustomAliasesRemainActive() {
        val custom = profile.copy(restrictions = listOf(Restriction("My trigger", "Allergy", "sodium caseinate")))
        assertEquals(AssessmentOutcome.AVOID, FoodEvidence.assess("Ingredients: rice, sodium caseinate.", custom,
            ResultOrigin(id.id, 7), "Provided ingredients", true).outcome)
        assertEquals(AssessmentOutcome.NEED_MORE_INFORMATION, FoodEvidence.assess("Ingredients: rice, salt.", custom,
            ResultOrigin(id.id, 7), "Provided ingredients", true).outcome)
        assertTrue(FoodEvidence.conflicts("soy", profile.copy(restrictions = listOf(Restriction("Soy", enabled = false)))).isEmpty())
    }

    @Test fun callerProseCannotContradictRetrievedIngredientQuotation() {
        val label = label(fixture("rice-label").replace("rice, salt", "rice, milk"))
        val origin = origin(label)
        val reference = FoodEvidence.reference(origin.retrieval!!, label, "Ingredients: rice, milk.")
        val assessment = FoodEvidence.assess("Ingredients: rice, salt.", profile, origin, "Sample Rice Original", true,
            evidence = listOf(reference))
        assertEquals(AssessmentOutcome.AVOID, assessment.outcome)
        assertThrows(IllegalArgumentException::class.java) {
            FoodEvidence.assess("Ingredients: rice, salt.", profile, origin, "Sample Rice Original", true,
                evidence = listOf(reference.copy(sourceId = "fabricated")))
        }
    }

    @Test fun fabricatedResearchCitationsFailBeforeAnyRecipeCanBeReturned() {
        val fake = FakeResearch(listOf(source()))
        fake.transform = { result -> result.copy(answer = ResearchAnswer("A friendly acknowledgement.", listOf(
            ResearchClaim("Invented ingredients.", "source", listOf(EvidenceReference(result.snapshot.id, "invented", "Invented ingredients.")))
        ), emptyList())) }
        assertThrows(RuntimeException::class.java) {
            runBlocking { SourcedFoodService(fake).recipe(id, noRestrictions, FeatureConversation(), "tiramisu recipe") }
        }
    }

    @Test fun retrievalFailureAndCancellationPreservePreviousRecipeAndDoNotCreateFallback() {
        val saved = parse()
        val conversation = conversation(saved)
        for (failure in listOf(IOException("Synthetic retrieval failure"), CancellationException("Stopped"))) {
            val fake = FakeResearch().also { it.failure = failure }
            val thrown = assertThrows(failure.javaClass) {
                runBlocking { SourcedFoodService(fake).recipe(id, profile, conversation, "No coconut") }
            }
            assertSame(failure, thrown)
            assertSame(saved, conversation.result!!.recipe)
            assertEquals(1, fake.requests.size)
        }
        val fake = FakeResearch().also { it.failure = IOException("Synthetic retrieval failure") }
        assertThrows(IOException::class.java) {
            runBlocking { SourcedFoodService(fake).check(id, profile, product, "Sample Rice Original") }
        }
    }

    @Test fun recipeFollowUpParsingKeepsNegationWithinItsClause() {
        assertEquals(listOf("soy"), RecipeFollowUp.exclusions("pasta without soy, with tomato"))
        assertEquals(listOf("coconut"), RecipeFollowUp.exclusions("No coconut and make two servings"))
        assertEquals(2.0, RecipeFollowUp.servings("Make two servings")!!, 0.0)
        assertEquals(2.0, RecipeFollowUp.servings("two servings")!!, 0.0)
        assertTrue(RecipeFollowUp.supportedEdit("No coconut and make two servings"))
        assertFalse(RecipeFollowUp.supportedEdit("Make it sweeter"))
        assertTrue(RecipeFollowUp.matchesDish(source(), "tiramisu recipe for two"))
    }
}
