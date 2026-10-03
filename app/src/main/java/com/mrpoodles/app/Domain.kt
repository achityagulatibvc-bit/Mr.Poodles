package com.mrpoodles.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.time.LocalDate
import java.util.UUID

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable data class Restriction(
    val name: String, val kind: String = "Intolerance", val aliases: String = "",
    val notes: String = "", val enabled: Boolean = true
)
@Serializable data class Environment(
    val equipment: List<String> = listOf("Kettle", "Fridge"),
    val mealSource: String = "Mess meals and self-cooking", val budget: String = "",
    val maxMinutes: Int = 20, val pantry: String = "", val rules: String = "",
    val workoutEquipment: String = "None", val workoutSpace: String = "Small room; quiet exercises"
)
@Serializable data class Profile(
    val name: String = "", val onboarding: Boolean = false, val mode: String = "Hostel",
    val diet: String = "Not specified", val cuisine: String = "", val goal: String = "General wellbeing",
    val injuries: String = "", val experience: String = "Beginner", val calorieTarget: Int? = null,
    val sounds: Boolean = false, val gentleMotion: Boolean = true, val cloudConsent: Boolean = true,
    val rememberChats: Boolean = false, val rememberComfort: Boolean = false,
    val restrictions: List<Restriction> = listOf(
        Restriction("Lactose", aliases = "milk, milk powder, whey, cream, butter, cheese, paneer, yogurt, yoghurt, lactose, ghee, buttermilk, curd"),
        Restriction("Soy", aliases = "soy, soya, soybean, soybeans, tofu, tempeh, edamame, miso, shoyu, tamari, soy lecithin"),
        Restriction("Spice", aliases = "chilli, chili, chillies, chilies, cayenne, jalapeno, hot sauce, red pepper, black pepper, white pepper, paprika, peppercorn, capsicum, garam masala", notes = "Specific triggers are not confirmed. Edit this list to match your experience.")
    ),
    val hostel: Environment = Environment(),
    val home: Environment = Environment(listOf("Stove", "Fridge"), "Home cooking", maxMinutes = 45, workoutSpace = "At home")
) {
    fun environment() = if (mode == "Hostel") hostel else home
}

@Serializable data class Food(
    val id: String, val name: String, val fdcId: Int, val description: String, val tags: List<String>,
    val ready: Boolean, val kcal: Double, val protein: Double? = null, val carbs: Double? = null,
    val fat: Double? = null, val fiber: Double? = null, val source: String
)
@Serializable data class Ingredient(val id: String, val grams: Double)
@Serializable data class PreparationStep(val action: String, val ingredients: List<String>)
@Serializable data class Recipe(
    val title: String, val ingredients: List<Ingredient>, val method: String = "assemble",
    val minutes: Int = 10, val servings: Int = 1, val note: String = "",
    val id: String = UUID.randomUUID().toString(), val aiGenerated: Boolean = false,
    val profileRevision: Int = 0, val preparation: List<PreparationStep> = emptyList()
)
@Serializable data class Meal(val id: String = UUID.randomUUID().toString(), val date: String,
    val slot: String, val recipe: Recipe, val mode: String, val revision: Int)
@Serializable data class Intake(val id: String = UUID.randomUUID().toString(), val date: String,
    val name: String, val kcal: Double?, val protein: Double? = null, val carbs: Double? = null,
    val fat: Double? = null, val portions: Double = 1.0, val source: String = "User estimate",
    val mealId: String? = null)
@Serializable data class Message(val role: String, val text: String, val id: String = UUID.randomUUID().toString(),
    val mood: String = "listening", val gift: String = "none")
@Serializable data class CheckRecord(val text: String, val verdict: String, val date: String = LocalDate.now().toString())
@Serializable data class Workout(val title: String, val exerciseIds: List<String>, val rounds: Int = 1,
    val mode: String = "Hostel", val revision: Int = 0, val completedDate: String? = null)
@Serializable data class AppData(val profile: Profile = Profile(), val revision: Int = 0,
    val meals: List<Meal> = emptyList(), val intake: List<Intake> = emptyList(),
    val recipes: List<Recipe> = emptyList(), val messages: List<Message> = emptyList(),
    val checks: List<CheckRecord> = emptyList(), val workout: Workout? = null,
    val comfortMemories: List<ComfortMemory> = emptyList(), val recentRecipes: List<Recipe> = emptyList())
@Serializable data class ProfileProposal(val field: String, val value: String, val explanation: String = "")

data class Finding(val trigger: String, val evidence: String, val note: String)
data class FoodCheck(val findings: List<Finding>, val uncertainties: List<String>) {
    val verdict get() = when { findings.isNotEmpty() -> "Trigger found"; uncertainties.isNotEmpty() -> "Needs clarification"; else -> "No listed trigger found" }
}

object FoodRules {
    fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
    fun contains(text: String, term: String): Boolean {
        val needle = normalize(term)
        return needle.isNotEmpty() && (" ${normalize(text)} ").contains(" $needle ")
    }
    fun check(text: String, profile: Profile, complete: Boolean = false): FoodCheck {
        val warnings = mutableListOf<String>()
        if (text.isBlank()) warnings += "No ingredients supplied."
        if (!complete) warnings += "Confirm the complete ingredient list and allergen statements."
        if (Regex("[^\\x00-\\x7F]").containsMatchIn(text)) warnings += "Foreign text may need translation; translation can miss triggers."
        listOf("spices", "spice blend", "flavouring", "flavoring", "natural flavors", "seasoning", "lecithin", "vegetable protein")
            .filter { contains(text, it) }.forEach { warnings += "Unspecified '$it': ask for the ingredient source." }
        if (contains(text, "may contain") || contains(text, "shared equipment")) warnings += "An advisory statement mentions possible cross-contact."
        val findings = profile.restrictions.filter { it.enabled }.flatMap { restriction ->
            (listOf(restriction.name) + restriction.aliases.split(',')).map(String::trim).filter(String::isNotEmpty)
                .distinctBy(::normalize).filter { contains(text, it) }.map { term ->
                    Finding(restriction.name, term, "Matches your ${restriction.kind.lowercase()}. ${restriction.notes}".trim())
                }
        }
        if (contains(text, "lactose free") && findings.any { it.trigger.equals("Lactose", true) }) {
            warnings += "Lactose-free claim found. Dairy matches are conservative; check the actual product and your tolerance."
        }
        return FoodCheck(findings, warnings.distinct())
    }
    fun allowed(food: Food, profile: Profile): Boolean {
        if (check((listOf(food.name) + food.tags).joinToString(", "), profile, true).findings.isNotEmpty()) return false
        if (profile.diet == "Vegan" && food.tags.any { it in listOf("meat", "chicken", "egg", "milk") }) return false
        if (profile.diet == "Vegetarian" && food.tags.any { it in listOf("meat", "chicken", "egg") }) return false
        return true
    }
}

data class Nutrients(val kcal: Double, val protein: Double?, val carbs: Double?, val fat: Double?)
object Nutrition {
    fun calculate(recipe: Recipe, foods: List<Food>, portions: Double = 1.0): Nutrients {
        require(portions.isFinite() && portions > 0 && portions <= 20)
        require(recipe.servings in 1..8)
        val parts = recipe.ingredients.map { item ->
            require(item.grams.isFinite() && item.grams > 0 && item.grams <= 2000)
            (foods.find { it.id == item.id } ?: error("Unknown ingredient ${item.id}")) to (item.grams / 100.0 * portions / recipe.servings)
        }
        fun total(selector: (Food) -> Double?): Double? = if (parts.any { selector(it.first) == null }) null else parts.sumOf { selector(it.first)!! * it.second }
        return Nutrients(parts.sumOf { it.first.kcal * it.second }, total { it.protein }, total { it.carbs }, total { it.fat })
    }
}

object RecipeRules {
    fun validate(recipe: Recipe, profile: Profile, foods: List<Food>): List<String> = buildList {
        if (recipe.title.isBlank()) add("Recipe needs a title.")
        if (recipe.servings !in 1..8 || recipe.ingredients.size !in 1..12) add("Invalid serving or ingredient count.")
        if (recipe.minutes !in 1..profile.environment().maxMinutes) add("Recipe exceeds your active preparation-time limit.")
        if (recipe.method !in listOf("assemble", "soak", "warm")) add("Unknown preparation method.")
        if (recipe.method == "soak" && "Fridge" !in profile.environment().equipment) add("Cold soaking requires a fridge.")
        if (recipe.method == "warm" && profile.environment().equipment.none { it == "Stove" || it == "Microwave" }) add("Warming requires a stove or microwave; a kettle is for water only.")
        recipe.ingredients.forEach { ingredient ->
            val food = foods.find { it.id == ingredient.id }
            if (food == null) add("Unknown ingredient: ${ingredient.id}.")
            else {
                if (!FoodRules.allowed(food, profile)) add("${food.name} conflicts with your restrictions or diet.")
                if (!food.ready && recipe.method == "assemble") add("${food.name} requires preparation, not direct assembly.")
                if (food.id == "tofu") add("Tofu preparation is product-specific; choose another ingredient.")
            }
            if (!ingredient.grams.isFinite() || ingredient.grams <= 0 || ingredient.grams > 2000) add("Invalid ingredient quantity.")
        }
        if (recipe.ingredients.map { it.id }.distinct().size != recipe.ingredients.size) add("Duplicate ingredients.")
        if (recipe.ingredients.any { it.id == "chia" } && recipe.method != "soak") add("Chia must be fully soaked before eating.")
        if (recipe.preparation.size > 8) add("Too many preparation steps.")
        recipe.preparation.forEach { step ->
            if (step.action !in setOf("cut", "mash", "mix", "layer", "spread", "warm", "soak")) add("Unknown preparation step.")
            if (step.ingredients.isEmpty() || step.ingredients.any { id -> recipe.ingredients.none { it.id == id } }) add("Preparation references an ingredient outside the recipe.")
            if (step.action == "warm" && recipe.method != "warm") add("Heating step needs a warming method.")
            if (step.action == "soak" && recipe.method != "soak") add("Soaking step needs a soaking method.")
        }
    }
    fun steps(recipe: Recipe, foods: List<Food> = emptyList()): String {
        val safety = when (recipe.method) {
        "soak" -> "Use food-grade rolled oats if oats are listed. Combine with enough drinking water to fully submerge the oats/seeds. Refrigerate for at least 4 hours; chia must be fully hydrated. Add washed fruit and other ready-to-eat ingredients before serving. Preparation time excludes soaking."
        "warm" -> "Use only the already-cooked ingredients listed. Wash produce. Warm the cooked ingredients on a stove or in a microwave until steaming throughout; stir well. Add washed raw produce and other ready-to-eat ingredients after warming. Do not cook food inside a water-only kettle."
        else -> "Wash produce. Use only ready-to-eat or fully cooked ingredients. Measure the listed amounts, cut into bite-sized pieces, and assemble. Check every packaged ingredient for your triggers."
        } + " Keep perishable foods refrigerated. Do not use leftovers with uncertain storage history."
        if (recipe.preparation.isEmpty()) return safety
        val specific = recipe.preparation.mapIndexed { index, step ->
            val ingredients = step.ingredients.joinToString(", ") { id -> foods.find { it.id == id }?.name ?: id }
            val action = when (step.action) {
                "cut" -> "Wash and cut $ingredients into small pieces."
                "mash" -> "Mash $ingredients with a fork."
                "mix" -> "Gently combine $ingredients."
                "layer" -> "Layer $ingredients in the listed order."
                "spread" -> "Spread the first ingredient over the remaining ingredients: $ingredients."
                "warm" -> "Warm $ingredients on a stove or in a microwave until steaming; stir well."
                "soak" -> "Cover $ingredients with drinking water and refrigerate for at least 4 hours."
                else -> "Review this preparation step."
            }
            "${index + 1}. $action"
        }.joinToString("\n")
        return "$specific\n\n$safety"
    }
}

data class Exercise(val id: String, val name: String, val instruction: String, val seconds: Int, val equipment: String? = null)
val exercises = listOf(
    Exercise("breathe", "Easy breathing", "Sit comfortably. Breathe at a natural pace; do not hold your breath.", 60),
    Exercise("walk", "Gentle walk", "Walk at a comfortable pace in a clear, safe space.", 180),
    Exercise("shoulders", "Shoulder rolls", "Make small, comfortable circles. Keep the movement pain-free.", 45),
    Exercise("ankles", "Ankle circles", "Sit down and gently circle each ankle within a comfortable range.", 45),
    Exercise("wall", "Wall push-ups", "Use a stable wall. Keep feet secure and bend elbows slowly. Stop for pain.", 40),
    Exercise("march", "Quiet marching", "March slowly in place, lifting feet gently. Use support if needed.", 60)
)
object WorkoutRules {
    fun validate(workout: Workout, profile: Profile): Boolean = workout.rounds in 1..3 &&
        workout.exerciseIds.size in 1..6 && workout.exerciseIds.distinct().size == workout.exerciseIds.size &&
        workout.exerciseIds.all { id -> exercises.any { it.id == id } } &&
        profile.injuries.isBlank()
}

object IntakeRules {
    fun upsert(entries: List<Intake>, entry: Intake): List<Intake> = entries.filterNot {
        it.id == entry.id || (entry.mealId != null && it.mealId == entry.mealId)
    } + entry
}
