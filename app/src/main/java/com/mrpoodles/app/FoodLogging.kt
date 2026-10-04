package com.mrpoodles.app

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Persist this entire value before applying the batch. Never regenerate a prepared retry. */
@Serializable data class PreparedFoodLog(
    val request: RequestIdentity, val message: String, val entries: List<Intake>,
    val operations: List<IntakeOperation>, val previousEntries: List<Intake> = emptyList(),
    val origin: ResultOrigin? = null
)

internal data class EatenFood(val name: String, val portion: Portion)
internal data class FoodLogIntent(
    val foods: List<EatenFood> = emptyList(), val date: LocalDate,
    val question: Boolean = false, val correction: Double? = null,
    val halve: Boolean = false, val clarification: String? = null, val explicitDate: Boolean = false
)

/** Deliberately recognizes assertions, rather than letting model prose authorize a diary write. */
internal object FoodLogLanguage {
    private const val NUMBER = "(?:\\d+(?:\\.\\d+)?|one|two|three|four|five|six|a|an|half|ek|do|teen|aadha|aadhi)"
    private val numbers = mapOf("one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
        "five" to 5.0, "six" to 6.0, "a" to 1.0, "an" to 1.0, "half" to 0.5,
        "ek" to 1.0, "do" to 2.0, "teen" to 3.0, "aadha" to 0.5, "aadhi" to 0.5)
    private fun amount(value: String) = value.toDoubleOrNull() ?: numbers.getValue(value.lowercase())
    private val eaten = Regex("(?i)\\b(?:ate|eaten|had|khayi|khai|khaya|khaye|khaayi|khaya tha|piya|drank|logged|log)\\b")
    private val questionStart = Regex("(?i)^(?:how|what|why|when|which|can|could|should|would|is|are|does|do|did|kitn[aei]|kya|tell me|calories? in|nutrition (?:in|of))\\b")

    fun parse(text: String, today: LocalDate, hasTarget: Boolean): FoodLogIntent {
        var input = text.trim().lowercase(Locale.ROOT)
        val question = '?' in input || questionStart.containsMatchIn(input) ||
            Regex("\\b(?:kitn[aei]|how many|how much)\\b").containsMatchIn(input)
        val dates = mutableListOf<LocalDate>()
        if (Regex("\\b(?:yesterday|kal)\\b").containsMatchIn(input)) {
            if (FoodRules.contains(input, "kal")) return FoodLogIntent(date = today, clarification = "Did you mean yesterday or tomorrow? Please use a date like 2026-10-04.")
            dates += today.minusDays(1)
        }
        if (Regex("\\b(?:today|aaj)\\b").containsMatchIn(input)) dates += today
        val iso = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b")
        for (match in iso.findAll(input)) {
            dates += runCatching { LocalDate.parse(match.value) }.getOrElse {
                return FoodLogIntent(date = today, clarification = "That date doesn't look valid. Please use year-month-day, like 2026-10-04.")
            }
        }
        val months = "(?:january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)"
        val named = Regex("\\b(?:\\d{1,2} $months \\d{4}|$months \\d{1,2},? \\d{4})\\b")
        for (match in named.findAll(input)) {
            val value = match.value.replace(",", "").split(' ').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
            val parsed = listOf("d MMM uuuu", "d MMMM uuuu", "MMM d uuuu", "MMMM d uuuu").firstNotNullOfOrNull { pattern ->
                runCatching { LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT)) }.getOrNull()
            } ?: return FoodLogIntent(date = today, clarification = "Please check that calendar date and use year-month-day.")
            dates += parsed
        }
        if (dates.distinct().size > 1 || Regex("\\b\\d{1,4}[/.]\\d{1,2}[/.]\\d{1,4}\\b|\\b\\d{1,2}-\\d{1,2}-\\d{2,4}\\b|\\b(?:last|next) (?:night|week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b|\\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|tomorrow)\\b").containsMatchIn(input))
            return FoodLogIntent(date = today, clarification = "Which date should I use? Please send one day at a time, written like 2026-10-04.")
        val date = dates.firstOrNull() ?: today
        if (date > today && !question) return FoodLogIntent(date = date, clarification = "This diary is for food you've already eaten. Please check the date.")
        input = named.replace(iso.replace(input, ""), "").replace(Regex("\\b(?:today|yesterday|aaj)\\b"), "")
            .replace(Regex("\\b(?:on|at)\\s*$"), "").trim()
        if (!question && Regex("\\b(?:not|never|didn't|didnt|haven't|haven’t|don't|dont|nahi|nahin|nhi|will|want|might|planning|plan to|going to|if|wish|friend|she|he|they|someone)\\b").containsMatchIn(input))
            return FoodLogIntent(date = date, clarification = "I haven't added anything. Tell me what you actually ate when you'd like to log it.")
        val bareHalf = input.trim(' ', '.', '!') in listOf("half", "aadha", "aadhi")
        val correction = Regex("^(?:(?:i )?(?:actually )?(?:ate|had|eaten) |(?:make (?:it )?|actually ))?($NUMBER)(?: (?:pieces?|portions?|servings?))?[.!]*$").matchEntire(input)
        if (!question && (bareHalf || correction != null)) {
            if (!hasTarget) return FoodLogIntent(date = date, clarification = "Which food should I change? Please log or select a food first.")
            return FoodLogIntent(date = date, correction = correction?.groupValues?.get(1)?.let(::amount), halve = bareHalf, explicitDate = dates.isNotEmpty())
        }
        val extra = hasTarget && Regex("\\b(?:too|also|bhi|extra)\\b").containsMatchIn(input)
        if (!question && !eaten.containsMatchIn(input) && !extra)
            return FoodLogIntent(date = date, clarification = "Did you eat this? Try ‘I ate a kachori’ or ask ‘How many calories in kachori?’.")
        input = input.replace(Regex("^(?:how (?:many|much) (?:calories?|protein|carbs?|fat)(?: (?:are|is|there))? (?:in|does)|(?:what is |tell me )?(?:the )?nutrition (?:in|of)|calories? in)\\s*"), "")
            .replace(Regex("\\b(?:how many calories|calories|kitni calories|kitne calories)\\b"), "")
            .replace(Regex("\\b(?:i|have|just|actually|maine|mene|mein|ne|there was|there were|some|please)\\b"), "")
        input = eaten.replace(input, "").replace(Regex("\\b(?:hai|hain|tha|thi|the|too|also|bhi|extra|for breakfast|for lunch|for dinner|for a snack)\\b"), "")
        val foods = input.split(Regex("\\s+(?:and|aur|with|plus)\\s+|[,;+]")).mapNotNull { raw ->
            var food = raw.trim(' ', '.', '!', '?', ':').replace(Regex("\\s+"), " ")
            if (food.isBlank()) return@mapNotNull null
            val quantity = Regex("^($NUMBER)(?:\\s+|(?=g\\b|grams?\\b))").find(food)
            val count = quantity?.groupValues?.get(1)?.let(::amount) ?: 1.0
            food = quantity?.let { food.removeRange(it.range).trim() } ?: food
            val unitMatch = Regex("^(g|grams?|kg|ml|cups?|tbsp|tsp|tablespoons?|teaspoons?|pieces?|servings?|bowls?)(?:\\s+of)?\\s+").find(food)
            val rawUnit = unitMatch?.groupValues?.get(1)
            food = unitMatch?.let { food.removeRange(it.range).trim() } ?: food
            val size = Regex("^(small|medium|large)\\s+").find(food)?.groupValues?.get(1)
            if (size != null) food = food.removePrefix("$size ")
            if (food.isBlank() || !food.any(Char::isLetter) || count <= 0 || count > 10000) return@mapNotNull null
            val unit = when (rawUnit) {
                "g", "gram", "grams", "kg" -> "g"
                null -> if (Regex("\\b(?:chutney|rice|dal|milk|yogurt|yoghurt|soup|juice|tea|coffee)\\b").containsMatchIn(food)) "serving" else "piece"
                "piece", "pieces" -> "piece"
                else -> rawUnit
            }
            val value = if (rawUnit == "kg") count * 1000 else count
            val description = if (unit == "piece") "${size ?: "medium"} $food" else "$unit $food"
            EatenFood(food, Portion(value, unit, description, assumed = quantity == null || (unit == "piece" && size == null), grams = value.takeIf { unit == "g" }))
        }
        if (foods.isEmpty() || foods.size > 6) return FoodLogIntent(date = date, question = question,
            clarification = "Please name one to six foods with their amounts, so I can keep your diary clear.")
        return FoodLogIntent(foods, date, question)
    }
}

/** Parses only labelled nutrition blocks from matching full retrieved pages, never model prose. */
internal object FoodLogNutrition {
    private data class Basis(val offset: Int, val end: Int, val count: Double, val unit: String,
        val size: String?, val grams: Double?)
    private data class Sample(val kcal: Double?, val protein: Double?, val carbs: Double?, val fat: Double?,
        val reference: EvidenceReference, val note: String = "")
    // Capture the whole numeric spelling first. Validation, not a regex suffix match, decides
    // whether it is a supported decimal or correctly grouped thousands value.
    private val number = "([+\\-−]?[0-9][0-9.,'’_eE+\\-−/:]*(?:[ \\t\u00a0\u202f]+[0-9][0-9.,'’_eE+\\-−/:]*)*)"
    private val decimal = Regex("(?:[0-9]+|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")
    private fun numeric(value: String): Double? = value.takeIf { decimal.matches(it) }
        ?.replace(",", "")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

    private fun entity(value: String): String = FoodRules.normalize(value).split(' ').filter(String::isNotBlank)
        .joinToString(" ") { token ->
            val term = if (token in listOf("pyaaz", "pyaz")) "onion" else token
            when {
                // These food plurals end in -is; that suffix is otherwise protected for
                // singular words. Keep explicit exceptions rather than dropping food terms.
                term in setOf("kachoris", "rotis", "chapatis", "puris", "idlis") -> term.dropLast(1)
                term.endsWith("ies") && term.length > 4 -> term.dropLast(3) + "y"
                term.endsWith("oes") && term.length > 4 -> term.dropLast(2)
                term.endsWith("s") && term.length > 3 && !term.endsWith("ss") && !term.endsWith("us") && !term.endsWith("is") -> term.dropLast(1)
                else -> term
            }
        }

    private val nutritionWords = "(?:nutrition(?:al)?(?: facts| information| data| values)?|calories(?: and nutrition(?: facts| information)?)?)"
    private fun headingEntity(value: String): String {
        val normalized = FoodRules.normalize(value)
        // Strip only anchored presentation wrappers, never arbitrary words inside a food name.
        val name = Regex("^$nutritionWords (?:for|of|in) (.+)$").matchEntire(normalized)?.groupValues?.get(1)
            ?: Regex("^(.+?) $nutritionWords$").matchEntire(normalized)?.groupValues?.get(1)
            ?: normalized
        return entity(name)
    }

    private val genericHeadings = setOf("nutrition", "nutrition facts", "nutritional information", "nutrition information",
        "nutrition data", "nutrition values", "nutrients", "calories", "serving size", "ingredients", "references", "notes", "footnotes")

    private fun matching(source: RetrievedSource, food: String, extraGeneric: Set<String> = emptySet(), requireFoodHeading: Boolean = false): Boolean {
        // Manufacturer classification is supplied by the backend's reviewed source registry.
        if (source.kind !in setOf("nutrition", "manufacturer")) return false
        val wanted = entity(food)
        if (wanted.isBlank() || headingEntity(source.title) != wanted) return false
        val lines = source.excerpt.lines()
        val firstContent = lines.indexOfFirst { it.isNotBlank() }
        val headings = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            val markdown = Regex("^\\s{0,3}#{1,6}\\s+(.+?)(?:\\s+#+)?\\s*$").matchEntire(line)
            val identity = Regex("(?i)^\\s*(?:food|product|recipe|dish)(?: name)?\\s*:\\s*(.+)$").matchEntire(line)
            when {
                markdown != null -> headings += markdown.groupValues[1]
                identity != null -> headings += identity.groupValues[1]
                lines.getOrNull(index + 1)?.trim()?.matches(Regex("(?:={3,}|-{3,})")) == true -> headings += line
                // A semicolon-separated sentence ending in "nutrition values" is prose,
                // not an unmarked food heading. Explicit headings/identity labels above
                // remain authoritative even when they contain punctuation.
                ';' !in line && Regex("(?i)^$nutritionWords (?:for|of|in) .+|.+ $nutritionWords$")
                    .matches(FoodRules.normalize(line)) -> headings += line
                index == firstContent && !line.trimStart().startsWith("<") &&
                    !Regex("(?i)^\\s*(?:serving(?: size)?\\b|per\\s+)").containsMatchIn(line) -> headings += line
            }
        }
        Regex("(?is)<h[1-6]\\b[^>]*>(.*?)</h[1-6]\\s*>").findAll(source.excerpt).forEach {
            headings += it.groupValues[1].replace(Regex("<[^>]+>"), "")
        }
        // A correct search-result title cannot override a different food/recipe in the body.
        val allowed = genericHeadings + extraGeneric
        return (!requireFoodHeading || headings.any { FoodRules.normalize(it) !in allowed && headingEntity(it) == wanted }) &&
            headings.all { FoodRules.normalize(it) in allowed || headingEntity(it) == wanted }
    }

    private fun nutrient(block: String, labels: String, unit: String): Double? {
        val forward = Regex("(?i)^(?:$labels)\\b[ \\t:*|=]*$number[ \\t]*(?:$unit)[ \\t*|]*$")
        val reverse = Regex("(?i)^$number[ \\t]*(?:$unit)(?:[ \\t]+(?:of )?(?:$labels))?[ \\t*|]*$")
        val calories = Regex("(?i)^calories[ \\t:*|=]+$number[ \\t*|]*$")
        val values = mutableListOf<Double>()
        for (raw in block.split(Regex("[\\r\\n;]"))) {
            val row = raw.trim().trimStart('*', '|').trim()
            val match = forward.matchEntire(row) ?: if (unit == "kcal|kilocalories|calories")
                reverse.matchEntire(row) ?: calories.matchEntire(row) else null
            if (match != null) {
                // A malformed metric invalidates this metric, even beside a well-formed duplicate.
                values += numeric(match.groupValues[1]) ?: return null
            } else if (Regex("(?i)^(?:$labels)\\b").containsMatchIn(row)) return null
        }
        // Conflicting values within a block usually indicate multiple serving columns.
        return values.distinct().singleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    }

    /** One reviewed publisher's first three-column label, not a general HTML/table extractor. */
    private fun myFoodDataTable(source: RetrievedSource, food: EatenFood, snapshotId: String): Sample? {
        if (source.kind != "nutrition" || !publicResearchUrl(source.url) ||
            runCatching { java.net.URI(source.url).host }.getOrNull() != "tools.myfooddata.com") return null
        val text = source.excerpt
        if (Regex("(?i)\\buser[ _-]*entered\\b").containsMatchIn(text)) return null
        val lines = Regex("[^\\n]*(?:\\n|$)").findAll(text).filter { it.value.isNotEmpty() }.toList()
        val start = lines.indexOfFirst { it.value.trimStart().startsWith("|") }
        if (start < 0) return null
        var end = start
        while (end < lines.size && lines[end].value.trimStart().startsWith("|")) end++
        if (end - start !in 5..64) return null
        val from = lines[start].range.first
        val until = lines[end - 1].range.last + 1
        if (until - from > 6000) return null
        val prefix = text.substring(0, from)
        if (!matching(source.copy(excerpt = prefix), food.name,
                extraGeneric = setOf("nutrition facts search tool"), requireFoodHeading = true)) return null
        val dataSources = Regex("(?im)^[ \\t]*Data Source:[ \\t]*([^\\r\\n]+)").findAll(prefix)
            .map { FoodRules.normalize(it.groupValues[1]) }.toList()
        if (dataSources != listOf("usda standard release")) return null

        // Keep offsets into the original excerpt. Cell parsing never changes the stored quotation.
        val rows = lines.subList(start, end).map { line ->
            val value = line.value.trim()
            if (value.length < 2 || !value.endsWith("|")) return null
            value.substring(1, value.length - 1).split('|').map(String::trim).also { if (it.size != 3) return null }
        }
        if (rows[0] != listOf("Nutrition Facts", "", "") ||
            !rows[1].all { it.matches(Regex(":?-{3,}:?")) } ||
            rows[2] != listOf("Serving Size", "", "") || rows[3].drop(1).any(String::isNotEmpty)) return null
        if (rows.drop(3).any { FoodRules.normalize(it[0]) == "serving size" }) return null
        val serving = Regex("(?i)^$number[ \\t]*(g|grams?|pieces?)(?:[ \\t]*\\([ \\t]*$number[ \\t]*(?:g|grams?)[ \\t]*\\))?$")
            .matchEntire(rows[3][0]) ?: return null
        val amount = numeric(serving.groupValues[1])?.takeIf { it > 0 } ?: return null
        val unit = if (serving.groupValues[2].lowercase().startsWith("piece")) "piece" else "g"
        val weight = serving.groupValues[3].takeIf(String::isNotBlank)?.let { value ->
            numeric(value)?.takeIf { it > 0 } ?: return null
        }
        // A mass label may repeat its mass, e.g. "100 grams (100g)". This is not
        // a piece conversion. Contradictory mass declarations remain unusable.
        if (unit == "g" && weight != null && weight != amount) return null
        val factor = when {
            food.portion.unit == unit -> food.portion.amount / amount
            food.portion.unit == "g" && unit == "piece" && weight != null -> food.portion.amount / weight
            else -> return null
        }.takeIf { it.isFinite() && it > 0 } ?: return null
        fun scaled(value: Double?) = value?.times(factor)?.takeIf { it.isFinite() && it >= 0 }
        val calorieRows = rows.drop(4).filter { it[0].equals("Calories", true) }
        val kcal = calorieRows.singleOrNull()?.takeIf { it[1].isEmpty() }?.let { scaled(numeric(it[2])) }
        fun macro(label: String): Double? {
            val matchingRows = rows.drop(4).filter { Regex("(?i)^${Regex.escape(label)}\\b").containsMatchIn(it[0]) }
            val row = matchingRows.singleOrNull() ?: return null
            if (row[1].isNotEmpty() || row[2].isNotEmpty() && !Regex("[0-9]+(?:\\.[0-9]+)?%").matches(row[2])) return null
            val value = Regex("(?i)^${Regex.escape(label)}[ \\t]+$number[ \\t]*g$").matchEntire(row[0]) ?: return null
            return scaled(numeric(value.groupValues[1]))
        }
        val protein = macro("Protein")
        val carbs = macro("Total Carbohydrate")
        val fat = macro("Total Fat")
        if (listOf(kcal, protein, carbs, fat).all { it == null }) return null
        return Sample(kcal, protein, carbs, fat, EvidenceReference(snapshotId, source.id, text.substring(from, until)),
            "MyFoodData attributes this label to USDA Standard Release. These are retrieved estimates, not independently verified measurements.")
    }

    fun estimate(food: EatenFood, snapshot: RetrievalSnapshot?): NutritionEstimate {
        val samples = mutableListOf<Sample>()
        snapshot?.sources?.forEach sourceLoop@ { source ->
            val text = source.excerpt
            if (text.lineSequence().any { it.trimStart().startsWith("|") }) {
                myFoodDataTable(source, food, requireNotNull(snapshot).id)?.let { samples += it }
                // A failed/unsupported table must never fall through to later tables or prose.
                return@sourceLoop
            }
            if (!matching(source, food.name)) return@sourceLoop
            val bases = mutableListOf<Basis>()
            val pieceName = food.name.substringAfterLast(' ').let { if (it.endsWith("s") && it.length > 3) it.dropLast(1) else it }
            val foodNames = (listOf(food.name, food.name.replace("pyaaz", "onion").replace("pyaz", "onion"), pieceName))
                .distinct().joinToString("|") { Regex.escape(it) + "s?" }
            // Generic serving/cup amounts need their own verified conversion; they are not pieces.
            val pattern = Regex("(?i)\\b(?:per\\s+|serving(?: size)?\\s*[:=]?\\s*|nutrition(?: facts)?(?: for)?\\s*[:=]?\\s*)($number|one|a)?\\s*(small\\s+|medium\\s+|large\\s+)?(grams?|g|pieces?|$foodNames)\\b(?:\\s*\\(\\s*$number\\s*g\\s*\\))?")
            pattern.findAll(text).forEach basisLoop@ { match ->
                // A matched "banana" must not silently consume the serving for "banana bread".
                // Inline/unlabelled tails are unsupported until their complete basis is parsed.
                if (text.substring(match.range.last + 1).substringBefore('\n').trim().isNotEmpty()) return@basisLoop
                val spelling = match.groupValues[1]
                val count = if (spelling.isBlank() || spelling.lowercase() in listOf("one", "a")) 1.0 else numeric(spelling) ?: return@basisLoop
                val weight = match.groupValues[5].takeIf(String::isNotBlank)?.let { numeric(it) ?: return@basisLoop }
                val rawUnit = match.groupValues[4].lowercase()
                bases += Basis(match.range.first, match.range.last + 1, count,
                    if (rawUnit in listOf("g", "gram", "grams")) "g" else "piece",
                    match.groupValues[3].trim().ifBlank { null }, weight)
            }
            bases.forEachIndexed { index, basis ->
                if (basis.count <= 0) return@forEachIndexed
                val requestedSize = Regex("^(small|medium|large)\\b").find(food.portion.description)?.value
                if (basis.unit == "piece" && basis.size != null && requestedSize != basis.size) return@forEachIndexed
                val factor = when {
                    food.portion.unit == basis.unit -> food.portion.amount / basis.count
                    food.portion.unit == "g" && basis.unit == "piece" && basis.grams != null && basis.grams > 0 -> food.portion.amount / basis.grams
                    else -> return@forEachIndexed
                }
                val end = (bases.getOrNull(index + 1)?.offset ?: text.length).coerceAtMost(basis.end + 700)
                var block = text.substring(basis.offset, end)
                // Do not consume an unrelated section, recipe, or ingredients as nutrition.
                val heading = Regex("(?m)^#{1,6}\\s+").find(block, (basis.end - basis.offset).coerceAtMost(block.length))
                if (heading != null) block = block.substring(0, heading.range.first)
                if (Regex("(?i)\\b(?:kj|kilojoules)\\b").containsMatchIn(block) && !Regex("(?i)\\b(?:kcal|calories|kilocalories)\\b").containsMatchIn(block)) return@forEachIndexed
                val kcal = nutrient(block, "energy|calories", "kcal|kilocalories|calories")?.times(factor)
                val protein = nutrient(block, "protein", "g|grams")?.times(factor)
                val carbs = nutrient(block, "carbohydrates?|carbs?|total carbohydrate", "g|grams")?.times(factor)
                val fat = nutrient(block, "total fat|fat", "g|grams")?.times(factor)
                if (listOf(kcal, protein, carbs, fat).all { it == null }) return@forEachIndexed
                samples += Sample(kcal, protein, carbs, fat, EvidenceReference(requireNotNull(snapshot).id, source.id, block))
            }
        }
        // A publisher contributes one comparable block. Multiple comparable columns are ambiguous.
        val comparable = samples.groupBy { it.reference.sourceId }.values.mapNotNull { it.singleOrNull() }
        fun representative(selector: (Sample) -> Double?): Double? {
            if (comparable.isEmpty() || comparable.any { selector(it) == null }) return null
            val values = comparable.map { selector(it)!! }.sorted()
            val mid = values.size / 2
            return if (values.size % 2 == 0) (values[mid - 1] + values[mid]) / 2 else values[mid]
        }
        val kcal = representative { it.kcal }
        val values = comparable.mapNotNull { it.kcal }
        val disagreement = values.distinct().size > 1
        return NutritionEstimate(kcal, representative { it.protein }, representative { it.carbs }, representative { it.fat },
            if (kcal == null) EstimateStatus.UNKNOWN else EstimateStatus.ESTIMATED, food.portion,
            comparable.map { it.reference }, buildString {
                if (kcal == null) append("I couldn't verify calories on a comparable serving basis. Nutrition remains unknown; no weight was guessed.")
                else append("Rough estimate from comparable published portions; your recipe and size may differ.")
                if (disagreement) append(" Sources disagree: ${foodLogNumber(values.min())}–${foodLogNumber(values.max())} kcal for this amount; the middle estimate is shown, not a measured value.")
                if (comparable.any { it.protein == null || it.carbs == null || it.fat == null }) append(" Missing macros stay unknown.")
                if (food.portion.assumed) append(" Assumption: ${foodLogNumber(food.portion.amount)} ${food.portion.description}. You can change it.")
                comparable.map { it.note }.filter(String::isNotBlank).distinct().forEach { append(" $it") }
            }, values.minOrNull().takeIf { kcal != null && disagreement }, values.maxOrNull().takeIf { kcal != null && disagreement })
    }
}

class FoodLoggingService(private val model: CloudModel) {
    suspend fun prepare(id: RequestIdentity, profile: Profile, conversation: FeatureConversation, text: String,
        today: LocalDate, existing: List<Intake>): PreparedFoodLog {
        conversation.preparedLog?.takeIf { it.request.operationId != null && it.request.operationId == id.operationId }?.let {
            // Keep request, original dates, revisions, source snapshots and operation bytes intact.
            return it
        }
        val targets = conversation.result?.intakeIds.orEmpty().mapNotNull { target -> existing.find { it.id == target } }
        val intent = FoodLogLanguage.parse(text, today, targets.isNotEmpty())
        val assertionOrigin = ResultOrigin(id.id, id.profileRevision, listOf(UserAssertion(text)))
        fun answer(message: String, origin: ResultOrigin = assertionOrigin) = PreparedFoodLog(id, message, emptyList(), emptyList(), origin = origin)
        intent.clarification?.let { return answer(it) }
        if (intent.correction != null || intent.halve) {
            if (targets.size != 1) return answer("Which food should I change? Your last log has several foods; please use Edit in the diary to choose one.")
            val old = targets.single()
            val portion = old.portion ?: return answer("This older entry has no reliable portion basis. Please use Edit in the diary to change it.")
            val amount = if (intent.halve) portion.amount / 2 else requireNotNull(intent.correction)
            require(amount.isFinite() && amount > 0 && amount <= 10000) { "Please use a positive, practical food amount." }
            val factor = amount / portion.amount
            require(factor.isFinite() && factor > 0) { "The old portion needs review before scaling." }
            // A quantity correction does not confirm the previously assumed piece size.
            val updatedPortion = portion.copy(amount = amount, grams = portion.grams?.times(factor))
            val oldSnapshot = old.origin?.retrieval
            val snapshot = oldSnapshot?.copy(id = "${id.operationId ?: id.id}:evidence", requestId = id.id)
            val origin = assertionOrigin.copy(assertions = old.origin?.assertions.orEmpty() + UserAssertion(text), retrieval = snapshot)
            val estimate = old.estimate?.copy(kcal = old.kcal?.times(factor), protein = old.protein?.times(factor),
                carbs = old.carbs?.times(factor), fat = old.fat?.times(factor), basis = updatedPortion,
                kcalLow = old.estimate.kcalLow?.times(factor), kcalHigh = old.estimate.kcalHigh?.times(factor),
                evidence = old.estimate.evidence.map { it.copy(snapshotId = snapshot?.id ?: it.snapshotId) },
                explanation = "Quantity changed from ${foodLogNumber(portion.amount)} to ${foodLogNumber(amount)} ${portion.unit}. Original size assumptions and source uncertainty still apply; unknown nutrients stay unknown.")
            val entry = old.copy(date = if (intent.explicitDate) intent.date.toString() else old.date,
                kcal = old.kcal?.times(factor), protein = old.protein?.times(factor), carbs = old.carbs?.times(factor),
                fat = old.fat?.times(factor), portions = old.portions * factor, portion = updatedPortion, estimate = estimate,
                entryRevision = old.entryRevision + 1, origin = origin)
            val operation = IntakeOperation("${prefix(id)}:update:${old.id}", old.id, old.entryRevision, entry)
            return PreparedFoodLog(id, "Of course. ${old.name}: ${foodLogNumber(amount)} ${portion.unit}. This changes the same diary entry.${warning(old.name, profile)}",
                listOf(entry), listOf(operation), listOf(old), origin)
        }
        check(profile.sourceLookupConsent) { "Please review source lookup before sending food names to search providers." }
        val entries = mutableListOf<Intake>()
        val sources = mutableListOf<RetrievedSource>()
        val messages = mutableListOf<String>()
        val batchPrefix = if (intent.question) id.operationId ?: id.id else prefix(id)
        for ((index, food) in intent.foods.withIndex()) {
            require(food.name.length <= 160) { "Please keep each food name under 160 characters." }
            val request = ResearchRequest(id.id, id.profileRevision, "food_log", "${food.name} nutrition per ${food.portion.unit}".take(160),
                allowExternalModel = profile.externalModelConsent)
            // /v2/research already composes the same Mr. Poodles personality on both providers.
            // Its current schema supplies verbatim claims, not structured nutrient inventions.
            var lookupFailure: String? = null
            val response = try { model.research(request).validate(request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ServiceLimitException) { lookupFailure = failure.message; null }
            catch (_: Exception) { lookupFailure = "Source lookup failed. Your diary is unchanged until you choose to save unknown nutrition."; null }
            val snapshot = response?.snapshot?.let { value ->
                value.copy(id = "$batchPrefix:evidence:$index", sources = value.sources.mapIndexed { n, source -> source.copy(id = "food-$index-source-$n") })
            }
            sources += snapshot?.sources.orEmpty()
            val origin = assertionOrigin.copy(retrieval = snapshot)
            val estimate = FoodLogNutrition.estimate(food, snapshot).let { value ->
                if (lookupFailure == null) value else value.copy(explanation = "${value.explanation} $lookupFailure")
            }
            messages += "${food.name}: ${estimate.kcal?.let { "about ${foodLogNumber(it)} kcal" } ?: "calories unknown"} for ${foodLogNumber(food.portion.amount)} ${food.portion.description}. " +
                "Protein ${estimate.protein?.let { "${foodLogNumber(it)} g" } ?: "unknown"}, carbs ${estimate.carbs?.let { "${foodLogNumber(it)} g" } ?: "unknown"}, fat ${estimate.fat?.let { "${foodLogNumber(it)} g" } ?: "unknown"}. ${estimate.explanation}"
            if (!intent.question) entries += Intake(id = "$batchPrefix:food:$index", date = intent.date.toString(), name = food.name,
                kcal = estimate.kcal, protein = estimate.protein, carbs = estimate.carbs, fat = estimate.fat,
                portions = food.portion.amount, source = if (estimate.kcal == null) "Unknown nutrition" else "Retrieved nutrition estimate",
                portion = food.portion, estimate = estimate, origin = origin)
        }
        val combinedOrigin = assertionOrigin.copy(retrieval = sources.takeIf { it.isNotEmpty() }?.let { RetrievalSnapshot("$batchPrefix:evidence", id.id, it) })
        if (intent.question) return answer("Let's look at that together. Nothing was added to your diary.\n" + messages.joinToString("\n"), combinedOrigin)
        val unknown = entries.any { it.kcal == null }
        val message = (if (unknown) "I couldn't verify every food's calories. You can retry or choose Save with unknown nutrition; nothing is confirmed saved yet.\n" else "Here's a rough estimate for ${intent.date}.\n") +
            messages.joinToString("\n") + warning(entries.joinToString(" ") { it.name }, profile)
        return PreparedFoodLog(id, message, entries, entries.map { IntakeOperation("$batchPrefix:add:${it.id}", it.id, entry = it) }, origin = combinedOrigin)
    }

    private fun prefix(id: RequestIdentity) = requireNotNull(id.operationId?.takeIf(String::isNotBlank)) { "Food logging needs a stable logical operation ID." }
    private fun warning(text: String, profile: Profile): String {
        val findings = FoodRules.check(text, profile).findings.map { it.trigger }.distinct()
        return if (findings.isEmpty()) "" else "\nSeparate restriction note: ${findings.joinToString()} matches this food name. What you actually ate can still be logged, without judgment. This is not a complete ingredient check."
    }
}

internal fun foodLogNumber(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.ROOT, "%.1f", value)
