package com.mrpoodles.app

import kotlinx.serialization.Serializable

@Serializable data class RecipeBatch(val recipes: List<Recipe>)
@Serializable data class GeneratedMenu(val days: List<MenuDay>)
@Serializable data class MenuDay(val breakfast: Int, val lunch: Int, val dinner: Int)
data class RecipeIntent(val required: Set<String>, val excluded: Set<String>)

object RecipeGeneration {
    private val aliases = mapOf(
        "oats" to listOf("oat", "oats", "oatmeal"), "banana" to listOf("banana", "bananas"),
        "spinach" to listOf("spinach", "palak"), "pasta" to listOf("pasta", "macaroni", "spaghetti"),
        "potato" to listOf("potato", "potatoes", "aloo"), "sweetpotato" to listOf("sweet potato", "sweet potatoes"),
        "pea" to listOf("peas", "green pea"), "corn" to listOf("corn", "sweetcorn"),
        "chickpea" to listOf("chickpeas", "chickpea", "chana"), "lentil" to listOf("lentils", "lentil", "dal"),
        "kidneybean" to listOf("kidney beans", "rajma"), "brownrice" to listOf("brown rice"),
        "rice" to listOf("rice"), "bread" to listOf("bread", "sandwich", "toast"),
        "egg" to listOf("egg", "eggs"), "chicken" to listOf("chicken"), "apple" to listOf("apple", "apples"),
        "peanut" to listOf("peanut butter"), "chia" to listOf("chia"), "cucumber" to listOf("cucumber", "cucumbers"),
        "tomato" to listOf("tomato", "tomatoes"), "carrot" to listOf("carrot", "carrots"),
        "mushroom" to listOf("mushroom", "mushrooms"), "avocado" to listOf("avocado", "avocados"),
        "onion" to listOf("onion", "onions"), "cabbage" to listOf("cabbage"), "quinoa" to listOf("quinoa"),
        "almond" to listOf("almond", "almonds"), "orange" to listOf("orange", "oranges"),
        "strawberry" to listOf("strawberry", "strawberries"), "lemon" to listOf("lemon"),
        "yogurt" to listOf("yogurt", "yoghurt"), "tofu" to listOf("tofu"))

    fun intent(request: String): RecipeIntent {
        val text = request.lowercase()
        val required = mutableSetOf<String>(); val excluded = mutableSetOf<String>()
        aliases.forEach { (id, names) -> names.forEach { name ->
            Regex("\\b${Regex.escape(name)}\\b").findAll(text).forEach matchLoop@{ match ->
                if (id == "rice" && text.substring(0, match.range.first).endsWith("brown ")) return@matchLoop
                if (id == "potato" && text.substring(0, match.range.first).endsWith("sweet ")) return@matchLoop
                val before = text.substring(0, match.range.first).substringAfterLast('.').substringAfterLast(';').substringAfterLast("but ").takeLast(80)
                val negative = Regex("\\b(without|except|avoid|no|not|skip|excluding|don't want|do not want)\\b(?:\\W+\\w+){0,5}\\W*$").containsMatchIn(before)
                if (negative) excluded += id else required += id
            }
        } }
        return RecipeIntent(required - excluded, excluded)
    }
    // Ignore titles and tiny quantity changes: a renamed spinach bowl is still the same suggestion.
    fun signature(recipe: Recipe): String = recipe.ingredients.map { it.id }.filterNot { it in setOf("oil", "lemon") }.sorted().joinToString("|")
    fun accepts(recipe: Recipe, intent: RecipeIntent, recent: List<Recipe>): Boolean {
        val ids = recipe.ingredients.map { it.id }.toSet()
        return ids.containsAll(intent.required) && ids.intersect(intent.excluded).isEmpty() && recent.none { signature(it) == signature(recipe) }
    }
    fun instructions(foods: List<Food>, recent: List<Recipe>, count: Int, intent: RecipeIntent): String = """
        Invent $count genuinely different, appetizing recipes for the user's request. Do not choose a preset recipe.
        The requested dish/ingredients take priority. Do not answer every request with oats, spinach or a bowl.
        Use only these ingredient IDs and their specified preparation state:
        ${foods.joinToString("\n") { "${it.id}: ${it.name}" }}
        Required ingredient IDs: ${intent.required.joinToString().ifBlank { "choose to suit the request" }}.
        Excluded ingredient IDs: ${intent.excluded.joinToString().ifBlank { "none beyond the profile restrictions" }}.
        Recent suggestions to avoid repeating, even under a different name:
        ${recent.takeLast(8).joinToString("\n") { "${it.title}: ${signature(it)}" }.ifBlank { "None yet" }}
        Vary the main ingredients, textures, and preparation, not just the titles or portions. A sandwich should use bread; pasta should use pasta.
        All catalog rice, pasta, potatoes, legumes, eggs, chicken and mushrooms marked cooked are ALREADY cooked. Do not assume raw versions are ready.
        Available methods: assemble (ready-to-eat foods), soak (oats/chia plus drinking water, refrigerated at least 4 hours), warm (stove/microwave only).
        Chia requires soak. Oats require soak or warm. A kettle only boils water; it is not a pan.
        Return one JSON object with a recipes array. Each recipe has:
        title (specific dish name), ingredients (2-7 objects, each with id from the catalog and grams), method, minutes (active time), servings (1 or 2),
        preparation (2-6 ordered objects, each with action and ingredients, an array of IDs FROM THAT RECIPE).
        Actions: cut, mash, mix, layer, spread, warm, soak. Spread means first ingredient over the others.
        Warm/soak actions must match the recipe method. Choose useful recipe-specific steps, not a generic list.
        Do not invent ingredient IDs, nutrition numbers, appliances, or ingredients in the title that are absent from the recipe.
    """.trimIndent()
}
