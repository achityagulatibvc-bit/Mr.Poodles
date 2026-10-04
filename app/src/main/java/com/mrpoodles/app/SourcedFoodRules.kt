package com.mrpoodles.app

import kotlin.math.abs
import java.util.UUID
import java.text.Normalizer

/** Evidence is copied from extraction, never reconstructed from an LLM's short research notes. */
object FoodEvidence {
    fun reference(snapshot: RetrievalSnapshot, source: RetrievedSource, quote: String) =
        EvidenceReference(snapshot.id, source.id, quote)

    fun valid(origin: ResultOrigin, references: List<EvidenceReference>): Boolean {
        val snapshot = origin.retrieval ?: return references.isEmpty()
        if (snapshot.requestId != origin.requestId || snapshot.id.isBlank() || snapshot.sources.size !in 1..9 ||
            snapshot.sources.map { it.id }.distinct().size != snapshot.sources.size) return false
        if (snapshot.sources.any { it.id.isBlank() || !publicResearchUrl(it.url) || it.excerpt.length !in 100..12000 }) return false
        return references.all { ref -> ref.snapshotId == snapshot.id && ref.excerpt.isNotBlank() &&
            snapshot.sources.singleOrNull { it.id == ref.sourceId }?.excerpt?.contains(ref.excerpt) == true }
    }

    private val dairy = listOf("milk", "milk powder", "whey", "casein", "caseinate", "caseinates", "lactalbumin", "lactoglobulin", "cream", "butter", "buttermilk", "cheese", "mascarpone", "paneer", "yogurt", "yoghurt", "curd", "ghee", "lactose")
    private val soy = listOf("soy", "soya", "soybean", "soybeans", "tofu", "tempeh", "edamame", "miso", "shoyu", "tamari", "soy lecithin")
    private val lactoseUnknown = listOf("casein", "caseinate", "caseinates", "lactalbumin", "lactoglobulin", "ghee")
    // Normalize compatibility forms for matching, but retain the original text in every quote.
    private fun matchingText(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace(Regex("\\p{Cf}"), "").replace(Regex("[‐‑‒–—−]"), "-")

    fun conflicts(text: String, profile: Profile): List<String> = buildList {
        profile.restrictions.filter { it.enabled }.forEach { restriction ->
            val name = FoodRules.normalize(restriction.name)
            val milkRestriction = name in listOf("milk", "dairy", "lactose", "milk allergy", "lactose intolerance")
            val lactoseIntolerance = name in listOf("lactose", "lactose intolerance") && !restriction.kind.equals("Allergy", true)
            var reviewed = matchingText(text).let { if (milkRestriction) plantNamesRemoved(it) else it }
            // A claim about one ingredient must not hide ordinary milk/whey elsewhere in the label.
            if (lactoseIntolerance) {
                reviewed = reviewed.replace(Regex("(?i)\\blactose[ -]free\\s+(?:milk|cream|yogurt|yoghurt|cheese)\\b"), "claimed ingredient")
                // These names establish milk derivation, not a known lactose amount. Even historical
                // default aliases must not turn them into a milk-allergy diagnosis.
                lactoseUnknown.forEach { reviewed = reviewed.replace(Regex("(?i)\\b${Regex.escape(it)}\\b"), "dairy derivative") }
            }
            val aliases = (listOf(restriction.name) + restriction.aliases.split(',') + when (name) {
                "milk", "dairy", "lactose", "milk allergy", "lactose intolerance" -> if (lactoseIntolerance) dairy - lactoseUnknown else dairy
                "soy", "soya", "soy allergy", "soy intolerance" -> soy
                else -> emptyList()
            }).map(String::trim).filter(String::isNotBlank).distinct()
            val matches = aliases.filter { term ->
                // Negative label claims are uncertainty, never positive ingredient findings.
                val withoutClaims = reviewed.replace(Regex("(?i)\\b${Regex.escape(term)}[ -]free\\b|\\b(?:no|free from|does not contain)\\s+${Regex.escape(term)}\\b"), "claim")
                FoodRules.contains(withoutClaims, term)
            }
            if (matches.isNotEmpty()) add("${restriction.name} (${restriction.kind}): ${matches.joinToString()}")
        }
        val meat = listOf("chicken", "beef", "pork", "fish", "prawn", "gelatin", "gelatine", "egg", "eggs")
        if (profile.diet in listOf("Vegetarian", "Vegan") && meat.any { FoodRules.contains(matchingText(text), it) }) add("Conflicts with your ${profile.diet.lowercase()} preference.")
        if (profile.diet == "Vegan") {
            val reviewed = plantNamesRemoved(text)
            if ((dairy + "honey").any { FoodRules.contains(reviewed, it) }) add("A vegan replacement needs verification.")
        }
    }.distinct()

    private fun plantNamesRemoved(text: String) = text.replace(Regex("(?i)\\b(?:coconut|oat|almond|rice|soy|soya|cashew|pea|hemp|peanut|cocoa|cacao|shea|sunflower)[ -](?:milk|cream|butter)\\b"), "plant ingredient")

    fun excluded(text: String, term: String): Boolean = FoodRules.contains(matchingText(text), term) || when (FoodRules.normalize(term)) {
        "soy", "soya" -> soy.any { FoodRules.contains(matchingText(text), it) }
        "milk", "dairy" -> dairy.any { FoodRules.contains(plantNamesRemoved(matchingText(text)), it) }
        else -> false
    }

    fun uncertainties(text: String, profile: Profile): List<String> = buildList {
        if (Regex("(?i)\\[unclear]|\\b(?:unclear|unknown|unreadable|illegible|unavailable|incomplete|missing|not (?:available|provided|listed)|flavou?rings?|flavou?rs?|spices|spice blend|seasoning|vegetable protein|tvp|emulsifiers?|stabili[sz]ers?|lecithin)\\b|\\bE[ -]?\\d{3,4}\\b|\\.\\.\\.|…|\\?{2,}").containsMatchIn(matchingText(text)))
            add("Some ingredients or aliases need clarification from the manufacturer or kitchen.")
        if (text.any { it.code > 127 && (it.isLetter() || it == '\uFFFD' || Character.getType(it) == Character.FORMAT.toInt() || Character.getType(it) == Character.NON_SPACING_MARK.toInt()) })
            add("Some label text needs translation or contains unclear Unicode characters; the ingredient match may be incomplete.")
        if (text.count { it == '(' } != text.count { it == ')' } || text.count { it == '[' } != text.count { it == ']' })
            add("A compound ingredient or label section appears cut off; please provide the full text.")
        if (Regex("(?i)\\b(?:biscuits?|cookies?|lady[ -]?fingers?|sponge[ -]fingers?|sponge cake|chocolate chips|chocolate spread|margarine|creamer)\\b").findAll(text).any {
                !text.substring(it.range.last + 1).trimStart().startsWith('(')
            }) add("A prepared ingredient needs its own full ingredient and advisory label.")
        if (Regex("(?i)may contain|may be present|shared (?:equipment|facility)|(?:made|manufactured|processed) in|cross.contact|traces of").containsMatchIn(text))
            add("An advisory statement mentions possible cross-contact.")
        if (profile.restrictions.any { it.enabled && FoodRules.normalize(it.name) !in listOf("milk", "dairy", "lactose", "milk allergy", "lactose intolerance", "soy", "soya", "soy allergy", "soy intolerance") })
            add("Some restrictions do not have a complete reviewed alias dictionary; absence of a name is not proof of absence.")
        if (Regex("(?i)\\blactose[ -]free\\b").containsMatchIn(matchingText(text)) && profile.restrictions.any { it.enabled && FoodRules.normalize(it.name) in listOf("lactose", "lactose intolerance") })
            add("A lactose-free claim is not a milk-allergy claim. Confirm the product's suitability for your intolerance.")
        if (Regex("(?i)\\b(?:casein(?:ates?)?|lactalbumin|lactoglobulin|ghee)\\b").containsMatchIn(matchingText(text)) && profile.restrictions.any { it.enabled && it.kind.equals("Intolerance", true) && FoodRules.normalize(it.name) in listOf("lactose", "lactose intolerance") })
            add("The lactose content of this dairy-derived ingredient is not established by its name.")
    }.distinct()

    fun assess(text: String, profile: Profile, origin: ResultOrigin, product: String, complete: Boolean,
        identityVerified: Boolean = true, evidence: List<EvidenceReference> = emptyList(),
        brand: String = "", variant: String = "", country: String = ""): FoodAssessment {
        require(FoodEvidence.valid(origin, evidence)) { "The food evidence no longer matches its source." }
        // Retrieved quotations, not caller/model prose, are authoritative for a sourced verdict.
        val reviewed = if (origin.retrieval == null) text else evidence.joinToString("\n") { it.excerpt }
        val findings = conflicts(reviewed, profile)
        val uncertainties = buildList {
            if (!complete) add("The complete ingredients and advisory statements are not confirmed.")
            if (!identityVerified) add("Confirm the exact brand, variant and country; a typical recipe is not your product label.")
            if (reviewed.isBlank()) add("No ingredient evidence is available.")
            if (Regex("(?i)^(?:ingredients?\\s*:?\\s*)?(?:none|n/?a|not applicable|[ -]*)$").matches(reviewed.trim()))
                add("Please paste actual ingredient information rather than an empty label statement.")
            addAll(uncertainties(reviewed, profile))
            if (origin.retrieval != null && evidence.isEmpty()) add("No retrieved ingredient quotation supports this assessment.")
        }
        val outcome = when {
            identityVerified && findings.isNotEmpty() -> AssessmentOutcome.AVOID
            uncertainties.isNotEmpty() -> AssessmentOutcome.NEED_MORE_INFORMATION
            else -> AssessmentOutcome.NO_LISTED_CONFLICT_FOUND
        }
        return FoodAssessment(origin, outcome, product, brand.ifBlank { null }, variant.ifBlank { null }, country.ifBlank { null },
            labelVersion = if (origin.retrieval == null) "User-supplied information" else "Retrieved ${origin.retrieval.sources.filter { s -> evidence.any { it.sourceId == s.id } }.map { it.retrievedAt }.distinct().joinToString()}; package version unverified",
            findings = findings, evidence = evidence, uncertainties = uncertainties + "No listed conflict is not a guarantee of safety; ingredients and cross-contact can change.")
    }
}

/** Conservative parser for explicit sections and a bounded terminal Markdown ingredient-list layout. */
object PublishedRecipeParser {
    private val fractions = mapOf('¼' to "1/4", '½' to "1/2", '¾' to "3/4", '⅓' to "1/3", '⅔' to "2/3", '⅛' to "1/8", '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8")
    private fun clean(value: String) = value.trim().replace(Regex("^(?:(?:#{1,6}|[-*•])[\\t ]+)+"), "").replace("**", "").trim()
    private fun bullet(value: String) = Regex("^[\\t ]*[-*•][\\t ]+").containsMatchIn(value)
    private fun continuation(value: String) = Regex("(?i)^(?:[,()]|finely |roughly |chopped\\b|sliced\\b|(?:very )?thinly sliced\\b|drained\\b|rinsed\\b|softened\\b|melted\\b|made with\\b|as many colou?rs\\b|we used\\b|diced as finely as\\b|juiced\\b)")
        .containsMatchIn(clean(value))
    private fun dressingHeading(value: String) = clean(value).matches(Regex("(?i)for (?:the )?dressing:?"))

    /** Work backwards from Nutrition/Method, never search article tips for ingredient-looking rows.
     * An unknown bullet is retained so parsing fails rather than silently dropping an ingredient.
     */
    private fun terminalIngredientStart(lines: List<String>, end: Int): Int? {
        var cursor = end - 1
        while (cursor >= 0 && lines[cursor].isBlank()) cursor--
        val last = cursor
        if (last < 0 || !(bullet(lines[last]) || continuation(lines[last]))) return null
        while (cursor >= 0) {
            val line = lines[cursor]
            if (line.isBlank() || bullet(line) || continuation(line) || dressingHeading(line)) cursor-- else break
        }
        // A malformed unbulleted row inside a list must not make us accept only its suffix.
        val beforeBoundary = (cursor - 1 downTo 0).firstOrNull { lines[it].isNotBlank() }
        if (beforeBoundary != null && (bullet(lines[beforeBoundary]) || continuation(lines[beforeBoundary])) &&
            (lines[cursor].trimStart().startsWith('#') || (beforeBoundary + 1 until cursor).none { lines[it].isBlank() })) return null
        val first = (cursor + 1..last).firstOrNull { lines[it].isNotBlank() } ?: return null
        if (!bullet(lines[first])) return null
        val quantified = (first..last).count { bullet(lines[it]) && ingredient(lines[it], "probe", emptyList())?.amount != null }
        return first.takeIf { quantified >= 2 }
    }
    private fun number(value: String): Double = value.trim().split(Regex("\\s+")).sumOf { piece ->
        if ('/' in piece) piece.split('/').let { it[0].toDouble() / it[1].toDouble() } else piece.toDouble()
    }
    private fun expanded(value: String): String = buildString {
        value.forEach { c -> if (c in fractions) { if (isNotEmpty() && last().isDigit()) append(' '); append(fractions.getValue(c)) } else append(c) }
    }
    internal fun ingredient(line: String, key: String, evidence: List<EvidenceReference>): NamedIngredient? {
        val text = expanded(clean(line))
        val numeric = "(?:\\d+\\s+\\d+/\\d+|\\d+/\\d+|\\d+(?:\\.\\d+)?)"
        val match = Regex("^($numeric)(.*)$").find(text)
        if (match == null) return if (Regex("(?i)^(?:a )?(?:pinch|drizzle|small pack|handful)\\b|\\b(?:to taste|for dusting|for greasing|to serve)$").containsMatchIn(text))
            NamedIngredient(key, clean(line), evidence = evidence) else null
        val amount = runCatching { number(match.groupValues[1]) }.getOrNull() ?: return null
        if (!amount.isFinite() || amount <= 0 || amount > 100000) return null
        val unitPattern = Regex("(?i)^(kg|grams?|g|ml|cl|litres?|liters?|l|tablespoons?|tbsp|teaspoons?|tsp|cups?|ounces?|oz|pounds?|lbs?)\\b")
        var rawRest = match.groupValues[2]
        var rest = rawRest.trim()
        var unit = unitPattern.find(rest)?.value
        if (unit != null) rest = rest.removePrefix(unit).trim()
        if (Regex("(?i)^(?:to|x|×)\\s*\\d").containsMatchIn(rest)) return null
        var amountMax: Double? = null
        val range = Regex("^[-–]\\s*($numeric)(.*)$").find(rest)
        if (range != null) {
            amountMax = runCatching { number(range.groupValues[1]) }.getOrNull() ?: return null
            if (!amountMax.isFinite() || amountMax <= amount || amountMax > 100000) return null
            rawRest = range.groupValues[2]
            rest = rawRest.trim()
            val upperUnit = unitPattern.find(rest)?.value
            // No unsupported mixed-unit conversion or guessed midpoint.
            if (unit != null && upperUnit != null && !unit.equals(upperUnit, true)) return null
            if (upperUnit != null) rest = rest.removePrefix(upperUnit).trim()
            unit = unit ?: upperUnit
        }
        if (unit == null && rawRest.firstOrNull()?.isWhitespace() != true) return null
        if (rest.length !in 2..220 || !rest.first().isLetter() || Regex("[<>]|https?://|\\[unclear]|\\.\\.\\.|…").containsMatchIn(rest)) return null
        return NamedIngredient(key, rest, amount, unit, evidence = evidence, amountMax = amountMax)
    }

    fun parse(source: RetrievedSource, origin: ResultOrigin): Recipe? = runCatching {
        val snapshot = requireNotNull(origin.retrieval)
        require(source.kind == "recipe" && source.excerpt.length in 100 until 12000 && publicResearchUrl(source.url))
        require(snapshot.sources.singleOrNull { it.id == source.id } == source)
        require(FoodEvidence.valid(origin, emptyList()))
        val lines = source.excerpt.lines()
        val servingPattern = Regex("(?i)(?:serves|servings|yield)\\s*:?\\s*\\d+(?:\\.\\d+)?(?:\\s+(?:servings?|people|portions?))?(?:\\s+(?:for a starter or lunch, or \\d+(?:[-–]\\d+)? with other dishes|for lunch or \\d+ as a starter))?(?:\\s+(?:Easy|More effort|A challenge))?")
        val servingsLine = lines.firstOrNull { clean(it).matches(servingPattern) } ?: return null
        val servings = Regex("\\d+(?:\\.\\d+)?").find(servingsLine)!!.value.toDouble()
        require(servings in 1.0..100.0)
        val methodAt = lines.indexOfFirst { clean(it).matches(Regex("(?i)(method|directions|preparation|instructions):?")) }
        require(methodAt > 0)
        val heading = lines.take(methodAt).indexOfFirst { clean(it).matches(Regex("(?i)ingredients:?")) }
        val nutritionAt = lines.take(methodAt).indexOfLast { clean(it).matches(Regex("(?i)nutrition\\s*:\\s*per serving(?:\\s*\\(\\d+\\))?")) }
        val ingredientEnd = if (nutritionAt >= 0 && (heading < 0 || nutritionAt > heading)) nutritionAt else methodAt
        val first = if (heading >= 0) heading + 1 else terminalIngredientStart(lines, ingredientEnd) ?: return null
        require(first in 0 until ingredientEnd)
        // Nutrition is not recipe input. Reject a second ingredient list hidden after its heading.
        if (ingredientEnd != methodAt) require(lines.subList(ingredientEnd + 1, methodAt).none {
            clean(it).matches(Regex("(?i)ingredients:?")) || bullet(it) && ingredient(it, "probe", emptyList())?.amount != null
        })
        val ingredients = mutableListOf<NamedIngredient>()
        val sectionEvidence = mutableListOf<EvidenceReference>()
        for (line in lines.subList(first, ingredientEnd)) {
            val value = clean(line)
            if (heading < 0 && dressingHeading(line)) {
                require(ingredients.isNotEmpty())
                sectionEvidence += FoodEvidence.reference(snapshot, source, line)
                continue
            }
            if (value.isBlank() || value.matches(Regex("(?i)(for (the )?[a-z ]+:?|ingredients:?|units:?)"))) continue
            val ref = FoodEvidence.reference(snapshot, source, line)
            val part = ingredient(line, "ingredient-${ingredients.size}", listOf(ref))
            if (part != null) ingredients += part
            else {
                // Unknown ingredient rows must never be quietly attached to a previous safe ingredient.
                require(ingredients.isNotEmpty() && value.length <= 160 && (!bullet(line) || line.firstOrNull()?.isWhitespace() == true) && continuation(line))
                val previous = ingredients.removeAt(ingredients.lastIndex)
                ingredients += previous.copy(preparation = (previous.preparation + " " + value).trim(), evidence = previous.evidence + ref)
            }
        }
        require(ingredients.size >= 2)
        val instructionLines = lines.drop(methodAt + 1).takeWhile { line ->
            val value = clean(line)
            !(Regex("^[\\t ]*(?:[-*•][\\t ]+)?#{1,6}[\\t ]+").containsMatchIn(line) && !value.matches(Regex("(?i)step\\s+\\d+.*"))) &&
                !Regex("(?i)^(recipe from|comments|overall rating|what are|variations|related recipes|nutrition|notes:?$|ad$)").containsMatchIn(value)
        }
        val methodText = instructionLines.joinToString("\n")
        val stepPattern = Regex("(?im)^\\s*[-#* ]*(?:step\\s+(\\d+)[.:]?|(\\d+)[.)])\\s*")
        val stepNumbers = stepPattern.findAll(methodText).map { it.groupValues[1].ifBlank { it.groupValues[2] }.toInt() }.toList()
        require(stepNumbers.isEmpty() || stepNumbers == (1..stepNumbers.size).toList())
        val chunks = methodText.split(stepPattern)
            .map(String::trim).filter(String::isNotBlank)
        require(chunks.isNotEmpty() && chunks.all { it.length in 10..4000 && source.excerpt.contains(it) && it.last() in ".!?" })
        fun minutes(label: String): Pair<Int?, EvidenceReference?> {
            val line = lines.firstOrNull { Regex("(?i)\\b(?:$label)\\s*:").containsMatchIn(it) } ?: return null to null
            // Match only this label's value, not a neighbouring Cook/Chill label on the same line.
            val field = Regex("(?i)\\b(?:$label)\\s*:\\s*((?:\\d+\\s*(?:mins?|minutes?|hrs?|hours?)\\s*)+)").find(line)?.groupValues?.get(1)
                ?: return null to FoodEvidence.reference(snapshot, source, line)
            val match = Regex("(?i)(\\d+)\\s*(mins?|minutes?|hrs?|hours?)").findAll(field).toList()
            if (match.isEmpty()) return null to null
            val value = match.sumOf {
                val number = it.groupValues[1].toLong()
                require(number in 0L..10080L)
                number * if (it.groupValues[2].startsWith("h", true)) 60L else 1L
            }
            return value.takeIf { it in 0L..10080L }?.toInt() to FoodEvidence.reference(snapshot, source, line)
        }
        val active = minutes("prep|preparation|active")
        val cooking = minutes("cook|cooking")
        val waiting = minutes("chill|chilling|wait|waiting")
        // Support an explicit metadata range, including the publisher's inline "plus ... chilling".
        // Method phrases such as "few hrs" never supply numeric bounds.
        val waitPattern = Regex("(?i)(?:\\b(?:chill|chilling|wait|waiting)\\s*:\\s*|\\bplus\\s+)(\\d+)(?:\\s*[-–]\\s*(\\d+))?\\s*(mins?|minutes?|hrs?|hours?)(?=\\s|[,.;]|$)")
        val waitRange = lines.take(methodAt).firstNotNullOfOrNull { line ->
            if (!Regex("(?i)^(?:prep|preparation|active|chill|chilling|wait|waiting)\\s*:").containsMatchIn(clean(line))) return@firstNotNullOfOrNull null
            waitPattern.find(line)?.takeIf { match ->
                !match.value.startsWith("plus", true) || Regex("(?i)^\\s+chilling\\b").containsMatchIn(line.substring(match.range.last + 1))
            }?.let { line to it }
        }
        var waitLow = waiting.first
        var waitHigh: Int? = null
        var waitRef = waiting.second
        if (waitRange != null && (waitRange.second.groupValues[2].isNotBlank() || waitRange.second.value.startsWith("plus", true))) {
            val (line, match) = waitRange
            val multiplier = if (match.groupValues[3].startsWith("h", true)) 60L else 1L
            val low = match.groupValues[1].toLong()
            val high = match.groupValues[2].takeIf(String::isNotBlank)?.toLong()
            require(low in 0L..10080L && (high == null || high in low..10080L))
            require(low * multiplier <= 10080 && (high == null || high * multiplier <= 10080))
            waitLow = (low * multiplier).toInt()
            waitHigh = high?.let { (it * multiplier).toInt() }
            waitRef = FoodEvidence.reference(snapshot, source, line)
        }
        val servingDescription = servingsLine.takeIf { Regex("(?i)\\bfor\\b").containsMatchIn(it) }
        val sourced = SourcedRecipe(origin, ingredients, chunks, servings, active.first, cooking.first, waitLow,
            originalSourceId = source.id, originalServings = servings,
            instructionEvidence = chunks.map { FoodEvidence.reference(snapshot, source, it) },
            metadataEvidence = listOf(FoodEvidence.reference(snapshot, source, servingsLine)) + sectionEvidence + listOfNotNull(active.second, cooking.second, waitRef),
            waitingMinutesMax = waitHigh, originalServingDescription = servingDescription,
            servingBasisNote = servingDescription?.let { "Quantities use the first explicitly stated $servings-serving basis; the alternative yield is not used for scaling." })
        Recipe(source.title.substringBefore(" | ").removeSuffix(" recipe"), emptyList(), "published", active.first ?: 0,
            servings.toInt(), sourced = sourced, origin = origin, profileRevision = origin.profileRevision)
    }.getOrNull()
}

object SourcedRecipeRules {
    fun validate(recipe: Recipe, profile: Profile): List<String> = buildList {
        val value = recipe.sourced ?: return@buildList
        val snapshot = value.origin.retrieval
        val original = snapshot?.sources?.singleOrNull { it.id == value.originalSourceId }?.let { PublishedRecipeParser.parse(it, value.origin)?.sourced }
        if (original == null) { add("The original complete recipe sections could not be verified."); return@buildList }
        if (recipe.origin != value.origin || recipe.profileRevision != value.origin.profileRevision) add("The recipe origin is inconsistent.")
        if (value.servings !in 0.25..100.0 || !value.servings.isFinite() || value.originalServings != original.servings) add("The serving basis needs review.")
        val originalTitle = requireNotNull(snapshot).sources.single { it.id == value.originalSourceId }.title.substringBefore(" | ").removeSuffix(" recipe")
        if (recipe.title != originalTitle) add("The recipe title no longer identifies its published source.")
        val refs = value.ingredients.flatMap { it.evidence } + value.instructionEvidence + value.metadataEvidence + value.adaptation?.substitutions.orEmpty().flatMap { it.evidence }
        if (!FoodEvidence.valid(value.origin, refs)) add("A recipe citation does not match the retrieved text.")
        if (value.ingredients.size != original.ingredients.size || value.ingredients.map { it.key } != original.ingredients.map { it.key }) add("The ingredient list differs from the original without a supported replacement.")
        val substitutions = value.adaptation?.substitutions.orEmpty()
        if ((substitutions.isNotEmpty() || value.servings != original.servings) &&
            (!recipe.aiGenerated || value.adaptation?.explanation.isNullOrBlank())) add("Recipe changes must be labeled as an adaptation of the original.")
        if (substitutions.isNotEmpty() && !SupportedSubstitutions.isNoCook(original.instructions))
            add("A heated recipe needs method-specific substitution guidance that this parser cannot verify.")
        if (substitutions.map { it.original.key }.distinct().size != substitutions.size || substitutions.any { sub -> original.ingredients.none { it == sub.original } })
            add("Substitution records do not match the original ingredient list.")
        val scale = value.servings / original.servings
        var expectedSteps = original.instructions
        value.ingredients.forEachIndexed { index, ingredient ->
            val base = original.ingredients.getOrNull(index) ?: return@forEachIndexed
            val substitution = value.adaptation?.substitutions?.singleOrNull { it.original.key == base.key }
            if (substitution == null) {
                if (ingredient.name != base.name || ingredient.unit != base.unit || ingredient.evidence != base.evidence || ingredient.preparation != base.preparation) add("An ingredient changed without substitution evidence.")
            } else {
                if (!SupportedSubstitutions.supports(substitution, value.origin) || substitution.original != base || ingredient.copy(amount = substitution.replacement.amount, amountMax = substitution.replacement.amountMax) != substitution.replacement)
                    add("The replacement is not supported by the cited guidance.")
                expectedSteps = expectedSteps.map { it.replace(base.name, ingredient.name, ignoreCase = true) }
            }
            val expected = base.amount?.times(scale)
            if (expected == null && ingredient.amount != null || expected != null && (ingredient.amount == null || !ingredient.amount.isFinite() || abs(ingredient.amount - expected) > 0.00001)) add("An ingredient quantity is not supported by the original serving basis.")
            val expectedMax = base.amountMax?.times(scale)
            if (expectedMax == null && ingredient.amountMax != null || expectedMax != null &&
                (ingredient.amountMax == null || !ingredient.amountMax.isFinite() || abs(ingredient.amountMax - expectedMax) > 0.00001))
                add("An ingredient range is not supported by the original serving basis.")
            if (value.servings != original.servings && base.preparation.any(Char::isDigit))
                add("This serving change needs the quantities inside the ingredient preparation reviewed; those quoted quantities have not been scaled.")
            addAll(FoodEvidence.conflicts(ingredient.name + " " + ingredient.preparation, profile))
            // This narrowly recognized variety-selection sentence is not an unspecified flavouring.
            // Full preparation text still participates in every restriction/exclusion check above/below.
            val uncertainPreparation = ingredient.preparation.replace(Regex("(?i)^as many colou?rs, shapes, sizes and flavou?rs as you can find$"), "")
            addAll(FoodEvidence.uncertainties(ingredient.name + " " + uncertainPreparation, profile).filterNot { it.startsWith("Some restrictions") })
            value.excludedIngredients.filter { FoodEvidence.excluded(ingredient.name + " " + ingredient.preparation, it) }.forEach { add("$it was excluded in this conversation.") }
        }
        if (value.instructions != expectedSteps || value.instructionEvidence != original.instructionEvidence || value.metadataEvidence != original.metadataEvidence) add("Recipe instructions or metadata lack matching original evidence.")
        if (value.activeMinutes != original.activeMinutes || value.cookingMinutes != original.cookingMinutes || value.waitingMinutes != original.waitingMinutes || value.waitingMinutesMax != original.waitingMinutesMax) add("Recipe timing changed without evidence.")
        if (value.originalServingDescription != original.originalServingDescription || value.servingBasisNote != original.servingBasisNote)
            add("The published serving description or selected basis changed without evidence.")
        val environment = value.origin.assertions.asReversed().firstNotNullOfOrNull { RecipeFollowUp.equipmentMode(it.text) }
            ?.let { profile.copy(mode = it).environment() } ?: profile.environment()
        if (value.activeMinutes == null) add("The source does not establish active preparation time.")
        else if (value.activeMinutes > environment.maxMinutes) add("This recipe exceeds your active preparation-time limit.")
        val methods = value.instructions.joinToString(" ")
        addAll(FoodEvidence.conflicts(methods, profile))
        value.excludedIngredients.filter { FoodEvidence.excluded(methods, it) }.forEach { add("$it remains in the recipe method.") }
        addAll(equipmentIssues(methods, environment.equipment))
        val waitingContext = methods + " " + original.metadataEvidence.joinToString(" ") { it.excerpt }
        if (Regex("(?i)\\b(?:chill(?:ed|ing)?|wait(?:ing)?|refrigerat(?:e|ed|ing|ion)|freez(?:e|ing)|overnight|(?:few|several) (?:hours|hrs))\\b").containsMatchIn(waitingContext) && value.waitingMinutes == null)
            add("The source does not establish the chilling or waiting time.")
        if (Regex("(?i)\\b(?:bake|roast|fry|simmer|boil|grill)\\b").containsMatchIn(methods) && value.cookingMinutes == null)
            add("The source does not establish the cooking time.")
        if (value.nutrition != NutritionEstimate()) add("Published nutrition cannot be retained automatically after recipe changes.")
    }.distinct()

    internal fun equipmentIssues(methods: String, available: List<String>): List<String> = buildList {
        val equipment = available.map(FoodRules::normalize)
        fun has(vararg names: String) = names.any { it in equipment }
        fun needs(pattern: String) = Regex("(?i)\\b(?:$pattern)\\b").containsMatchIn(methods)
        if (needs("bak(?:e|ed|ing)|roast(?:ed|ing)?|(?<!microwave )oven|broil(?:ed|ing)?") && !has("oven", "convection oven", "electric oven", "gas oven", "toaster oven")) add("This recipe needs an oven.")
        if (needs("fr(?:y|ied|ying)|simmer(?:ed|ing)?|saucepan|skillet|frying pan|hob|stove[ -]?top|boil(?:ed|ing)?|steam(?:ed|ing)?|saut(?:e|é)(?:ed|ing)?|burner") && !has("stove", "gas stove", "electric stove", "hob", "stovetop", "stove top", "induction", "induction hob", "induction stove", "induction cooktop", "hot plate", "hotplate"))
            add("This recipe needs a stove; a water-only kettle is not suitable.")
        if (needs("chill(?:ed|ing)?|refrigerat(?:e|ed|or|ing)|fridge") && !has("fridge", "refrigerator", "mini fridge", "fridge freezer")) add("This recipe needs a fridge.")
        if (needs("freez(?:e|er|ing)|frozen") && !has("freezer", "fridge freezer")) add("This recipe needs a freezer.")
        if (needs("microwav(?:e|ed|ing)") && !has("microwave", "microwave oven")) add("This recipe needs a microwave.")
        if (needs("blend(?:er|ed|ing)?") && !has("blender", "hand blender", "immersion blender")) add("This recipe needs a blender.")
        if (needs("food processor") && !has("food processor")) add("This recipe needs a food processor.")
        if (needs("(?:electric|stand|hand)[ -]mixer|electric whisk|electric beater") && !has("mixer", "electric mixer", "stand mixer", "hand mixer", "electric whisk", "electric beater")) add("This recipe needs an electric mixer.")
        if (needs("air[ -]fry(?:er|ing)?") && !has("air fryer")) add("This recipe needs an air fryer.")
        if (needs("pressure cook(?:er|ing)?") && !has("pressure cooker", "instant pot")) add("This recipe needs a pressure cooker.")
        if (needs("slow cook(?:er|ing)?|crock[ -]?pot") && !has("slow cooker", "crock pot", "crockpot")) add("This recipe needs a slow cooker.")
        if (needs("grill(?:ed|ing)?|barbecu(?:e|ing)") && !has("grill", "barbecue")) add("This recipe needs a grill.")
    }

    fun scale(recipe: Recipe, servings: Double): Recipe {
        require(servings.isFinite() && servings in 0.25..100.0)
        val value = requireNotNull(recipe.sourced)
        require(value.servings.isFinite() && value.servings > 0)
        val ratio = servings / value.servings
        return recipe.copy(id = UUID.randomUUID().toString(), aiGenerated = true, servings = servings.toInt(), sourced = value.copy(servings = servings, ingredients = value.ingredients.map { it.copy(amount = it.amount?.times(ratio), amountMax = it.amountMax?.times(ratio)) },
            adaptation = (value.adaptation ?: RecipeAdaptation("Portions scaled from the published recipe.")).copy(
                instructionChanges = (value.adaptation?.instructionChanges.orEmpty().filterNot { it.startsWith("Quantities scaled from ") } + "Quantities scaled from ${value.originalServings} published servings to $servings. The quoted method and timing retain the original serving basis; use the scaled ingredient quantities.").distinct())))
    }
}

/** Only explicit, equal-amount, no-cook guidance is applied. Other substitutions remain a question. */
object SupportedSubstitutions {
    internal fun isNoCook(instructions: List<String>): Boolean = !Regex("(?i)\\b(?:bak\\w*|roast\\w*|fr(?:y|ied|ying)|simmer\\w*|boil\\w*|cook\\w*|heat\\w*|microwav\\w*|steam\\w*|grill\\w*|broil\\w*|saut[eé]\\w*|toast\\w*|warm\\w*|melt\\w*|sear\\w*)\\b")
        .containsMatchIn(instructions.joinToString(" "))

    private fun candidates(original: NamedIngredient, text: String): List<Pair<String, String>> {
        // Anchor the complete imperative to its sentence. Never extract "replace" from
        // "Do not replace", a question, a conditional, or a longer qualified sentence.
        val pattern = Regex("(?im)(?:^|(?<=[.!?]) )\\s*(?:[-*] )?((?:replace|swap)\\s+${Regex.escape(original.name)}\\s+with\\s+([a-z][a-z -]{1,60}?)\\s+(?:in equal amounts|in equal quantities|at a 1:1 ratio)\\.)(?=\\s|$)")
        return pattern.findAll(text).filter { match ->
            val context = text.substring(maxOf(0, match.range.first - 180), match.range.first)
            !Regex("(?i)\\b(?:not|never|avoid|wrong|unsafe|mistake|rejected|unless|only if)\\b").containsMatchIn(context)
        }.map { it.groupValues[2].trim() to it.groupValues[1] }.toList()
    }
    private fun disputed(original: NamedIngredient, origin: ResultOrigin): Boolean = origin.retrieval?.sources.orEmpty().any { source ->
        source.excerpt.split(Regex("[.!?\\n]")).any { sentence -> FoodRules.contains(sentence, original.name) &&
            Regex("(?i)\\b(?:not|never|avoid|cannot|can['’]t|don['’]t|unsuitable|fails?|instead of|unless|only if)\\b").containsMatchIn(sentence) }
    }
    fun supports(value: Substitution, origin: ResultOrigin): Boolean {
        if (value.evidence.size != 1 || !FoodEvidence.valid(origin, value.evidence) || disputed(value.original, origin)) return false
        val ref = value.evidence.single()
        val source = origin.retrieval?.sources?.singleOrNull { it.id == ref.sourceId } ?: return false
        val originalSource = requireNotNull(origin.retrieval).sources.singleOrNull { original -> value.original.evidence.any { it.sourceId == original.id } } ?: return false
        return source.kind == "recipe" && RecipeFollowUp.matchesDish(source, originalSource.title.substringBefore(" | ")) &&
            candidates(value.original, source.excerpt).any { (name, quote) -> name == value.replacement.name && quote == ref.excerpt } &&
            value.original.key == value.replacement.key && value.original.amount == value.replacement.amount && value.original.amountMax == value.replacement.amountMax &&
            value.original.unit == value.replacement.unit && value.replacement.evidence == value.evidence && value.replacement.preparation.isBlank()
    }

    fun adapt(recipe: Recipe, origin: ResultOrigin, profile: Profile, exclusions: List<String>): Recipe? {
        val value = requireNotNull(recipe.sourced)
        if (!isNoCook(value.instructions)) return null
        val substitutions = value.ingredients.filter { FoodEvidence.conflicts(it.name + " " + it.preparation, profile).isNotEmpty() || exclusions.any { term -> FoodEvidence.excluded(it.name + " " + it.preparation, term) } }.map { ingredient ->
            val found = origin.retrieval!!.sources.filter { it.kind == "recipe" }.firstNotNullOfOrNull { source ->
                candidates(ingredient, source.excerpt).firstNotNullOfOrNull { (name, quote) ->
                    if (disputed(ingredient, origin) || FoodEvidence.conflicts(name, profile).isNotEmpty() || FoodEvidence.uncertainties(name, profile).any { !it.startsWith("Some restrictions") } || exclusions.any { FoodEvidence.excluded(name, it) }) null
                    else Substitution(ingredient, ingredient.copy(name = name, preparation = "", evidence = listOf(FoodEvidence.reference(origin.retrieval, source, quote))),
                        "Published equal-amount substitution guidance; check the actual package and texture.", listOf(FoodEvidence.reference(origin.retrieval, source, quote))).takeIf { supports(it, origin) }
                }
            } ?: return null
            found
        }
        if (substitutions.isEmpty()) return null
        var steps = value.instructions
        substitutions.forEach { steps = steps.map { text -> text.replace(it.original.name, it.replacement.name, ignoreCase = true) } }
        return recipe.copy(id = UUID.randomUUID().toString(), aiGenerated = true, profileRevision = origin.profileRevision, sourced = value.copy(origin = origin, instructions = steps,
            ingredients = value.ingredients.map { part -> substitutions.find { it.original.key == part.key }?.replacement ?: part },
            excludedIngredients = exclusions, adaptation = RecipeAdaptation("Adapted from the published recipe using cited equal-amount guidance. Texture can differ.", substitutions,
                listOf("Ingredient names in the method were replaced; the original method remains linked."))), origin = origin)
    }
}
