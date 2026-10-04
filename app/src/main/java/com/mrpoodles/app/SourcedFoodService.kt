package com.mrpoodles.app

import java.util.UUID

data class FoodChatReply(val message: String, val recipe: Recipe? = null, val assessment: FoodAssessment? = null,
    val subject: String = "", val exclusions: List<String> = emptyList(), val workout: Workout? = null)

object RecipeFollowUp {
    private val words = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
    fun servings(text: String): Double? {
        val found = Regex("(?i)\\b(?:(?:make|for|serves?|scale to)\\s+(\\d+(?:\\.\\d+)?|${words.joinToString("|")})(?:\\s+(?:servings?|people|portions?))?|(\\d+(?:\\.\\d+)?|${words.joinToString("|")})\\s+(?:servings?|people|portions?))(?=\\s|[.!?,;]|$)").findAll(text.trim()).lastOrNull() ?: return null
        return found.groupValues[1].ifBlank { found.groupValues[2] }.lowercase().let { it.toDoubleOrNull() ?: words.indexOf(it).toDouble() }
    }
    fun exclusions(text: String): List<String> = Regex("(?i)\\b(?:no|without|exclude|avoid)\\s+([^.!?;]+)").findAll(text).flatMap { match ->
        match.groupValues[1].split(Regex("(?i)(?:,?\\s+(?:with|but|make|for|use|using)\\b|\\s+and\\s+(?:with|make|use)\\b)"), limit = 2).first()
            .split(Regex("(?i)\\s+(?:or|and)\\s+|,")).asSequence()
    }.map { it.trim().lowercase().removeSuffix(" please") }.filter { it.matches(Regex("[a-z][a-z -]{0,39}")) && servings(it) == null }.distinct().toList()
    fun isEdit(text: String) = Regex("(?i)^(?:please )?(?:make |for |serves? |scale to |no |without |exclude |avoid |use (?:hostel|home) (?:equipment|kitchen)|(?:\\d+(?:\\.\\d+)?|${words.joinToString("|")}) servings?)").containsMatchIn(text.trim())
    fun equipmentMode(text: String): String? = Regex("(?i)\\b(hostel|home) (?:equipment|kitchen)\\b").find(text)?.groupValues?.get(1)?.let { if (it.equals("hostel", true)) "Hostel" else "Home" }

    fun supportedEdit(text: String): Boolean {
        if (servings(text) == null && exclusions(text).isEmpty() && equipmentMode(text) == null) return false
        var remaining = text.trim().replace(Regex("(?i)\\bplease\\b"), "")
        remaining = remaining.replace(Regex("(?i)\\b(?:(?:make|for|serves?|scale to)\\s+)?(?:\\d+(?:\\.\\d+)?|${words.joinToString("|")})\\s*(?:servings?|people|portions?)\\b"), "")
            .replace(Regex("(?i)\\b(?:make|for|serves?|scale to)\\s+(?:\\d+(?:\\.\\d+)?|${words.joinToString("|")})\\b"), "")
            .replace(Regex("(?i)\\buse (?:hostel|home) (?:equipment|kitchen)\\b"), "")
        exclusions(text).forEach { term -> remaining = remaining.replace(Regex("(?i)\\b${Regex.escape(term)}\\b"), "") }
        return remaining.replace(Regex("(?i)\\b(?:no|without|exclude|avoid|and|or)\\b|[\\s,.!?;]+"), "").isEmpty()
    }

    /** Strict title relevance avoids serving a salad merely because retrieval returned it. */
    fun matchesDish(source: RetrievedSource, request: String): Boolean {
        if (request.startsWith("https://")) return source.url == request
        val dish = request.replace(Regex("(?i)^(?:please )?make (?=[a-z])"), "")
            .replace(Regex("(?i)^recipes? for (?=[a-z])"), "")
            .replace(Regex("(?i)\\b(?:\\d+(?:\\.\\d+)?|${words.joinToString("|")})\\s+(?:servings?|people|portions?)\\b"), "")
            .split(Regex("(?i)\\b(?:without|no|exclude|avoid|for|with|using|use)\\b"), limit = 2).first()
        val ignored = setOf("i", "want", "would", "like", "a", "an", "the", "please", "find", "me", "give", "show", "recipe", "recipes", "published", "easy", "quick", "can", "you", "get", "some")
        val tokens = FoodRules.normalize(dish).split(' ').filter { it.isNotBlank() && it !in ignored }
        val heading = source.excerpt.lines().firstOrNull { it.trim().startsWith("# ") }?.removePrefix("# ")
        return tokens.isNotEmpty() && tokens.all { FoodRules.contains(source.title, it) } &&
            (heading == null || tokens.all { FoodRules.contains(heading, it) })
    }
}

class SourcedFoodService(private val model: CloudModel) {
    private fun constraints(profile: Profile) = ResearchConstraints(
        restrictions = profile.restrictions.filter { it.enabled }.map { ResearchRestriction(it.name, it.kind, it.aliases, it.notes) },
        diet = profile.diet, equipment = profile.environment().equipment)

    private suspend fun lookup(id: RequestIdentity, profile: Profile, task: String, subject: String, url: String? = null,
        brand: String = "", variant: String = "", country: String = ""): ResearchResponse {
        check(profile.sourceLookupConsent) { "Please review source lookup before sending food terms to search providers." }
        require(subject.length <= 160) { "Please keep the dish or product name under 160 characters." }
        val request = ResearchRequest(id.id, id.profileRevision, task, subject, brand, variant, country, constraints(profile),
            allowExternalModel = profile.externalModelConsent, url = url)
        // /v2/research composes the shared companion personality for every provider. Its prose is
        // deliberately not used as ingredient, quantity, label, or substitution evidence here.
        return model.research(request).validate(request)
    }

    private fun origin(id: RequestIdentity, text: String, sources: List<RetrievedSource>, assertions: List<UserAssertion> = emptyList()): ResultOrigin = ResultOrigin(id.id, id.profileRevision,
        assertions + UserAssertion(text), RetrievalSnapshot(UUID.randomUUID().toString(), id.id,
            sources.distinctBy { it.url to it.excerpt }.take(9).mapIndexed { index, source -> source.copy(id = "source-$index") }))

    private fun rebind(recipe: Recipe, id: RequestIdentity, text: String, assertions: List<UserAssertion>): Recipe {
        val value = requireNotNull(recipe.sourced)
        val old = requireNotNull(value.origin.retrieval)
        val snapshot = old.copy(id = UUID.randomUUID().toString(), requestId = id.id)
        val origin = ResultOrigin(id.id, id.profileRevision, assertions + UserAssertion(text), snapshot)
        fun refs(refs: List<EvidenceReference>) = refs.map { it.copy(snapshotId = snapshot.id) }
        fun ingredient(part: NamedIngredient) = part.copy(evidence = refs(part.evidence))
        return recipe.copy(id = UUID.randomUUID().toString(), profileRevision = id.profileRevision, origin = origin, sourced = value.copy(origin = origin,
            ingredients = value.ingredients.map(::ingredient), instructionEvidence = refs(value.instructionEvidence), metadataEvidence = refs(value.metadataEvidence),
            adaptation = value.adaptation?.copy(substitutions = value.adaptation.substitutions.map {
                it.copy(original = ingredient(it.original), replacement = ingredient(it.replacement), evidence = refs(it.evidence))
            })))
    }

    suspend fun recipe(id: RequestIdentity, profile: Profile, conversation: FeatureConversation, text: String): FoodChatReply {
        val edit = RecipeFollowUp.isEdit(text) && conversation.subject.isNotBlank()
        val subject = if (edit) conversation.subject else text.trim()
        // A failed new-dish search deliberately retains the older result in the UI. That result
        // must not become the subject of a later serving/equipment edit for the new dish.
        val previous = conversation.result?.recipe?.takeIf { old -> edit && old.sourced?.let { data ->
            data.origin.retrieval?.sources?.singleOrNull { it.id == data.originalSourceId }
                ?.let { RecipeFollowUp.matchesDish(it, subject) } == true
        } == true }
        val exclusions = ((if (edit) conversation.exclusions else emptyList()) + RecipeFollowUp.exclusions(text)).distinct()
        if (edit && !RecipeFollowUp.supportedEdit(text)) return FoodChatReply(
            "I don't have a verified change for that instruction yet. Would you like to change the servings, exclude an ingredient, or use hostel or home equipment?", subject = subject, exclusions = conversation.exclusions)
        val assertions = if (edit) listOf(UserAssertion(subject)) + previous?.sourced?.origin?.assertions.orEmpty() +
            conversation.messages.filter { it.role in listOf("You", "user") }.takeLastWhile { RecipeFollowUp.isEdit(it.text) }.map { UserAssertion(it.text) } else emptyList()
        val history = (if (edit) listOf(subject) else emptyList()) + assertions.map { it.text } + text
        val requestedServings = history.asReversed().firstNotNullOfOrNull(RecipeFollowUp::servings) ?: if (edit) previous?.sourced?.servings else null
        require(requestedServings == null || requestedServings.isFinite() && requestedServings in 0.25..100.0) { "Please choose between 0.25 and 100 servings." }
        val effective = history.asReversed().firstNotNullOfOrNull(RecipeFollowUp::equipmentMode)?.let { profile.copy(mode = it) } ?: profile
        fun finish(recipe: Recipe): Recipe {
            val constrained = recipe.copy(sourced = recipe.sourced!!.copy(excludedIngredients = exclusions))
            return requestedServings?.let { if (it != constrained.sourced!!.servings) SourcedRecipeRules.scale(constrained, it) else constrained } ?: constrained
        }
        if (edit && previous?.sourced != null) {
            val scaled = finish(rebind(previous, id, text, assertions))
            if (SourcedRecipeRules.validate(scaled, effective).isEmpty()) return FoodChatReply(
                "Of course. I've kept your current recipe and updated this request. The original sources and their dates are still shown.", scaled, subject = subject, exclusions = exclusions)
        }
        val directUrl = subject.takeIf { it.startsWith("https://") }
        val query = if (directUrl == null) subject else "Published recipe ingredients and method"
        val response = lookup(id, effective, "recipe", query, directUrl)
        var origin = origin(id, text, response.snapshot.sources, assertions)
        var recipes = origin.retrieval!!.sources.filter { RecipeFollowUp.matchesDish(it, subject) }.mapNotNull { PublishedRecipeParser.parse(it, origin) }
        fun acceptable(values: List<Recipe>): Recipe? = values.map(::finish)
            .firstOrNull { SourcedRecipeRules.validate(it, effective).isEmpty() }
        acceptable(recipes)?.let { return FoodChatReply("Here's a published recipe we can look at together. Its quantities and method come from the linked page; check actual packages before cooking.", it, subject = subject, exclusions = exclusions) }
        val base = recipes.firstOrNull() ?: return FoodChatReply("I couldn't verify a matching recipe with complete ingredient quantities, servings and a method. Could you share a different published recipe link? Your previous recipe stays here.", subject = subject, exclusions = exclusions)
        val blocked = base.sourced!!.ingredients.filter { FoodEvidence.conflicts(it.name + " " + it.preparation, effective).isNotEmpty() || exclusions.any { term -> FoodEvidence.excluded(it.name + " " + it.preparation, term) } }
        if (blocked.isNotEmpty()) {
            val guideQuery = (query.take(65) + " replace " + blocked.take(2).joinToString(" ") { it.name.take(24) } + " equal amounts substitutes").take(160)
            val guidance = lookup(id, effective, "recipe", guideQuery)
            origin = origin(id, text, response.snapshot.sources + guidance.snapshot.sources, assertions)
            recipes = origin.retrieval!!.sources.filter { RecipeFollowUp.matchesDish(it, subject) }.mapNotNull { PublishedRecipeParser.parse(it, origin) }
            val rebound = recipes.firstOrNull { it.sourced?.originalSourceId == origin.retrieval!!.sources.firstOrNull { s -> s.url == base.sourced!!.origin.retrieval!!.sources.first { source -> source.id == base.sourced!!.originalSourceId }.url }?.id }
            val adapted = rebound?.let { SupportedSubstitutions.adapt(it, origin, effective, exclusions) }?.let(::finish)
            if (adapted != null && SourcedRecipeRules.validate(adapted, effective).isEmpty()) return FoodChatReply(
                "I've adapted the no-cook recipe using the linked substitution guidance. This is an adaptation, not the author's original recipe. Please review each change.", adapted, subject = subject, exclusions = exclusions)
            acceptable(recipes)?.let { return FoodChatReply("I found a separately published version that fits the reviewed ingredients. It is linked as its own recipe, not presented as the original author's adaptation.", it, subject = subject, exclusions = exclusions) }
        }
        val issues = SourcedRecipeRules.validate(finish(base), effective)
        return FoodChatReply("Let's pause on this recipe. ${issues.take(3).joinToString(" ")} I don't have enough evidence for a suitable change. Could you share another recipe or the missing ingredient or equipment details?", subject = subject, exclusions = exclusions)
    }

    suspend fun check(id: RequestIdentity, profile: Profile, conversation: FeatureConversation, text: String): FoodChatReply {
        val suppliedLabel = text.startsWith("ingredients", true) || text.contains(',') || conversation.completeLabel
        if (suppliedLabel) {
            val origin = ResultOrigin(id.id, id.profileRevision, listOf(UserAssertion(text)))
            val result = FoodEvidence.assess(text, profile, origin, "Provided ingredients", conversation.completeLabel,
                brand = conversation.brand, variant = conversation.variant, country = conversation.country)
            return FoodChatReply("I'm right here. This check uses the ingredients you supplied, not a verified internet label.", assessment = result, subject = "Provided ingredients")
        }
        val subject = if (text.equals("check again", true) && conversation.subject.isNotBlank()) conversation.subject else text.trim()
        if (conversation.foodKind == "dish") {
            val response = lookup(id, profile, "recipe", subject)
            val origin = origin(id, text, response.snapshot.sources)
            val recipe = origin.retrieval!!.sources.filter { RecipeFollowUp.matchesDish(it, subject) }.firstNotNullOfOrNull { PublishedRecipeParser.parse(it, origin) }
            val refs = recipe?.sourced?.ingredients.orEmpty().flatMap { it.evidence }
            val information = recipe?.sourced?.ingredients.orEmpty().joinToString(", ") { it.name + " " + it.preparation }
            return FoodChatReply("This is a typical published recipe, not the ingredients in your actual dish. Ask the kitchen or paste its full ingredient list.",
                assessment = FoodEvidence.assess(information, profile, origin, subject, false, false, refs), subject = subject)
        }
        if (listOf(conversation.brand, conversation.variant, conversation.country).any(String::isBlank)) return FoodChatReply(
            "Which brand, exact variant and country is this? Add Product details below, or paste the complete ingredients and advisory statements.", subject = subject)
        val url = subject.takeIf { it.startsWith("https://") }
        val response = lookup(id, profile, "food_check", if (url == null) subject else "Product ingredients", url,
            conversation.brand, conversation.variant, conversation.country)
        val origin = origin(id, text, response.snapshot.sources)
        val manufacturers = origin.retrieval!!.sources.filter { it.kind == "manufacturer" }
        val matched = manufacturers.filter { source ->
            val identityText = source.title + " " + source.publisher.orEmpty()
            val countryVerified = FoodRules.contains(source.title, conversation.country) ||
                Regex("(?im)^[\\t ]*(?:#{1,6}[\\t ]*)?(?:\\*\\*)?(?:country|market|region)(?:\\*\\*)?:[\\t ]*(?:\\*\\*)?${Regex.escape(conversation.country.trim())}[.!]?[\\t ]*$").containsMatchIn(source.excerpt)
            FoodRules.contains(identityText, conversation.brand) && FoodRules.contains(source.title, conversation.variant) &&
                countryVerified &&
                (url?.let { it == source.url } ?: RecipeFollowUp.matchesDish(source, subject))
        }
        // If identity is not established, show the available evidence only as an uncertain candidate.
        val labels = (matched.ifEmpty { manufacturers }).map { source ->
            Label(source, sections(source.excerpt, "ingredients?"), sections(source.excerpt,
                "allergen(?: advice| information| statement|s)?|allergy advice|contains|may contain|advisory(?: statements?)?"))
        }
        val supported = labels.filter { it.ingredients.isNotEmpty() }
        val conflicting = supported.flatMap { it.ingredients }.map(FoodRules::normalize).distinct().size > 1 ||
            labels.filter { it.advisories.isNotEmpty() }.map { FoodRules.normalize(it.advisories.joinToString("\n")) }.distinct().size > 1
        // An advisory-only manufacturer page can contradict an ingredient page. Never drop it
        // just because a neighbouring source has a more complete-looking Ingredients section.
        val reviewed = labels.filter { it.ingredients.isNotEmpty() || it.advisories.isNotEmpty() }
        val refs = reviewed.flatMap { item -> (item.ingredients + item.advisories).map { FoodEvidence.reference(origin.retrieval!!, item.source, it) } }
        val info = refs.joinToString("\n") { it.excerpt }
        // The current backend marks extraction completeness as unverified. Section headings alone
        // cannot turn an excerpt into the complete current package label.
        val complete = reviewed.isNotEmpty() && reviewed.all { it.ingredients.size == 1 && it.advisories.isNotEmpty() &&
            it.source.completeness == "complete" && it.source.excerpt.length < 12000 }
        val result = FoodEvidence.assess(info, profile, origin, subject, complete,
            matched.isNotEmpty() && !conflicting, refs, conversation.brand, conversation.variant, conversation.country)
        return FoodChatReply(if (conflicting) "The retrieved labels disagree. Let's confirm the current package before deciding." else "Here's what I could check from the linked manufacturer information. Missing details stay uncertain.",
            assessment = if (conflicting) result.copy(uncertainties = result.uncertainties + "Retrieved ingredient statements disagree.") else result, subject = subject)
    }

    private data class Label(val source: RetrievedSource, val ingredients: List<String>, val advisories: List<String>)

    private fun sections(text: String, label: String): List<String> {
        val headings = Regex("(?im)^[\\t ]*(?:#{1,6}[\\t ]*)?(?:\\*\\*)?(ingredients?|allergen(?: advice| information| statement|s)?|allergy advice|contains|may contain|advisory(?: statements?)?|nutrition[^:\\n]*|storage|directions|product[^:\\n]*|country|notes?)(?:\\*\\*)?[\\t ]*(?::(?:\\*\\*)?|$)").findAll(text).toList()
        return headings.mapIndexedNotNull { index, match ->
            if (!Regex("(?i)(?:$label)").matches(match.groupValues[1])) return@mapIndexedNotNull null
            val end = headings.getOrNull(index + 1)?.range?.first ?: text.length
            if (text.substring(match.range.last + 1, end).isBlank()) return@mapIndexedNotNull null
            text.substring(match.range.first, end).trim().takeIf { it.length in 5..4000 }
        }
    }
}
