@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mrpoodles.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private fun Double.pretty() = String.format(Locale.US, "%.0f", this)
private fun Double?.knownNutrient() = this?.takeIf { it.isFinite() && it >= 0 }

internal enum class HomeAction { CHECK, RECIPE, WORKOUT, FOOD_LOG, PLAN, CHAT }

@Composable internal fun TodayScreen(state: ScreenState, navigate: (HomeAction) -> Unit) {
    val p = state.data.profile
    val today = LocalDate.now().toString()
    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.labelLarge, color = Rose)
    Text("A little care,\njust for you.", style = MaterialTheme.typography.headlineLarge)
    Text(if (p.name.isBlank()) "Come in. There's no rush here." else "Come in, ${p.name}. There's no rush here.", color = Plum)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallAction("Can I eat this?", "Check ingredients", Icons.Rounded.DocumentScanner, Blush, Modifier.weight(1f)) { navigate(HomeAction.CHECK) }
        SmallAction("Find a recipe", "Tell Poodles your idea", Icons.Rounded.Restaurant, Cream, Modifier.weight(1f)) { navigate(HomeAction.RECIPE) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallAction("Find a workout", "Movement at your pace", Icons.Rounded.Spa, Sage, Modifier.weight(1f)) { navigate(HomeAction.WORKOUT) }
        SmallAction("Log what I ate", "Add food to your diary", Icons.Rounded.EditNote, Lavender, Modifier.weight(1f)) { navigate(HomeAction.FOOD_LOG) }
    }
    CozyAction("Talk to Poodles", { navigate(HomeAction.CHAT) }, Modifier.fillMaxWidth())
    val logs = state.data.intake.filter { it.date == today }
    val total = logs.sumOf { it.kcal.knownNutrient() ?: 0.0 }
    CozyCard(color = Color(0xFFF0EAF5)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FavoriteBorder, null, tint = Rose)
            Text("Today's nourishment", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(total.pretty(), fontSize = 35.sp, fontWeight = FontWeight.Bold)
            Text(" kcal logged", Modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        p.calorieTarget?.takeIf { it > 0 }?.let { target ->
            LinearProgressIndicator(progress = { (total / target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Rose)
            Text("Your optional target: $target kcal. Food is not a score.", style = MaterialTheme.typography.bodySmall)
        }
        if (logs.any { it.kcal.knownNutrient() == null }) Text("Some entries have unknown calories; the total is incomplete.")
        Text("${logs.size} entries · estimates based on portions", style = MaterialTheme.typography.bodySmall)
    }
    SectionTitle("Your little plans", "A softer rhythm for the day")
    val nextMeal = state.data.meals.filter { meal ->
        runCatching { !LocalDate.parse(meal.date).isBefore(LocalDate.now()) }.getOrDefault(false) &&
            state.data.intake.none { it.mealId == meal.id }
    }.sortedWith(compareBy<Meal> { it.date }.thenBy { listOf("Breakfast", "Lunch", "Dinner", "Snack").indexOf(it.slot) }).firstOrNull()
    CozyCard {
        if (nextMeal == null) {
            Text("A fresh page for your meals", style = MaterialTheme.typography.titleMedium)
            Text("Let's plan something that fits your ${p.mode.lowercase()} day.")
        } else {
            Text("Next planned meal", style = MaterialTheme.typography.titleMedium)
            Text("${nextMeal.date} · ${nextMeal.slot}")
            Text(nextMeal.recipe.title)
            Text("Not logged as eaten yet.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { navigate(HomeAction.PLAN) }) { Text("Open meal planner") }
    }
    CozyCard(color = Sage) {
        Text("A small moment to move", style = MaterialTheme.typography.titleMedium)
        Text(state.data.workout?.title ?: "Gentle movement, at your own pace.")
        TextButton(onClick = { navigate(HomeAction.WORKOUT) }) { Text("Open workout") }
    }
    CozyScene(Modifier.fillMaxWidth().height(150.dp), "happy", p.mode == "Hostel", compact = true)
    PoodlesNote()
}

@Composable private fun SmallAction(title: String, subtitle: String, icon: ImageVector, color: Color, modifier: Modifier, click: () -> Unit) {
    Surface(onClick = click, modifier = modifier.heightIn(min = 80.dp), color = color, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = Rose)
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable internal fun MealsScreen(state: ScreenState, vm: PoodlesViewModel,
    selectedPage: MutableState<String> = rememberSaveable { mutableStateOf("Plan") },
    openLog: Boolean = false, onLogOpened: () -> Unit = {}, onFindRecipe: () -> Unit = {}, onFoodLog: () -> Unit = {}) {
    var page by selectedPage
    val planningDate = rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var recipeDate by planningDate
    val features by vm.featureStates.collectAsStateWithLifecycle()
    val selectedDate = features[Feature.FOOD_LOG]?.selectedDate ?: LocalDate.now().toString()
    var dateDraft by rememberSaveable(selectedDate) { mutableStateOf(selectedDate) }
    var selectedSlot by rememberSaveable { mutableStateOf("Lunch") }
    var custom by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openLog) { if (openLog) { custom = true; onLogOpened() } }
    var edit by rememberSaveable(stateSaver = Saver<Intake?, String>(
        save = { it?.let { value -> json.encodeToString(Intake.serializer(), value) }.orEmpty() },
        restore = { it.takeIf(String::isNotBlank)?.let { value -> json.decodeFromString<Intake>(value) } }
    )) { mutableStateOf<Intake?>(null) }
    var recipeDetails by remember { mutableStateOf<Recipe?>(null) }
    SectionTitle("Something nourishing", "Recipes, little plans, and room for what you love.")
    ChoiceRow("", listOf("Plan", "Recipes", "Food diary"), page) { page = it }
    when (page) {
        "Plan" -> PlannerWorkspace(state, vm, planningDate)
        "Recipes" -> {
            CozyAction("Find a recipe", onFindRecipe, Modifier.fillMaxWidth())
            state.draft?.let { recipe ->
                RecipeCard(recipe, state.foods)
                Text("Review the source, actual packages, cooking conditions and portions before saving.", style = MaterialTheme.typography.bodySmall)
                CozyAction("Save this recipe", vm::saveDraft)
            }
            SectionTitle("Your recipe shelf")
            Text("Your saved recipes and adaptations. Choose a date and meal slot to add one to your plan.", style = MaterialTheme.typography.bodySmall)
            ChoiceRow("Meal", listOf("Breakfast", "Lunch", "Dinner", "Snack"), selectedSlot) { selectedSlot = it }
            Row(Modifier.horizontalScroll(rememberScrollState())) { repeat(7) { offset ->
                val day = LocalDate.now().plusDays(offset.toLong())
                FilterChip(recipeDate == day.toString(), { recipeDate = day.toString() }, label = { Text(day.format(DateTimeFormatter.ofPattern("EEE d"))) }, modifier = Modifier.padding(end = 6.dp))
            } }
            state.data.recipes.forEach { recipe ->
                val issues = RecipeRules.validate(recipe, state.data.profile, state.foods)
                CozyCard {
                    Text(if (recipe.aiGenerated) "CREATED FOR YOU" else "SAVED RECIPE", color = Rose, style = MaterialTheme.typography.labelSmall)
                    Text(recipe.title, style = MaterialTheme.typography.titleLarge)
                    Text("${Nutrition.calculateOrNull(recipe, state.foods)?.kcal?.pretty() ?: "Unknown"} kcal / serving · ${recipe.minutes} min active")
                    if (issues.isNotEmpty()) Text(issues.joinToString(" "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { recipeDetails = recipe }) { Text("View") }
                        TextButton(enabled = issues.isEmpty(), onClick = { vm.planRecipe(recipe, recipeDate, selectedSlot); page = "Plan" }) { Text("Add to plan") }
                    }
                }
            }
        }
        else -> {
            val date = PlanningRules.parseDate(selectedDate) ?: LocalDate.now()
            OutlinedTextField(dateDraft, { dateDraft = it.take(10) }, Modifier.fillMaxWidth().testTag("diary_date"),
                label = { Text("Diary date · YYYY-MM-DD") }, singleLine = true,
                isError = PlanningRules.parseDate(dateDraft) == null,
                supportingText = { Text("Choose any date to browse or add food.") })
            OutlinedButton(onClick = { vm.selectDiaryDate(dateDraft) }, enabled = PlanningRules.parseDate(dateDraft) != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Show this date") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runCatching { date.minusDays(1) }.getOrNull()?.let { vm.selectDiaryDate(it.toString()) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous day") }
                OutlinedButton(onClick = { vm.selectDiaryDate(LocalDate.now().toString()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Today") }
                OutlinedButton(onClick = { runCatching { date.plusDays(1) }.getOrNull()?.let { vm.selectDiaryDate(it.toString()) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next day") }
            }
            val entries = state.data.intake.filter { it.date == selectedDate }
            val known = entries.sumOf { it.kcal.knownNutrient() ?: 0.0 }
            CozyCard(color = Color(0xFFF0EAF5)) {
                Text(if (selectedDate == LocalDate.now().toString()) "Today's food diary" else "Food diary · $selectedDate", style = MaterialTheme.typography.titleLarge)
                Text(selectedDate, Modifier.testTag("diary_selected_date"))
                Text("${known.pretty()} kcal logged", Modifier.testTag("diary_total"), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (entries.any { it.kcal.knownNutrient() == null }) Text("Total incomplete: ${entries.count { it.kcal.knownNutrient() == null }} unknown entries.")
                fun sumLabel(label: String, value: (Intake) -> Double?) = "$label: ${entries.sumOf { value(it).knownNutrient() ?: 0.0 }.pretty()} g${if (entries.any { value(it).knownNutrient() == null }) " + unknown" else ""}"
                Text(listOf(sumLabel("Protein") { it.protein }, sumLabel("Carbs") { it.carbs }, sumLabel("Fat") { it.fat }).joinToString("\n"))
                CozyAction("Log what I ate", onFoodLog, Modifier.fillMaxWidth())
                OutlinedButton(onClick = { custom = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Log food, snack or drink") }
                Text("Manual entries below use $selectedDate. In chat, include a date or say ‘yesterday’; otherwise Poodles logs today.", style = MaterialTheme.typography.bodySmall)
            }
            if (entries.isEmpty()) Text("Nothing logged yet. No pressure—start whenever you like.")
            entries.reversed().forEach { entry -> key(entry.id) { CozyCard {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text("${entry.kcal.knownNutrient()?.pretty() ?: "Unknown"} kcal · ${entry.portions} portion(s)")
                Text(entry.source, style = MaterialTheme.typography.bodySmall)
                if (entry.origin != null || entry.estimate != null || entry.portion != null) {
                    var showSource by rememberSaveable { mutableStateOf(false) }
                    TextButton(onClick = { showSource = !showSource }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showSource) "Hide source details" else "Sources and portion details") }
                    if (showSource) {
                        entry.portion?.let { Text("${it.amount} ${it.description.ifBlank { it.unit }}${if (it.assumed) " · assumed portion" else ""}") }
                        entry.estimate?.let { Text(it.explanation, style = MaterialTheme.typography.bodySmall) }
                        entry.origin?.let { EvidenceSources(it) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { edit = entry }, modifier = Modifier.heightIn(min = 48.dp).testTag("diary_edit_${entry.id}")) { Text("Edit food / date / calories") }
                    TextButton(onClick = { vm.deleteIntake(entry.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
                }
            } } }
        }
    }
    if (custom) LogFoodDialog(onDismiss = { custom = false }, save = { name, calories, source, operationId, onSaved ->
        vm.logCustom(name, calories, source, operationId, onSaved, date = selectedDate)
    }, date = selectedDate)
    edit?.let { entry -> EditDiaryDialog(entry, { edit = null }) { name, date, calories, operationId, onSaved ->
        vm.editDiaryEntry(entry.id, name, date, calories, operationId, entry.entryRevision, onSaved)
    } }
    recipeDetails?.let { recipe -> ModalBottomSheet(onDismissRequest = { recipeDetails = null }) { Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding()) { RecipeCard(recipe, state.foods); Spacer(Modifier.height(24.dp)) } } }
}

@Composable internal fun RecipeCard(recipe: Recipe, foods: List<Food>) {
    if (recipe.sourced != null) { SourcedRecipeCard(recipe); return }
    val n = Nutrition.calculateOrNull(recipe, foods)
    CozyCard(color = Cream) {
        Text(recipe.title, style = MaterialTheme.typography.headlineMedium)
        Text("${recipe.servings} serving(s) · ${recipe.minutes} min active · ${recipe.method}")
        Text("${n?.kcal?.pretty() ?: "Unknown"} kcal / serving", style = MaterialTheme.typography.titleLarge)
        if (n == null) Text("This saved recipe needs review. Nutrition is unknown; the original record is preserved.")
        Text("Protein ${n?.protein?.pretty() ?: "?"} g · Carbs ${n?.carbs?.pretty() ?: "?"} g · Fat ${n?.fat?.pretty() ?: "?"} g", style = MaterialTheme.typography.bodySmall)
        recipe.ingredients.forEach { part -> val food = foods.find { it.id == part.id }; Text("${part.grams.pretty()} g · ${food?.name ?: "Unknown ingredient (${part.id})"}") }
        HorizontalDivider()
        Text("How to make it", style = MaterialTheme.typography.titleMedium)
        Text(if (n == null) "Review the saved ingredients and quantities before following this recipe." else RecipeRules.steps(recipe, foods))
        if (recipe.note.isNotBlank()) Text("AI note: ${recipe.note}", style = MaterialTheme.typography.bodySmall)
        if (n != null) Text("Nutrition: USDA SR Legacy, April 2018; calculated estimates. Actual brands, preparation and portions differ.", style = MaterialTheme.typography.bodySmall)
        recipe.ingredients.mapNotNull { foods.find { f -> f.id == it.id } }.forEach { Text("${it.name}: FDC ${it.fdcId}", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable internal fun MoveScreen(state: ScreenState, vm: PoodlesViewModel) {
    SectionTitle("Your movement corner", "Small steps. Soft landings. Your pace.")
    CozyCard(color = Sage) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("You don't have to do it all.", style = MaterialTheme.typography.titleLarge); Text("A gentle reset is enough.", Modifier.padding(top = 8.dp)) }
            Poodles(Modifier.size(115.dp), "encouraging", state.data.profile.mode == "Hostel")
        }
        Text("${state.data.profile.mode}: ${state.data.profile.environment().workoutSpace}")
        Text("Goal: ${state.data.profile.goal} · ${state.data.profile.experience}")
        CozyAction("Make my gentle routine", vm::makeWorkout, enabled = !state.busy && state.data.profile.injuries.isBlank())
        if (state.data.profile.injuries.isNotBlank()) Text("A limitation is recorded. Get suitable exercise guidance before using a generated routine.")
    }
    Text("General movement suggestions, not injury rehabilitation. Stop if movement hurts or you feel dizzy or unwell.", style = MaterialTheme.typography.bodySmall)
    state.data.workout?.let { workout ->
        CozyCard {
            Text(workout.title, style = MaterialTheme.typography.titleLarge)
            Text("${workout.rounds} round(s) · ${workout.mode}")
            val stale = workout.revision != state.data.revision
            val invalid = !WorkoutRules.validate(workout, state.data.profile) || workout.sourced != null
            if (invalid) Text("This saved routine needs review before use. Its original data is preserved.")
            if (stale) Text("Profile or mode changed. Regenerate before following this routine.", color = Rose)
            workout.exerciseIds.forEachIndexed { i, id -> exercises.find { it.id == id }?.let { exercise ->
                Text("${i + 1}. ${exercise.name} · ${exercise.seconds} sec", fontWeight = FontWeight.SemiBold)
                Text(exercise.instruction)
            } }
            Text("Rest as needed between movements. There is no calorie-burn estimate.", style = MaterialTheme.typography.bodySmall)
            if (workout.completedDate == LocalDate.now().toString()) Text("A little movement, celebrated. Done today.", color = Rose)
            else OutlinedButton(enabled = !stale && !invalid, onClick = vm::completeWorkout) { Text("I finished today's routine") }
        }
    }
}

@Composable private fun ChoiceRow(title: String, choices: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column {
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            choices.forEach { option -> FilterChip(selected == option, { onSelect(option) }, label = { Text(option) }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
    }
}

@Composable internal fun ProfileScreen(original: Profile, saving: Boolean, error: String?, dismissError: () -> Unit,
    onEdit: (Profile) -> Unit, onDismiss: () -> Unit, onSave: (Profile) -> Unit,
    memories: List<ComfortMemory> = emptyList(), onForget: (String) -> Unit = {}) {
    var profile by remember { mutableStateOf(original) }
    LaunchedEffect(profile) { onEdit(profile) }
    BackHandler(enabled = !saving, onBack = onDismiss)
    var restriction by rememberSaveable(stateSaver = Saver<Restriction?, String>(
        save = { it?.let { value -> json.encodeToString(Restriction.serializer(), value) }.orEmpty() },
        restore = { if (it.isBlank()) null else json.decodeFromString<Restriction>(it) }
    )) { mutableStateOf<Restriction?>(null) }
    var restrictionIndex by rememberSaveable { mutableIntStateOf(-1) }
    var target by rememberSaveable { mutableStateOf(original.calorieTarget?.toString().orEmpty()) }
    var forget by remember { mutableStateOf<String?>(null) }
    val validTarget = target.isBlank() || (target.toIntOrNull()?.let { it in 1..10000 } == true)
    restriction?.let { item ->
        RestrictionEditor(item, onDismiss = { restriction = null }, save = { edited ->
            profile = profile.copy(restrictions = if (restrictionIndex < 0) profile.restrictions + edited else profile.restrictions.mapIndexed { i, r -> if (i == restrictionIndex) edited else r })
            restriction = null
        })
        return
    }
    Scaffold(containerColor = Paper, modifier = Modifier.imePadding(), topBar = {
        TopAppBar(title = { Text("About you") }, navigationIcon = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Back") } })
    }, bottomBar = {
        Surface(color = Paper) { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Button(enabled = !saving && validTarget && profile.restrictions.all { it.name.isNotBlank() },
                onClick = { onSave(profile.copy(calorieTarget = target.toIntOrNull())) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (saving) "Saving…" else "Save my preferences")
            }
        } }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle("Made around you", "Take your time. Your draft stays here if you go back.")
            OutlinedTextField(profile.name, { profile = profile.copy(name = it.take(40)) }, label = { Text("What should Poodles call you? (optional)") }, modifier = Modifier.fillMaxWidth())
            ChoiceRow("Eating preference", listOf("Not specified", "Vegetarian", "Vegan", "Omnivore"), profile.diet) { profile = profile.copy(diet = it) }
            Text("Vegetarian excludes eggs here. Choose your preference accordingly.", style = MaterialTheme.typography.bodySmall)
            Text("Your food boundaries", style = MaterialTheme.typography.titleLarge)
            profile.restrictions.forEachIndexed { index, item ->
                Surface(color = Blush, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.Bold); Text(item.kind, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { restriction = item; restrictionIndex = index }) { Text("Edit") }
                        Switch(item.enabled, { enabled -> profile = profile.copy(restrictions = profile.restrictions.mapIndexed { i, r -> if (i == index) r.copy(enabled = enabled) else r }) },
                            modifier = Modifier.semantics { contentDescription = "Enable ${item.name}" })
                    }
                }
            }
            OutlinedButton(onClick = { restrictionIndex = -1; restriction = Restriction("") }) { Text("Add a restriction") }
            Text("These are your reported intolerances, not AI diagnoses. Spice aliases are conservative until you tune them. Tolerance notes do not automatically bypass ingredient matches.", style = MaterialTheme.typography.bodySmall)
            ChoiceRow("Where are you today?", listOf("Hostel", "Home"), profile.mode) { profile = profile.copy(mode = it) }
            Text("Editing your ${profile.mode} setup. Your other setup stays saved.", style = MaterialTheme.typography.bodySmall)
            val environment = profile.environment()
            fun updateEnvironment(next: Environment) { profile = if (profile.mode == "Hostel") profile.copy(hostel = next) else profile.copy(home = next) }
            Text("Available equipment", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Kettle", "Fridge", "Stove", "Microwave", "Oven", "Blender", "Mixer", "Freezer", "Food processor", "Air fryer", "Pressure cooker", "Slow cooker", "Grill").forEach { equipment ->
                    FilterChip(equipment in environment.equipment, { updateEnvironment(environment.copy(equipment = if (equipment in environment.equipment) environment.equipment - equipment else environment.equipment + equipment)) }, label = { Text(equipment) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Text("Active preparation: ${environment.maxMinutes} minutes")
            Slider(environment.maxMinutes.toFloat(), { updateEnvironment(environment.copy(maxMinutes = it.roundToInt())) }, valueRange = 5f..90f, steps = 16)
            Text("Hands-on time only. Cooking, chilling and waiting can take longer.", style = MaterialTheme.typography.bodySmall)
            SettingsSection("Optional food preferences") {
                Text("Preferences Poodles may consider, not guaranteed limits. Budget is not checked and pantry amounts are not deducted.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(profile.cuisine, { profile = profile.copy(cuisine = it.take(150)) }, label = { Text("Favorite cuisines") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(environment.mealSource, { updateEnvironment(environment.copy(mealSource = it.take(200))) }, label = { Text("Mess / self-cooking / other") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(environment.budget, { updateEnvironment(environment.copy(budget = it.take(100))) }, label = { Text("Budget, including currency and period") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(environment.pantry, { updateEnvironment(environment.copy(pantry = it.take(300))) }, label = { Text("Available foods / pantry") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(environment.rules, { updateEnvironment(environment.copy(rules = it.take(300))) }, label = { Text("Cooking preferences") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
            Text("Movement", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(environment.workoutEquipment, { updateEnvironment(environment.copy(workoutEquipment = it.take(150))) }, label = { Text("Workout equipment") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(environment.workoutSpace, { updateEnvironment(environment.copy(workoutSpace = it.take(150))) }, label = { Text("Space and noise limits") }, modifier = Modifier.fillMaxWidth())
            ChoiceRow("Experience", listOf("Beginner", "Regular", "Experienced"), profile.experience) { profile = profile.copy(experience = it) }
            OutlinedTextField(profile.injuries, { profile = profile.copy(injuries = it.take(300)) }, label = { Text("Injuries or limitations (optional)") }, modifier = Modifier.fillMaxWidth())
            Text("Recording a limitation pauses automatic workout generation; Poodles cannot assess injury suitability.", style = MaterialTheme.typography.bodySmall)
            SettingsSection("Diary settings") {
                OutlinedTextField(target, { target = it.take(5) }, label = { Text("Optional calorie target (kcal/day)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !validTarget, modifier = Modifier.fillMaxWidth())
                Text("Leave blank for tracking without a target. Poodles does not prescribe weight-loss goals.", style = MaterialTheme.typography.bodySmall)
            }
            SettingsSection("Little comforts") {
                SettingSwitch("Gentle movement", "Poodles blinks and sways. Respects Android's motion settings.", profile.gentleMotion) { profile = profile.copy(gentleMotion = it) }
                SettingSwitch("Little sounds", "Soft chimes for small moments. Silent mode stays silent.", profile.sounds) { profile = profile.copy(sounds = it) }
            }
            SettingsSection("Comfort memories") {
                SettingSwitch("Let Poodles learn what comforts me", "Remember simple preferences between visits, like shorter replies or fewer jokes. No private life events are saved as memories. You can review or forget them here.", profile.rememberComfort) { profile = profile.copy(rememberComfort = it) }
                if (memories.isEmpty()) Text("Still getting to know you. Tell Poodles what feels good, in your own words.", style = MaterialTheme.typography.bodyMedium)
                memories.forEach { memory ->
                    Surface(color = Lavender, shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(memory.description, style = MaterialTheme.typography.titleSmall)
                                Text(if (memory.explicit || memory.evidence >= 3) "Remembered preference" else "Still learning; not a fixed preference", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton({ forget = memory.key }) { Icon(Icons.Rounded.Close, "Forget ${memory.description}") }
                        }
                    }
                }
                if (memories.isNotEmpty()) TextButton({ forget = "all" }) { Text("Forget comfort memories") }
            }
            Text("Your privacy", style = MaterialTheme.typography.titleLarge)
            Text("Messages and relevant preferences are processed by Cloudflare's AI service. Published recipe, ingredient, nutrition and workout sources use a separate choice for Exa/Tavily search and optional Groq fallback. Related workout video lookup uses YouTube. Our backend does not save chats. Poodles can make mistakes.", style = MaterialTheme.typography.bodyMedium)
            SettingSwitch("Allow published-source lookup", "Send recipe, food, nutrition or workout terms to Exa/Tavily, related workout video terms to YouTube, and relevant requests and preferences to Cloudflare. Your unrelated chats are not included.", profile.sourceLookupConsent) {
                profile = profile.copy(sourceLookupConsent = it, externalModelConsent = it && profile.externalModelConsent)
            }
            if (profile.sourceLookupConsent) SettingSwitch("Allow Groq fallback", "If Cloudflare is unavailable, send the relevant research request and preferences to Groq. Uses only an eligible free provider.", profile.externalModelConsent) {
                profile = profile.copy(externalModelConsent = it)
            }
            SettingSwitch("Remember our chats", "Save conversation history on this phone between visits. Turning this off removes stored conversation histories. Chat's clear action clears companion history only; saved results and diary evidence are separate.", profile.rememberChats) { profile = profile.copy(rememberChats = it) }
            Spacer(Modifier.height(18.dp))
        }
    }
    forget?.let { key -> AlertDialog(onDismissRequest = { forget = null }, title = { Text("Forget this comfort memory?") },
        text = { Text("The memory and current conversation will be cleared so old messages cannot bring it back. Your food profile and plans are separate.") },
        confirmButton = { TextButton({ onForget(key); forget = null }) { Text("Forget") } },
        dismissButton = { TextButton({ forget = null }) { Text("Keep it") } }) }
    error?.let { AlertDialog(onDismissRequest = dismissError, title = { Text("Your draft is safe") }, text = { Text(it) }, confirmButton = { TextButton(onClick = dismissError) { Text("Keep editing") } }) }
}

@Composable internal fun WelcomeScreen(saving: Boolean, error: String?, dismissError: () -> Unit, customize: () -> Unit, continueToHome: () -> Unit) {
    Scaffold(containerColor = Paper) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(12.dp))
            CozyScene(Modifier.fillMaxWidth().height(220.dp), "happy")
            Text("Hello, lovely.\nI'm Mr. Poodles.", style = MaterialTheme.typography.headlineLarge)
            Text("A softer place for your meals, little plans, and whatever is on your mind.")
            CozyCard(color = Blush) {
                Text("A little head start", style = MaterialTheme.typography.titleMedium)
                Text("Lactose, soy and spice intolerances. Hostel meals and self-cooking, with a kettle and fridge. You can tune every detail later.")
            }
            CozyCard(color = Cream) {
                Text("A little note about privacy", style = MaterialTheme.typography.titleMedium)
                Text("Continuing lets Poodles process the messages and relevant preferences you choose to send through our AI service. Saving chat history and comfort memories is optional in About you.", style = MaterialTheme.typography.bodyMedium)
            }
            CozyAction(if (saving) "Getting ready…" else "Come on in", continueToHome, Modifier.fillMaxWidth(), !saving)
            OutlinedButton(enabled = !saving, onClick = customize, modifier = Modifier.fillMaxWidth()) { Text("Make it more me") }
            TextButton(enabled = !saving, onClick = continueToHome) { Text("Set up later") }
            Text("Poodles is an AI companion, not a medical professional. He can make mistakes.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = dismissError) { Text("Try again") } }
        }
    }
}

@Composable private fun SettingSwitch(title: String, description: String, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodySmall) }
        Switch(enabled, change, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedButton({ expanded = !expanded }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(if (expanded) "Hide $title" else title)
    }
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable private fun RestrictionEditor(original: Restriction, onDismiss: () -> Unit, save: (Restriction) -> Unit) {
    var encoded by rememberSaveable { mutableStateOf(json.encodeToString(Restriction.serializer(), original)) }
    var item = json.decodeFromString<Restriction>(encoded)
    fun edit(next: Restriction) { encoded = json.encodeToString(Restriction.serializer(), next) }
    BackHandler(onBack = onDismiss)
    Scaffold(modifier = Modifier.imePadding(), containerColor = Paper,
        topBar = { TopAppBar(title = { Text("A food boundary") }, navigationIcon = { TextButton(onClick = onDismiss) { Text("Back") } }) },
        bottomBar = { Button(enabled = item.name.isNotBlank(), onClick = { save(item) }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("Apply to profile") } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(item.name, { edit(item.copy(name = it.take(50))) }, label = { Text("Trigger name") }, modifier = Modifier.fillMaxWidth())
            ChoiceRow("Type", listOf("Intolerance", "Allergy", "Suspected trigger"), item.kind) { edit(item.copy(kind = it)) }
            SettingsSection("Advanced restriction details") {
                OutlinedTextField(item.aliases, { edit(item.copy(aliases = it.take(1200))) }, label = { Text("Ingredient aliases, comma-separated") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(item.notes, { edit(item.copy(notes = it.take(400))) }, label = { Text("Tolerance notes / specific spices") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
            Text("Use exact ingredient names. Unknown aliases may be missed. Only change medical restrictions based on your own reliable information.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun LogFoodDialog(onDismiss: () -> Unit, save: (String, Double?, String, String, () -> Unit) -> Unit,
    date: String = LocalDate.now().toString()) {
    var name by rememberSaveable { mutableStateOf("") }
    var calories by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("User estimate") }
    val operationId = rememberSaveable(name, calories, source, date) { java.util.UUID.randomUUID().toString() }
    val latestOperation by rememberUpdatedState(operationId)
    val valid = calories.isBlank() || (calories.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..10000.0 } == true)
    PlanningDialog("What did you have?", onDismiss) {
        Text("Diary date: $date")
        OutlinedTextField(name, { name = it.take(120) }, label = { Text("Food and portion") })
        OutlinedTextField(calories, { calories = it.take(8) }, label = { Text("Total calories, if known") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !valid)
        ChoiceRow("Source", listOf("User estimate", "Package label", "Mess estimate"), source) { source = it }
        Text("Leave calories blank when unknown. Enter calories for the portion actually eaten, not per 100 g.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = name.isNotBlank() && valid, onClick = {
            save(name, calories.toDoubleOrNull(), source, operationId) { if (latestOperation == operationId) onDismiss() }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Log food") }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
    }
}

@Composable private fun EditDiaryDialog(entry: Intake, dismiss: () -> Unit,
    save: (String, String, Double?, String, () -> Unit) -> Unit) {
    var name by rememberSaveable(entry.id) { mutableStateOf(entry.name) }
    var date by rememberSaveable(entry.id) { mutableStateOf(entry.date) }
    var calories by rememberSaveable(entry.id) { mutableStateOf(entry.kcal?.toString().orEmpty()) }
    val operationId = rememberSaveable(entry.id, name, date, calories) { java.util.UUID.randomUUID().toString() }
    val latestOperation by rememberUpdatedState(operationId)
    val validCalories = calories.isBlank() || calories.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..10000.0 } == true
    val validDate = PlanningRules.parseDate(date) != null
    PlanningDialog("Edit diary entry", dismiss) {
        OutlinedTextField(name, { name = it.take(120) }, Modifier.fillMaxWidth().testTag("diary_edit_name"), label = { Text("Food and portion") })
        OutlinedTextField(date, { date = it.take(10) }, Modifier.fillMaxWidth().testTag("diary_edit_date"),
            label = { Text("Diary date · YYYY-MM-DD") }, singleLine = true, isError = !validDate)
        OutlinedTextField(calories, { calories = it.take(16) }, Modifier.fillMaxWidth().testTag("diary_edit_calories"),
            label = { Text("Calories (blank = unknown)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !validCalories)
        Text("Use the total for the portion eaten. Manual corrections are your estimate; unsupported nutrition stays unknown.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = name.isNotBlank() && validDate && validCalories, onClick = {
            save(name, date, calories.toDoubleOrNull(), operationId) { if (latestOperation == operationId) dismiss() }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save") }
        TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
    }
}

@Composable internal fun AboutDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    val notices = remember { runCatching { context.assets.open("NOTICES.txt").bufferedReader().use { it.readText() } }.getOrDefault("License details are available with this app's source.") }
    var license by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("A private little companion") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Poodles is an AI companion for everyday support. He can make mistakes and cannot provide a diagnosis or certify food safety.")
            Text("Saved preferences, plans and your diary stay on this phone. Messages and relevant context go to Cloudflare. With source lookup enabled, recipe, ingredient, nutrition and workout terms go to Exa/Tavily, and related workout video terms go to YouTube. Groq receives relevant requests only with your optional fallback permission. Our backend stores allowance counters, not chats.")
            Text("Saving chats and comfort memories is optional. You can review and delete them. Android backup is disabled.")
            Text("Food checks use typed ingredients or retrieved public sources. Incomplete labels and uncertain ingredients need clarification. Recipes, rough nutrition estimates and workouts show retrieved sources where available. Missing nutrition stays unknown; adaptations and estimates are not the original published guidance.")
            Text("Removing the app deletes its private data. No cloud restore is available.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { license = !license }) { Text(if (license) "Hide licenses" else "Licenses and model terms") }
            if (license) Text(notices, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Lovely") } })
}
