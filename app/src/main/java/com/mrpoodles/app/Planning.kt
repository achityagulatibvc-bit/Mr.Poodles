package com.mrpoodles.app

import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

@Serializable data class PlanPreview(
    val meals: List<Meal>, val expectedMeals: List<Meal>, val dates: List<String>, val profileRevision: Int
)
@Serializable data class PantryAmount(val name: String, val unit: String, val amount: Double)
data class ShoppingItem(val key: String, val name: String, val amount: Double?, val unit: String, val note: String = "")
data class PlanningNeeds(val required: Int, val available: Int, val missing: Int, val queries: List<String>)
class MissingPlanRecipes(val needs: PlanningNeeds) : IllegalStateException(
    "Poodles needs ${needs.missing} more distinct, suitable sourced recipes (${needs.available}/${needs.required} ready). " +
        "Your existing meals are kept. Find more recipes, then preview again."
)

/** Pure operations. Call apply and individual edits against the latest data inside the persistence lock. */
object PlanningRules {
    val slots = listOf("Breakfast", "Lunch", "Dinner", "Snack")
    private val scheduledSlots = slots.take(3)

    fun parseDate(value: String): LocalDate? = runCatching {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value))
        LocalDate.parse(value).also { require(it.year in 1..9999) }
    }.getOrNull()

    fun dates(start: LocalDate, days: Int): List<String> {
        require(days in 1..31) { "Choose between 1 and 31 days." }
        return List(days) { start.plusDays(it.toLong()).toString().also { date ->
            require(parseDate(date) != null) { "Choose a date between years 0001 and 9999." }
        } }
    }

    /** Portion variants, renamed copies and repeated retrievals are not distinct meal choices. */
    fun suitableRecipes(data: AppData, recipes: List<Recipe>): List<Recipe> {
        val sources = mutableSetOf<String>()
        val titles = mutableSetOf<String>()
        val compositions = mutableSetOf<String>()
        return recipes.filter { recipe ->
            val sourced = recipe.sourced ?: return@filter false
            if (SourcedRecipeRules.validate(recipe, data.profile).isNotEmpty()) return@filter false
            // A recipe's chat-specific equipment override must not override the planner's current environment.
            if (SourcedRecipeRules.equipmentIssues(sourced.instructions.joinToString(" "), data.profile.environment().equipment).isNotEmpty() ||
                sourced.activeMinutes == null || sourced.activeMinutes > data.profile.environment().maxMinutes) return@filter false
            val source = sourced.origin.retrieval?.sources?.singleOrNull { it.id == sourced.originalSourceId }
                ?: return@filter false
            val sourceKey = runCatching { URI(source.url).let {
                "${it.host.lowercase(Locale.ROOT)}${it.path.orEmpty().trimEnd('/')}"
            } }.getOrDefault(source.url)
            val title = exactName(recipe.title)
            val composition = sourced.ingredients.map { exactName(it.name) }.sorted().joinToString("\u0000") +
                "\u0001" + sourced.instructions.joinToString(" ").let(::exactName)
            if (sourceKey in sources || title in titles || composition in compositions) return@filter false
            sources += sourceKey; titles += title; compositions += composition
            true
        }
    }

    /** One distinct recipe per planned meal: a seven-day preview needs 21, not three permutations. */
    fun needs(data: AppData, recipes: List<Recipe>, days: Int): PlanningNeeds {
        require(days in 1..31) { "Choose between 1 and 31 days." }
        val required = days * scheduledSlots.size
        val available = suitableRecipes(data, recipes).size
        val missing = (required - available).coerceAtLeast(0)
        val themes = listOf("fruit breakfast", "savory grain lunch", "vegetable dinner", "oat breakfast",
            "bean lunch", "rice dinner", "potato breakfast", "lentil lunch", "salad dinner",
            "wholegrain breakfast", "chickpea lunch", "vegetable soup dinner", "seed breakfast",
            "pasta lunch", "vegetable bowl dinner", "toast breakfast", "grain salad lunch",
            "stew dinner", "breakfast bowl", "lentil salad lunch", "rice bowl dinner")
        val excluded = suitableRecipes(data, recipes).takeLast(21).joinToString("; ") { it.title }
        val queries = List(missing) { index ->
            "Find a different published ${themes[(available + index) % themes.size]} recipe with complete quantities, " +
                "servings, method and preparation time. Match my current restrictions, diet and equipment. " +
                "Do not repeat these dishes: ${excluded.ifBlank { "none yet" }}."
        }
        return PlanningNeeds(required, available, missing, queries)
    }

    /** Capture data before fetching more candidates so expectedMeals fences edits made during retrieval. */
    fun preview(data: AppData, recipes: List<Recipe>, start: LocalDate, days: Int): PlanPreview {
        val dates = dates(start, days)
        val candidates = suitableRecipes(data, recipes)
        val needs = needs(data, candidates, days)
        if (needs.missing > 0) throw MissingPlanRecipes(needs)
        val remaining = candidates.toMutableList()
        val meals = dates.flatMap { date -> scheduledSlots.map { slot ->
            val recipe = remaining.maxBy { slotScore(it, slot) }
            remaining.remove(recipe)
            Meal(date = date, slot = slot, recipe = recipe, mode = data.profile.mode, revision = data.revision)
        } }
        return PlanPreview(meals, data.meals.filter { it.date in dates }, dates, data.revision)
    }

    private fun slotScore(recipe: Recipe, slot: String): Int {
        val text = exactName(recipe.title)
        val breakfast = listOf("breakfast", "porridge", "oat", "pancake", "toast", "fruit", "granola")
        val savory = listOf("salad", "soup", "stew", "rice", "lentil", "bean", "pasta", "dinner", "lunch")
        return (if (slot == "Breakfast") breakfast else savory).count { text.contains(it) }
    }

    fun apply(data: AppData, preview: PlanPreview): AppData {
        check(data.revision == preview.profileRevision) { "Your profile changed. Preview a new plan before applying it." }
        require(preview.dates.isNotEmpty()) { "The preview has no dates." }
        val start = requireNotNull(parseDate(preview.dates.first())) { "The preview date is invalid." }
        require(preview.dates == dates(start, preview.dates.size)) { "The preview dates need review." }
        val expected = preview.expectedMeals
        require(expected.all { it.date in preview.dates } && expected.map { it.id }.distinct().size == expected.size)
        val current = data.meals.filter { it.date in preview.dates }
        check(current.size == expected.size && current.associateBy { it.id } == expected.associateBy { it.id }) {
            "Your meals changed while this preview was open. Your edits are kept; preview again."
        }
        require(preview.meals.size == preview.dates.size * 3 &&
            preview.meals.map { it.id }.distinct().size == preview.meals.size) { "The preview has missing or duplicate meals." }
        preview.dates.forEach { date ->
            require(preview.meals.filter { it.date == date }.map { it.slot }.sorted() == scheduledSlots.sorted()) {
                "The preview must include breakfast, lunch and dinner for each date."
            }
        }
        require(preview.meals.all { it.date in preview.dates && it.revision == data.revision &&
            it.mode == data.profile.mode && validPortions(it.portions) }) { "The preview's portions or profile need review." }
        require(suitableRecipes(data, preview.meals.map { it.recipe }).size == preview.meals.size) {
            "The preview needs distinct recipes that still fit your profile and have complete sources."
        }
        val outside = data.meals.filterNot { it.date in preview.dates }
        require(outside.none { old -> preview.meals.any { it.id == old.id } }) { "A meal identity conflicts with another date." }
        return data.copy(meals = outside + preview.meals)
    }

    fun validPortions(value: Double) = value.isFinite() && value > 0 && value <= 20

    /** An occupied slot replaces exactly one meal and retains its identity/portions; never delete siblings. */
    fun planRecipe(data: AppData, recipe: Recipe, date: String, slot: String, foods: List<Food> = emptyList()): AppData {
        validateDestination(date, slot)
        val issues = RecipeRules.validate(recipe, data.profile, foods) + recipe.sourced?.let { sourced ->
            SourcedRecipeRules.equipmentIssues(sourced.instructions.joinToString(" "), data.profile.environment().equipment) +
                if (sourced.activeMinutes == null || sourced.activeMinutes > data.profile.environment().maxMinutes)
                    listOf("This recipe's active preparation time needs review for your current environment.") else emptyList()
        }.orEmpty()
        require(issues.isEmpty()) { issues.joinToString(" ") }
        val occupied = data.meals.filter { it.date == date && it.slot == slot }
        require(occupied.size <= 1) { "Several saved meals share this slot. Move or remove one before replacing it." }
        val previous = occupied.singleOrNull()
        val meal = previous?.copy(recipe = recipe, mode = data.profile.mode, revision = data.revision)
            ?: Meal(date = date, slot = slot, recipe = recipe, mode = data.profile.mode, revision = data.revision)
        require(validPortions(meal.portions)) { "Correct this meal's portions before replacing its recipe." }
        return data.copy(meals = data.meals.filterNot { it.id == meal.id } + meal)
    }

    fun moveMeal(data: AppData, id: String, date: String, slot: String, portions: Double): AppData {
        validateDestination(date, slot)
        require(validPortions(portions)) { "Enter servings greater than 0 and at most 20." }
        val meal = data.meals.singleOrNull { it.id == id } ?: error("That planned meal is no longer available.")
        require(data.meals.none { it.id != id && it.date == date && it.slot == slot }) {
            "That meal slot is occupied. Choose an empty slot, or replace its recipe explicitly."
        }
        // Moving a meal is not evidence revalidation. Keep its old revision visible until reviewed.
        return data.copy(meals = data.meals.map { if (it.id == id) meal.copy(date = date, slot = slot, portions = portions) else it })
    }

    fun removeMeal(data: AppData, id: String): AppData = data.copy(meals = data.meals.filterNot { it.id == id })

    private fun validateDestination(date: String, slot: String) {
        require(parseDate(date) != null) { "Enter a real date as YYYY-MM-DD." }
        require(slot in slots) { "Choose Breakfast, Lunch, Dinner or Snack." }
    }

    private data class UnitAmount(val unit: String, val factor: Double)
    private fun normalizedUnit(value: String?): UnitAmount = when (val name = exactName(value.orEmpty())) {
        "g", "gram", "grams" -> UnitAmount("g", 1.0)
        "kg", "kilogram", "kilograms" -> UnitAmount("g", 1000.0)
        "ml", "milliliter", "milliliters", "millilitre", "millilitres" -> UnitAmount("ml", 1.0)
        "l", "liter", "liters", "litre", "litres" -> UnitAmount("ml", 1000.0)
        // Other unit spellings retain their exact identity; no volume/weight or container-size guess.
        else -> UnitAmount(name, 1.0)
    }

    private fun exactName(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    private fun stableKey(identity: String, unit: String, unknown: Boolean): String {
        val raw = "${identity.length}:$identity|${unit.length}:$unit|$unknown"
        return "shopping:" + MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Quantities are per sourced recipe / sourced.servings * planned servings, not per legacy Int servings. */
    fun shopping(meals: List<Meal>, pantry: List<PantryAmount>): List<ShoppingItem> {
        data class Part(val identity: String, val name: String, val amount: Double?, val unit: String, val note: String)
        val parts = meals.flatMap { meal ->
            val sourced = meal.recipe.sourced
            val servings = sourced?.servings ?: meal.recipe.servings.toDouble()
            val scale = if (servings.isFinite() && servings > 0 && validPortions(meal.portions)) meal.portions / servings else null
            fun part(identity: String, name: String, amount: Double?, rawUnit: String?, note: String = ""): Part {
                val unit = normalizedUnit(rawUnit)
                val quantity = amount?.takeIf { it.isFinite() && it > 0 }?.let { n -> scale?.let { n * it * unit.factor } }
                    ?.takeIf { it.isFinite() && it > 0 }
                return Part(identity, name.trim(), quantity, unit.unit,
                    if (quantity == null) "Quantity unknown; check the recipe. $note".trim()
                    else if (unit.unit.isBlank()) "Unit unspecified; pantry not deducted. $note".trim() else note)
            }
            if (sourced != null) sourced.ingredients.map { ingredient ->
                // A continuation can distinguish cooked/drained stock from raw stock. Do not erase it.
                val name = ingredient.name + ingredient.preparation.trim().takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
                if (ingredient.amountMax != null) {
                    // A published interval is not an exact inventory amount. Keep it in the unknown
                    // group so neither the lower bound nor pantry stock becomes a fabricated total.
                    part("name:${exactName(name)}", name, null, ingredient.unit,
                        "Recipe quantity is a range: ${ingredient.amount}–${ingredient.amountMax} ${ingredient.unit.orEmpty()}. " +
                            "Confirm the purchase amount for planned portions; pantry not deducted.")
                } else part("name:${exactName(name)}", name, ingredient.amount, ingredient.unit)
            } else meal.recipe.ingredients.map { ingredient ->
                part("catalog:${ingredient.id}", ingredient.id, ingredient.grams, "g", "Saved catalog ingredient; obtain in its listed preparation form.")
            }
        }
        return parts.groupBy { stableKey(it.identity, it.unit, it.amount == null) }.map { (key, group) ->
            val first = group.first()
            val total = if (first.amount == null) null else group.sumOf { requireNotNull(it.amount) }.takeIf { it.isFinite() }
            // Pantry rows are inventory snapshots, not additive events. Duplicate snapshots are ambiguous.
            val matches = if (first.identity.startsWith("name:") && first.unit.isNotBlank()) pantry.filter {
                exactName(it.name) == exactName(first.name) && normalizedUnit(it.unit).unit == first.unit
            } else emptyList()
            val stock = matches.singleOrNull()?.let { item ->
                (item.amount * normalizedUnit(item.unit).factor).takeIf { it.isFinite() && it >= 0 }
            } ?: 0.0
            val remaining = total?.let { (it - stock).coerceAtLeast(0.0) }
            val note = (group.map { it.note }.filter(String::isNotBlank) + buildList {
                if (total == null && first.amount != null) add("Total quantity is invalid; check the saved amounts.")
                if (matches.size > 1) add("Duplicate pantry amounts need review; no pantry deduction made.")
                if (total != null && stock > 0) add("Explicit pantry stock deducted.")
            }).distinct().joinToString(" ")
            ShoppingItem(key, first.name, remaining, first.unit, note)
        }.sortedWith(compareBy({ exactName(it.name) }, { it.unit }, { it.key }))
    }

    fun setPantryAmount(data: AppData, name: String, unit: String, amount: Double): AppData {
        val normalizedName = exactName(name)
        val canonicalUnit = normalizedUnit(unit)
        require(normalizedName.isNotBlank() && canonicalUnit.unit.isNotBlank()) { "Enter the exact ingredient name and a unit." }
        val value = amount * canonicalUnit.factor
        require(amount.isFinite() && amount >= 0 && value.isFinite()) { "Enter a finite pantry amount of zero or more." }
        val kept = data.pantryAmounts.filterNot { exactName(it.name) == normalizedName && normalizedUnit(it.unit).unit == canonicalUnit.unit }
        return data.copy(pantryAmounts = if (value == 0.0) kept else kept + PantryAmount(name.trim(), canonicalUnit.unit, value))
    }

    fun setShoppingBought(data: AppData, key: String, bought: Boolean): AppData {
        require(key.matches(Regex("shopping:[0-9a-f]{64}"))) { "Choose a shopping-list item." }
        return data.copy(boughtShopping = if (bought) (data.boughtShopping + key).distinct() else data.boughtShopping - key)
    }
}
