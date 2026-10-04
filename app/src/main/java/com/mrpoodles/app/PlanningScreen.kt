package com.mrpoodles.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun Double.pretty(): String = if (isFinite()) java.math.BigDecimal.valueOf(this).stripTrailingZeros().toPlainString() else "Unknown"

/** Content for the existing scrollable Meals screen; dialogs own their own bounded scroll containers. */
@Composable internal fun PlannerWorkspace(state: ScreenState, vm: PoodlesViewModel,
    selectedDay: MutableState<String> = rememberSaveable { mutableStateOf(LocalDate.now().toString()) }) {
    var selectedDate by selectedDay
    var dateDraft by rememberSaveable { mutableStateOf(selectedDate) }
    var range by rememberSaveable { mutableStateOf("Day") }
    var showRecipes by rememberSaveable { mutableStateOf(false) }
    var pickerDate by rememberSaveable { mutableStateOf(selectedDate) }
    var pickerSlot by rememberSaveable { mutableStateOf("Lunch") }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var loggingMeal by rememberSaveable(stateSaver = Saver<Meal?, String>(
        save = { it?.let { meal -> json.encodeToString(Meal.serializer(), meal) }.orEmpty() },
        restore = { it.takeIf(String::isNotBlank)?.let { saved -> json.decodeFromString<Meal>(saved) } }
    )) { mutableStateOf<Meal?>(null) }
    var pantryName by rememberSaveable { mutableStateOf<String?>(null) }
    var pantryUnit by rememberSaveable { mutableStateOf("") }
    var showPantry by rememberSaveable { mutableStateOf(false) }
    val start = requireNotNull(PlanningRules.parseDate(selectedDate))
    val days = if (range == "Week") 7 else 1
    val visibleDates = runCatching { PlanningRules.dates(start, days) }.getOrElse { listOf(selectedDate) }
    val validRange = visibleDates.size == days
    val meals = state.data.meals.filter { it.date in visibleDates }
        .sortedWith(compareBy({ it.date }, { PlanningRules.slots.indexOf(it.slot) }))
    fun select(date: LocalDate) {
        if (PlanningRules.parseDate(date.toString()) != null) {
            selectedDate = date.toString()
            dateDraft = selectedDate
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your meal planner", style = MaterialTheme.typography.headlineSmall)
        Text("A little plan, at your pace. Planned meals are separate from food you have eaten.", style = MaterialTheme.typography.bodyMedium)
        PlanningChoices(listOf("Day", "Week"), range) { range = it }
        OutlinedTextField(dateDraft, { dateDraft = it.take(10) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Start date · YYYY-MM-DD") }, singleLine = true,
            isError = PlanningRules.parseDate(dateDraft) == null,
            supportingText = { Text(if (PlanningRules.parseDate(dateDraft) == null) "Enter a real date, for example 2026-10-04." else "Choose any date, including earlier days.") })
        OutlinedButton(onClick = { PlanningRules.parseDate(dateDraft)?.let(::select) },
            enabled = PlanningRules.parseDate(dateDraft) != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Show this date") }
        PlanningActionFlow {
            OutlinedButton(onClick = { runCatching { start.minusDays(days.toLong()) }.getOrNull()?.let(::select) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous ${range.lowercase()}") }
            OutlinedButton(onClick = { select(LocalDate.now()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Today") }
            OutlinedButton(onClick = { runCatching { start.plusDays(days.toLong()) }.getOrNull()?.let(::select) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next ${range.lowercase()}") }
        }
        Text(if (days == 7) "Seven days: ${visibleDates.first()} to ${visibleDates.last()}" else start.format(DateTimeFormatter.ofPattern("EEEE, d MMM uuuu")),
            style = MaterialTheme.typography.titleMedium)
        if (!validRange) Text("This week extends beyond the supported calendar. Choose an earlier start date.", color = MaterialTheme.colorScheme.error)
        CozyAction("Add or replace a meal", {
            pickerDate = selectedDate; pickerSlot = "Lunch"; showRecipes = true
        }, Modifier.fillMaxWidth(), enabled = !state.loading && !state.storageError)
        OutlinedButton(onClick = { vm.previewPlan(selectedDate, days) },
            enabled = validRange && !state.busy && !state.loading && !state.storageError,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Preview Poodles' ${range.lowercase()} plan") }
        Text("Poodles uses distinct sourced recipes: ${days * 3} for this ${range.lowercase()}. More choices may need an internet search. Review the preview before replacing anything.", style = MaterialTheme.typography.bodySmall)

        state.planPreview?.let { preview ->
            val conflict = preview.profileRevision != state.data.revision ||
                state.data.meals.filter { it.date in preview.dates }.sortedBy { it.id } != preview.expectedMeals.sortedBy { it.id }
            CozyCard(color = Cream) {
                Text("Preview only · not applied", style = MaterialTheme.typography.titleLarge)
                Text("${preview.dates.firstOrNull().orEmpty()} to ${preview.dates.lastOrNull().orEmpty()}")
                Text("Apply replaces ${preview.expectedMeals.size} existing planned meal(s) on these dates, including snacks. Food diary entries stay as recorded.")
                preview.meals.groupBy { it.date }.forEach { (date, proposed) ->
                    Text(date, style = MaterialTheme.typography.titleMedium)
                    proposed.forEach { meal ->
                        Text("${meal.slot}: ${meal.recipe.title} · ${meal.portions.pretty()} serving(s)")
                        TextButton(onClick = { detailsId = meal.recipe.id }, modifier = Modifier.heightIn(min = 48.dp)) { Text("View ${meal.recipe.title}") }
                    }
                }
                Text("Suggestions, not a complete dietary prescription. Review the recipes, portions and actual ingredient labels.", style = MaterialTheme.typography.bodySmall)
                if (conflict) Text("Your profile or meals changed. Dismiss this preview and create a new one; your edits are kept.", color = MaterialTheme.colorScheme.error)
                CozyAction("Apply this preview", vm::applyPlanPreview, Modifier.fillMaxWidth(), enabled = !conflict && !state.busy && !state.storageError)
                OutlinedButton(onClick = vm::dismissPlanPreview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Keep my plan · dismiss preview") }
            }
        }

        if (meals.isEmpty()) CozyCard { Text("No meals planned for these dates yet. Choose a saved recipe or ask Poodles for a preview.") }
        meals.forEach { meal ->
            key(meal.id) {
                val issues = RecipeRules.validate(meal.recipe, state.data.profile, state.foods)
                CozyCard {
                    Text("${meal.date} · ${meal.slot}", style = MaterialTheme.typography.labelLarge)
                    Text(meal.recipe.title, style = MaterialTheme.typography.titleLarge)
                    Text("${meal.portions.pretty()} serving(s) · ${meal.mode}")
                    val logged = state.data.intake.find { it.mealId == meal.id }
                    if (logged != null) Text("Logged on ${logged.date} · log again to update the same entry", style = MaterialTheme.typography.bodySmall)
                    if (meal.revision != state.data.revision) Text("Profile changed since planning. Review this recipe before using it.", color = MaterialTheme.colorScheme.error)
                    issues.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    PlanningActionFlow {
                        TextButton(onClick = { detailsId = meal.recipe.id }, modifier = Modifier.heightIn(min = 48.dp)) { Text("View recipe") }
                        TextButton(onClick = { loggingMeal = meal }, enabled = !state.loading && !state.storageError,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("plan_log_${meal.id}")) { Text("Log eaten") }
                        TextButton(onClick = { editId = meal.id }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Move / portions") }
                        TextButton(onClick = { pickerDate = meal.date; pickerSlot = meal.slot; showRecipes = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Replace recipe") }
                        TextButton(onClick = { vm.removeMeal(meal.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove meal") }
                    }
                }
            }
        }

        val shopping = PlanningRules.shopping(meals, state.data.pantryAmounts)
        CozyCard(color = Sage) {
            Text("Shopping · ${range.lowercase()}", style = MaterialTheme.typography.titleLarge)
            Text("${visibleDates.first()}${if (days == 7) " to ${visibleDates.last()}" else ""}. Quantities use planned servings. Only exact-name, compatible-unit pantry amounts are deducted.", style = MaterialTheme.typography.bodySmall)
            if (shopping.isEmpty()) Text("Add a planned meal to make a shopping list.")
            shopping.forEach { item ->
                val bought = item.key in state.data.boughtShopping
                val displayName = if (item.note.startsWith("Saved catalog")) state.foods.find { it.id == item.name }?.name ?: item.name else item.name
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(bought, role = Role.Checkbox, onValueChange = { vm.setShoppingBought(item.key, it) }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bought, onCheckedChange = null)
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(displayName)
                        Text("${item.amount?.pretty() ?: "Unknown quantity"} ${item.unit}".trim(), style = MaterialTheme.typography.bodyMedium)
                        if (item.amount == 0.0) Text("Covered by pantry", style = MaterialTheme.typography.bodySmall)
                        if (bought) Text("Bought", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodySmall)
                if (item.unit.isNotBlank() && !item.note.contains("catalog ingredient")) TextButton(onClick = {
                    pantryName = item.name; pantryUnit = item.unit; showPantry = true
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Set pantry amount for ${item.name}") }
                HorizontalDivider()
            }
            Text("Bought checks are shared for the same ingredient and unit. Checking an item does not add pantry stock. Unknown quantities and incompatible units stay separate.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { pantryName = null; pantryUnit = ""; showPantry = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add exact pantry stock") }
            state.data.pantryAmounts.forEach { stock ->
                TextButton(onClick = { pantryName = stock.name; pantryUnit = stock.unit; showPantry = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Edit pantry: ${stock.name} · ${stock.amount.pretty()} ${stock.unit}")
                }
            }
        }
    }

    if (showRecipes) SavedPlanningRecipes(state, pickerDate, pickerSlot, { showRecipes = false }, { detailsId = it.id }, vm::planRecipe)
    loggingMeal?.let { meal -> LogPlannedMeal(meal, state.data.intake.find { it.mealId == meal.id }, { loggingMeal = null }) { portions, operationId, onSaved ->
        vm.logMeal(meal, portions, operationId, onSaved)
    } }
    editId?.let { id ->
        val meal = state.data.meals.find { it.id == id }
        if (meal == null) LaunchedEffect(id) { editId = null }
        else EditPlannedMeal(meal, state.data.meals, { editId = null }, vm::moveMeal)
    }
    if (showPantry) PlanningPantryEditor(state.data, pantryName.orEmpty(), pantryUnit, { showPantry = false }, vm::setPantryAmount)
    detailsId?.let { id ->
        val recipe = (state.data.recipes + state.data.meals.map { it.recipe } + state.planPreview?.meals.orEmpty().map { it.recipe })
            .find { it.id == id }
        PlanningDialog("Recipe details", { detailsId = null }) {
            if (recipe != null) RecipeCard(recipe, state.foods) else Text("This recipe is no longer available. Close this view and choose another recipe.")
            OutlinedButton(onClick = { detailsId = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back to planner") }
        }
    }
}

@Composable private fun LogPlannedMeal(meal: Meal, existing: Intake?, dismiss: () -> Unit,
    save: (Double, String, () -> Unit) -> Unit) {
    var portions by rememberSaveable(meal.id) { mutableStateOf((existing?.portions ?: meal.portions).pretty()) }
    val amount = portions.toDoubleOrNull()
    val valid = amount != null && PlanningRules.validPortions(amount)
    val operationId = rememberSaveable(meal.id, portions) { java.util.UUID.randomUUID().toString() }
    val latestOperation by rememberUpdatedState(operationId)
    PlanningDialog("Log ${meal.recipe.title}", dismiss) {
        Text(if (existing == null) "Log food eaten today (${LocalDate.now()}). The planned date is ${meal.date}." else "Update the same diary entry on ${existing.date}.")
        Text("Record what you actually ate, even if it differs from the plan. Use Edit in Food diary to change the date.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(portions, { portions = it.take(16) }, Modifier.fillMaxWidth().testTag("planned_log_portions"),
            label = { Text("Servings eaten (e.g. 0.5)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !valid,
            supportingText = { Text("More than 0, up to 20. Missing nutrition stays unknown.") })
        CozyAction("Save food log", { amount?.let { save(it, operationId) { if (latestOperation == operationId) dismiss() } } },
            Modifier.fillMaxWidth().testTag("planned_log_save"), enabled = valid)
        OutlinedButton(onClick = dismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
    }
}

@Composable private fun SavedPlanningRecipes(state: ScreenState, initialDate: String, initialSlot: String,
    dismiss: () -> Unit, view: (Recipe) -> Unit, save: (Recipe, String, String) -> Unit) {
    var date by rememberSaveable { mutableStateOf(initialDate) }
    var slot by rememberSaveable { mutableStateOf(initialSlot) }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.data.meals, submitted) {
        if (submitted != null && state.data.meals.any { it.date == date && it.slot == slot && it.recipe.id == submitted }) dismiss()
    }
    val occupied = state.data.meals.filter { it.date == date && it.slot == slot }
    PlanningDialog("Choose a saved recipe", dismiss) {
        OutlinedTextField(date, { date = it.take(10); submitted = null }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Meal date · YYYY-MM-DD") }, isError = PlanningRules.parseDate(date) == null,
            supportingText = { if (PlanningRules.parseDate(date) == null) Text("Enter a real date.") })
        PlanningChoices(PlanningRules.slots, slot) { slot = it; submitted = null }
        if (occupied.isNotEmpty()) Text("Replace ${occupied.joinToString { it.recipe.title }} in this slot. Other meals and diary entries stay as recorded.")
        if (occupied.size > 1) Text("Several saved meals share this slot. Move or remove one first.", color = MaterialTheme.colorScheme.error)
        if (state.data.recipes.isEmpty()) Text("Your shelf is empty. Open Find a recipe, review its sources, and save it first.")
        state.data.recipes.forEach { recipe ->
            val issues = RecipeRules.validate(recipe, state.data.profile, state.foods)
            Text(recipe.title, style = MaterialTheme.typography.titleMedium)
            if (issues.isNotEmpty()) Text(issues.joinToString(" "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { view(recipe) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("View recipe and sources") }
            CozyAction(if (occupied.isEmpty()) "Add ${recipe.title}" else "Replace with ${recipe.title}", {
                submitted = recipe.id; save(recipe, date, slot)
            }, Modifier.fillMaxWidth(), enabled = issues.isEmpty() && occupied.size <= 1 && PlanningRules.parseDate(date) != null && !state.storageError)
            HorizontalDivider()
        }
        OutlinedButton(onClick = dismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
    }
}

@Composable private fun EditPlannedMeal(meal: Meal, allMeals: List<Meal>, dismiss: () -> Unit,
    save: (String, String, String, Double) -> Unit) {
    var date by rememberSaveable(meal.id) { mutableStateOf(meal.date) }
    var slot by rememberSaveable(meal.id) { mutableStateOf(meal.slot) }
    var portions by rememberSaveable(meal.id) { mutableStateOf(meal.portions.toString()) }
    var submitted by rememberSaveable(meal.id) { mutableStateOf(false) }
    val amount = portions.toDoubleOrNull()
    val occupied = allMeals.any { it.id != meal.id && it.date == date && it.slot == slot }
    LaunchedEffect(meal, submitted) {
        if (submitted && meal.date == date && meal.slot == slot && meal.portions == amount) dismiss()
    }
    PlanningDialog("Move or change portions", dismiss) {
        Text(meal.recipe.title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(date, { date = it.take(10); submitted = false }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Meal date · YYYY-MM-DD") }, isError = PlanningRules.parseDate(date) == null,
            supportingText = { if (PlanningRules.parseDate(date) == null) Text("Enter a real date.") })
        PlanningChoices(PlanningRules.slots, slot) { slot = it; submitted = false }
        OutlinedTextField(portions, { portions = it; submitted = false }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Planned servings, e.g. 0.5 or 2") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = amount == null || !PlanningRules.validPortions(amount), supportingText = { Text("More than 0, up to 20. Shopping quantities scale with servings.") })
        if (occupied) Text("This slot already has another meal. Choose an empty slot.", color = MaterialTheme.colorScheme.error)
        CozyAction("Save meal changes", { amount?.let { submitted = true; save(meal.id, date, slot, it) } }, Modifier.fillMaxWidth(),
            enabled = PlanningRules.parseDate(date) != null && amount != null && PlanningRules.validPortions(amount) && !occupied)
        OutlinedButton(onClick = dismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
    }
}

@Composable private fun PlanningPantryEditor(data: AppData, initialName: String, initialUnit: String,
    dismiss: () -> Unit, save: (String, String, Double) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var unit by rememberSaveable { mutableStateOf(initialUnit) }
    var amount by rememberSaveable { mutableStateOf(data.pantryAmounts.find { it.name == initialName && it.unit == initialUnit }?.amount?.toString().orEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val value = amount.toDoubleOrNull()
    val expected = value?.let { runCatching { PlanningRules.setPantryAmount(data, name, unit, it).pantryAmounts }.getOrNull() }
    LaunchedEffect(data.pantryAmounts, submitted) {
        if (submitted && expected != null && data.pantryAmounts == expected) dismiss()
    }
    PlanningDialog("Exact pantry stock", dismiss) {
        Text("Enter the amount you actually have. Names must match the shopping list. g/kg and ml/l convert; other units stay separate. Enter 0 to remove stock.")
        OutlinedTextField(name, { name = it; submitted = false }, Modifier.fillMaxWidth(), label = { Text("Exact ingredient name") })
        OutlinedTextField(unit, { unit = it; submitted = false }, Modifier.fillMaxWidth(), label = { Text("Unit, e.g. g, kg, ml, l or cup") }, singleLine = true)
        OutlinedTextField(amount, { amount = it; submitted = false }, Modifier.fillMaxWidth(), label = { Text("Amount in this unit") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = value == null || expected == null)
        CozyAction("Save pantry stock", { value?.let { submitted = true; save(name, unit, it) } }, Modifier.fillMaxWidth(), enabled = expected != null)
        OutlinedButton(onClick = dismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PlanningActionFlow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable private fun PlanningChoices(choices: List<String>, selected: String, select: (String) -> Unit) {
    PlanningActionFlow { choices.forEach { choice ->
        FilterChip(selected == choice, { select(choice) }, label = { Text(choice) }, modifier = Modifier.heightIn(min = 48.dp))
    } }
}

@Composable internal fun PlanningDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .85f }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.imePadding().padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
            }
        }
    }
}
