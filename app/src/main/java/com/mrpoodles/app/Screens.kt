@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mrpoodles.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private fun Double.pretty() = String.format(Locale.US, "%.0f", this)

@Composable internal fun TodayScreen(state: ScreenState, navigate: (Int) -> Unit) {
    val p = state.data.profile
    val today = LocalDate.now().toString()
    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.labelLarge, color = Rose)
    Text("A little care,\njust for you.", style = MaterialTheme.typography.headlineLarge)
    Text(if (p.name.isBlank()) "Come in. There's no rush here." else "Come in, ${p.name}. There's no rush here.", color = Plum)
    CozyScene(Modifier.fillMaxWidth().height(190.dp), "happy", p.mode == "Hostel")
    CozyAction("Spend a moment with Poodles", { navigate(4) }, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallAction("Can I eat this?", "Check a label", Icons.Rounded.DocumentScanner, Blush, Modifier.weight(1f)) { navigate(1) }
        SmallAction("Something yummy", "Make a recipe", Icons.Rounded.Restaurant, Cream, Modifier.weight(1f)) { navigate(2) }
    }
    val logs = state.data.intake.filter { it.date == today }
    val total = logs.sumOf { it.kcal ?: 0.0 }
    CozyCard(color = Color(0xFFF0EAF5)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FavoriteBorder, null, tint = Rose)
            Text("Today's nourishment", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(total.pretty(), fontSize = 35.sp, fontWeight = FontWeight.Bold)
            Text(" kcal logged", Modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        p.calorieTarget?.let { target ->
            LinearProgressIndicator(progress = { (total / target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Rose)
            Text("Your optional target: $target kcal. Food is not a score.", style = MaterialTheme.typography.bodySmall)
        }
        if (logs.any { it.kcal == null }) Text("Some entries have unknown calories; the total is incomplete.")
        Text("${logs.size} entries · estimates based on portions", style = MaterialTheme.typography.bodySmall)
    }
    SectionTitle("Your little plans", "A softer rhythm for the day")
    val meals = state.data.meals.filter { it.date == today }
    CozyCard {
        if (meals.isEmpty()) {
            Text("A fresh page for your meals", style = MaterialTheme.typography.titleMedium)
            Text("Let's plan something that fits your ${p.mode.lowercase()} day.")
        } else meals.forEach { Text("${it.slot} · ${it.recipe.title}") }
        TextButton(onClick = { navigate(2) }) { Text("Open meal planner") }
    }
    CozyCard(color = Sage) {
        Text("A small moment to move", style = MaterialTheme.typography.titleMedium)
        Text(state.data.workout?.title ?: "Gentle movement, at your own pace.")
        TextButton(onClick = { navigate(3) }) { Text("Visit your movement corner") }
    }
    PoodlesNote()
}

@Composable private fun SmallAction(title: String, subtitle: String, icon: ImageVector, color: Color, modifier: Modifier, click: () -> Unit) {
    Surface(onClick = click, modifier = modifier, color = color, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = Rose)
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable internal fun CheckScreen(state: ScreenState, vm: PoodlesViewModel) {
    val context = LocalContext.current
    var language by rememberSaveable { mutableStateOf("English") }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var rotation by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            photo = it.toString(); rotation = false; vm.readPhoto(it, language, false)
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) photo?.let { vm.readPhoto(Uri.parse(it), language, false) }
    }
    SectionTitle("A little label check", "Look closer, with Mr. Poodles by your side.")
    CozyCard(color = Cream) {
        Text("A check, not a safety guarantee", fontWeight = FontWeight.SemiBold)
        Text("Hidden ingredients, translation errors and cross-contact may be missed. This app cannot diagnose allergies or certify food safety.", style = MaterialTheme.typography.bodyMedium)
    }
    ChoiceRow("Label language", LabelPhoto.languages, language) { language = it }
    Text("Choose a label photo to send for reading. Location metadata is removed.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(enabled = !state.busy, onClick = {
            try {
                val file = File(context.cacheDir, "photos/label.jpg").apply { parentFile?.mkdirs() }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                photo = uri.toString(); rotation = false; camera.launch(uri)
            } catch (e: Exception) { vm.error("Could not open the camera: ${e.message}") }
        }) { Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Camera") }
        OutlinedButton(enabled = !state.busy, onClick = { picker.launch(arrayOf("image/*")) }) { Text("Choose photo") }
    }
    photo?.let { selectedPhoto ->
        PhotoPreview(selectedPhoto, rotation)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !state.busy, onClick = { rotation = !rotation; vm.readPhoto(Uri.parse(selectedPhoto), language, rotation) }) { Text("Rotate & read") }
            TextButton(enabled = !state.busy, onClick = { vm.readPhoto(Uri.parse(selectedPhoto), language, rotation) }) { Text("Read again") }
        }
    }
    OutlinedTextField(value = state.label, onValueChange = vm::setLabel, label = { Text("Ingredients and allergen statements") },
        placeholder = { Text("Paste a label, or read one from a photo…") }, modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 10, enabled = !state.busy)
    Text("Compare every word with the package before checking. Confirm unclear text yourself.", style = MaterialTheme.typography.bodySmall)
    if (language != "English") {
        OutlinedButton(enabled = !state.busy && state.label.isNotBlank(), onClick = { vm.translate(language) }) { Text("Translate label into English") }
        if (state.translation.isNotBlank()) CozyCard {
            Text("AI translation · verify before using", fontWeight = FontWeight.Bold)
            Text(state.translation)
            Text("Original text remains above. A small model may mistranslate ingredient names.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = vm::useTranslation) { Text("Use translation for review") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(state.labelComplete, onCheckedChange = vm::setComplete)
        Text("I checked the complete ingredients and advisory statements.", style = MaterialTheme.typography.bodyMedium)
    }
    CozyAction("Check my triggers", vm::checkLabel, Modifier.fillMaxWidth(), state.label.isNotBlank() && !state.busy)
    state.check?.let { result ->
        CozyCard(color = if (result.findings.isEmpty()) Cream else Blush) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Poodles(Modifier.size(65.dp), if (result.findings.isEmpty()) "curious" else "concerned")
                Text(result.verdict, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            result.findings.forEach { Text("${it.trigger}: matched “${it.evidence}”\n${it.note}", style = MaterialTheme.typography.bodyMedium) }
            result.uncertainties.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            if (result.findings.isEmpty()) Text("No match does not mean the food is safe. Confirm unclear ingredients with the manufacturer or kitchen.")
            OutlinedButton(enabled = !state.busy, onClick = vm::explainLabel) { Text("Ask Poodles to explain") }
            if (state.explanation.isNotBlank()) { HorizontalDivider(); Text("AI explanation · may be inaccurate", style = MaterialTheme.typography.labelMedium); Text(state.explanation) }
        }
    }
    TextButton(onClick = { history = !history }) { Text(if (history) "Hide recent checks" else "Recent checks (${state.data.checks.size})") }
    if (history) state.data.checks.take(8).forEach { record -> CozyCard { Text("${record.date} · ${record.verdict}", fontWeight = FontWeight.Bold); Text(record.text.take(300)); TextButton(onClick = { vm.setLabel(record.text) }) { Text("Recheck with current profile") } } }
}

@Composable internal fun MealsScreen(state: ScreenState, vm: PoodlesViewModel) {
    var page by rememberSaveable { mutableStateOf("Plan") }
    var request by rememberSaveable { mutableStateOf("") }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var selectedSlot by rememberSaveable { mutableStateOf("Lunch") }
    var custom by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Intake?>(null) }
    var portionMeal by remember { mutableStateOf<Meal?>(null) }
    var showWeekConfirmation by remember { mutableStateOf(false) }
    var recipeDetails by remember { mutableStateOf<Recipe?>(null) }
    SectionTitle("Something nourishing", "Recipes, little plans, and room for what you love.")
    ChoiceRow("", listOf("Plan", "Recipes", "Food diary"), page) { page = it }
    when (page) {
        "Plan" -> {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(7) { offset ->
                    val day = LocalDate.now().plusDays(offset.toLong())
                    FilterChip(selected = selectedDate == day.toString(), onClick = { selectedDate = day.toString() }, label = { Text(day.format(DateTimeFormatter.ofPattern("EEE d"))) })
                }
            }
            val meals = state.data.meals.filter { it.date == selectedDate }
            val total = meals.sumOf { runCatching { Nutrition.calculate(it.recipe, state.foods).kcal }.getOrDefault(0.0) }
            CozyCard(color = Cream) {
                Text("${total.pretty()} kcal planned", style = MaterialTheme.typography.titleLarge)
                Text("Planning is separate from eating. These suggestions are not a complete dietary prescription.", style = MaterialTheme.typography.bodySmall)
                CozyAction("Let Poodles plan my meals", { showWeekConfirmation = true }, enabled = !state.busy)
            }
            if (meals.isEmpty()) CozyCard { Text("An empty plate, lots of possibilities."); Text("Save a recipe, then add it here. Or let Poodles choose from recipes matching your profile.") }
            meals.sortedBy { listOf("Breakfast", "Lunch", "Dinner", "Snack").indexOf(it.slot) }.forEach { meal ->
                CozyCard {
                    Text("${meal.slot.uppercase()} · ${meal.mode}", color = Rose, style = MaterialTheme.typography.labelMedium)
                    Text(meal.recipe.title, style = MaterialTheme.typography.titleLarge)
                    Text("${Nutrition.calculate(meal.recipe, state.foods).kcal.pretty()} kcal / serving · estimated")
                    val problems = RecipeRules.validate(meal.recipe, state.data.profile, state.foods)
                    if (meal.revision != state.data.revision) Text("Profile changed since planning. Review this meal again.", color = Rose)
                    problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.data.intake.any { it.mealId == meal.id }) Text("Logged · log again to update the same entry", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { recipeDetails = meal.recipe }) { Text("Recipe") }
                        TextButton(onClick = { portionMeal = meal }) { Text("Log eaten") }
                        IconButton(onClick = { vm.removeMeal(meal.id) }) { Icon(Icons.Rounded.DeleteOutline, "Remove planned meal") }
                    }
                }
            }
            val groceries = meals.flatMap { it.recipe.ingredients }.groupBy { it.id }
            if (groceries.isNotEmpty()) CozyCard(color = Sage) {
                Text("Little shopping list", style = MaterialTheme.typography.titleLarge)
                Text("Whole-recipe quantities for $selectedDate. Already-cooked items must be obtained prepared.", style = MaterialTheme.typography.bodySmall)
                groceries.forEach { (id, portions) -> Text("${state.foods.find { it.id == id }?.name ?: id} · ${portions.sumOf { it.grams }.pretty()} g") }
                Text("Check package labels and pantry stock before buying.", style = MaterialTheme.typography.bodySmall)
            }
        }
        "Recipes" -> {
            CozyCard(color = Blush) {
                Text("What sounds good?", style = MaterialTheme.typography.titleLarge)
                Text("Try “a hostel breakfast” or “adapt creamy pasta without lactose or soy”. Poodles works within the bundled ingredient catalog.")
                OutlinedTextField(request, { request = it.take(800) }, modifier = Modifier.fillMaxWidth(), label = { Text("Your recipe wish") }, minLines = 2)
                CozyAction("Create with Poodles", { vm.makeRecipe(request) }, enabled = !state.busy && request.isNotBlank())
            }
            state.draft?.let { recipe ->
                RecipeCard(recipe, state.foods)
                Text("AI draft passed ingredient rules. Review actual packages, cooking conditions and portions before saving.", style = MaterialTheme.typography.bodySmall)
                CozyAction("Save this recipe", vm::saveDraft)
            }
            SectionTitle("Your recipe shelf")
            Text("Your saved AI creations. Choose a date and meal slot to add one to your plan.", style = MaterialTheme.typography.bodySmall)
            ChoiceRow("Meal", listOf("Breakfast", "Lunch", "Dinner", "Snack"), selectedSlot) { selectedSlot = it }
            Row(Modifier.horizontalScroll(rememberScrollState())) { repeat(7) { offset ->
                val day = LocalDate.now().plusDays(offset.toLong())
                FilterChip(selectedDate == day.toString(), { selectedDate = day.toString() }, label = { Text(day.format(DateTimeFormatter.ofPattern("EEE d"))) }, modifier = Modifier.padding(end = 6.dp))
            } }
            state.data.recipes.forEach { recipe ->
                val issues = RecipeRules.validate(recipe, state.data.profile, state.foods)
                CozyCard {
                    Text(if (recipe.aiGenerated) "CREATED FOR YOU" else "SAVED RECIPE", color = Rose, style = MaterialTheme.typography.labelSmall)
                    Text(recipe.title, style = MaterialTheme.typography.titleLarge)
                    Text("${Nutrition.calculate(recipe, state.foods).kcal.pretty()} kcal / serving · ${recipe.minutes} min active")
                    if (issues.isNotEmpty()) Text(issues.joinToString(" "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { recipeDetails = recipe }) { Text("View") }
                        TextButton(enabled = issues.isEmpty(), onClick = { vm.planRecipe(recipe, selectedDate, selectedSlot); page = "Plan" }) { Text("Add to plan") }
                    }
                }
            }
        }
        else -> {
            val entries = state.data.intake.filter { it.date == LocalDate.now().toString() }
            val known = entries.sumOf { it.kcal ?: 0.0 }
            CozyCard(color = Color(0xFFF0EAF5)) {
                Text("Today's food diary", style = MaterialTheme.typography.titleLarge)
                Text("${known.pretty()} kcal logged", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (entries.any { it.kcal == null }) Text("Total incomplete: ${entries.count { it.kcal == null }} unknown entries.")
                fun sumLabel(label: String, value: (Intake) -> Double?) = "$label: ${entries.sumOf { value(it) ?: 0.0 }.pretty()} g${if (entries.any { value(it) == null }) " + unknown" else ""}"
                Text(listOf(sumLabel("Protein") { it.protein }, sumLabel("Carbs") { it.carbs }, sumLabel("Fat") { it.fat }).joinToString("\n"))
                CozyAction("Log food, snack or drink", { custom = true })
            }
            if (entries.isEmpty()) Text("Nothing logged yet. No pressure—start whenever you like.")
            entries.reversed().forEach { entry -> CozyCard {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text("${entry.kcal?.pretty() ?: "Unknown"} kcal · ${entry.portions} portion(s)")
                Text(entry.source, style = MaterialTheme.typography.bodySmall)
                Row { TextButton(onClick = { edit = entry }) { Text("Correct calories") }; TextButton(onClick = { vm.deleteIntake(entry.id) }) { Text("Remove") } }
            } }
            val past = state.data.intake.filter { it.date != LocalDate.now().toString() }.groupBy { it.date }
            if (past.isNotEmpty()) { SectionTitle("Earlier days"); past.toSortedMap(reverseOrder()).forEach { (date, logs) -> CozyCard { Text(date); Text("${logs.sumOf { it.kcal ?: 0.0 }.pretty()} known kcal · ${logs.size} entries") } } }
        }
    }
    if (custom) LogFoodDialog(onDismiss = { custom = false }, save = { name, calories, source -> vm.logCustom(name, calories, source); custom = false })
    edit?.let { entry -> NumericDialog("Correct ${entry.name}", "Calories (blank = unknown)", entry.kcal?.pretty().orEmpty(), { edit = null }) { value -> vm.editIntake(entry.id, value); edit = null } }
    portionMeal?.let { meal -> NumericDialog("Log ${meal.recipe.title}", "Servings eaten (e.g. 0.5)", "1", { portionMeal = null }) { value -> if (value != null && value > 0 && value <= 20) { vm.logMeal(meal, value); portionMeal = null } } }
    recipeDetails?.let { recipe -> ModalBottomSheet(onDismissRequest = { recipeDetails = null }) { Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding()) { RecipeCard(recipe, state.foods); Spacer(Modifier.height(24.dp)) } } }
    if (showWeekConfirmation) AlertDialog(onDismissRequest = { showWeekConfirmation = false }, title = { Text("Plan with Poodles") },
        text = { Text("This replaces planned meals for the selected day or next seven days. Logged food stays unchanged. Review generated plans before using them.") },
        confirmButton = { TextButton(onClick = { vm.makePlan(selectedDate, 1); showWeekConfirmation = false }) { Text("Selected day") } },
        dismissButton = { TextButton(onClick = { vm.makePlan(selectedDate, 7); showWeekConfirmation = false }) { Text("Seven days") } })
}

@Composable private fun RecipeCard(recipe: Recipe, foods: List<Food>) {
    val n = Nutrition.calculate(recipe, foods)
    CozyCard(color = Cream) {
        Text(recipe.title, style = MaterialTheme.typography.headlineMedium)
        Text("${recipe.servings} serving(s) · ${recipe.minutes} min active · ${recipe.method}")
        Text("${n.kcal.pretty()} kcal / serving", style = MaterialTheme.typography.titleLarge)
        Text("Protein ${n.protein?.pretty() ?: "?"} g · Carbs ${n.carbs?.pretty() ?: "?"} g · Fat ${n.fat?.pretty() ?: "?"} g", style = MaterialTheme.typography.bodySmall)
        recipe.ingredients.forEach { part -> val food = foods.first { it.id == part.id }; Text("${part.grams.pretty()} g · ${food.name}") }
        HorizontalDivider()
        Text("How to make it", style = MaterialTheme.typography.titleMedium)
        Text(RecipeRules.steps(recipe, foods))
        if (recipe.note.isNotBlank()) Text("AI note: ${recipe.note}", style = MaterialTheme.typography.bodySmall)
        Text("Nutrition: USDA SR Legacy, April 2018; calculated estimates. Actual brands, preparation and portions differ.", style = MaterialTheme.typography.bodySmall)
        recipe.ingredients.map { foods.first { f -> f.id == it.id } }.forEach { Text("${it.name}: FDC ${it.fdcId}", style = MaterialTheme.typography.labelSmall) }
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
            if (stale) Text("Profile or mode changed. Regenerate before following this routine.", color = Rose)
            workout.exerciseIds.forEachIndexed { i, id -> exercises.find { it.id == id }?.let { exercise ->
                Text("${i + 1}. ${exercise.name} · ${exercise.seconds} sec", fontWeight = FontWeight.SemiBold)
                Text(exercise.instruction)
            } }
            Text("Rest as needed between movements. There is no calorie-burn estimate.", style = MaterialTheme.typography.bodySmall)
            if (workout.completedDate == LocalDate.now().toString()) Text("A little movement, celebrated. Done today.", color = Rose)
            else OutlinedButton(enabled = !stale, onClick = vm::completeWorkout) { Text("I finished today's routine") }
        }
    }
}

@Composable private fun ChoiceRow(title: String, choices: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column {
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            choices.forEach { option -> FilterChip(selected == option, { onSelect(option) }, label = { Text(option) }) }
        }
    }
}

@Composable internal fun ProfileScreen(original: Profile, saving: Boolean, error: String?, dismissError: () -> Unit,
    onEdit: (Profile) -> Unit, onDismiss: () -> Unit, onSave: (Profile) -> Unit,
    memories: List<ComfortMemory> = emptyList(), onForget: (String) -> Unit = {}) {
    var profile by remember { mutableStateOf(original) }
    LaunchedEffect(profile) { onEdit(profile) }
    BackHandler(enabled = !saving, onBack = onDismiss)
    var editEnvironment by rememberSaveable { mutableStateOf(original.mode) }
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
            CozyScene(Modifier.fillMaxWidth().height(130.dp), "comfort", compact = true)
            OutlinedTextField(profile.name, { profile = profile.copy(name = it.take(40)) }, label = { Text("What should Poodles call you? (optional)") }, modifier = Modifier.fillMaxWidth())
            ChoiceRow("Eating preference", listOf("Not specified", "Vegetarian", "Vegan", "Omnivore"), profile.diet) { profile = profile.copy(diet = it) }
            Text("Vegetarian excludes eggs here. Choose your preference accordingly.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(profile.cuisine, { profile = profile.copy(cuisine = it.take(150)) }, label = { Text("Favorite cuisines") }, modifier = Modifier.fillMaxWidth())
            Text("Your food boundaries", style = MaterialTheme.typography.titleLarge)
            profile.restrictions.forEachIndexed { index, item ->
                Surface(color = Blush, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.Bold); Text(item.kind, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { restriction = item; restrictionIndex = index }) { Text("Edit") }
                        Switch(item.enabled, { enabled -> profile = profile.copy(restrictions = profile.restrictions.mapIndexed { i, r -> if (i == index) r.copy(enabled = enabled) else r }) })
                    }
                }
            }
            OutlinedButton(onClick = { restrictionIndex = -1; restriction = Restriction("") }) { Text("Add a restriction") }
            Text("These are your reported intolerances, not AI diagnoses. Spice aliases are conservative until you tune them. Tolerance notes do not automatically bypass ingredient matches.", style = MaterialTheme.typography.bodySmall)
            ChoiceRow("Where are you today?", listOf("Hostel", "Home"), profile.mode) { profile = profile.copy(mode = it); editEnvironment = it }
            ChoiceRow("Edit environment", listOf("Hostel", "Home"), editEnvironment) { editEnvironment = it }
            val environment = if (editEnvironment == "Hostel") profile.hostel else profile.home
            fun updateEnvironment(next: Environment) { profile = if (editEnvironment == "Hostel") profile.copy(hostel = next) else profile.copy(home = next) }
            Text("Available equipment", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Kettle", "Fridge", "Stove", "Microwave", "Oven").forEach { equipment ->
                    FilterChip(equipment in environment.equipment, { updateEnvironment(environment.copy(equipment = if (equipment in environment.equipment) environment.equipment - equipment else environment.equipment + equipment)) }, label = { Text(equipment) })
                }
            }
            OutlinedTextField(environment.mealSource, { updateEnvironment(environment.copy(mealSource = it.take(200))) }, label = { Text("Mess / self-cooking / other") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(environment.budget, { updateEnvironment(environment.copy(budget = it.take(100))) }, label = { Text("Budget, including currency and period") }, modifier = Modifier.fillMaxWidth())
            Text("Active preparation: ${environment.maxMinutes} minutes")
            Slider(environment.maxMinutes.toFloat(), { updateEnvironment(environment.copy(maxMinutes = it.roundToInt())) }, valueRange = 5f..90f, steps = 16)
            OutlinedTextField(environment.pantry, { updateEnvironment(environment.copy(pantry = it.take(300))) }, label = { Text("Available foods / pantry") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(environment.rules, { updateEnvironment(environment.copy(rules = it.take(300))) }, label = { Text("Cooking rules and practical limits") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(environment.workoutEquipment, { updateEnvironment(environment.copy(workoutEquipment = it.take(150))) }, label = { Text("Workout equipment") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(environment.workoutSpace, { updateEnvironment(environment.copy(workoutSpace = it.take(150))) }, label = { Text("Space and noise limits") }, modifier = Modifier.fillMaxWidth())
            Text("Movement & nourishment", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(profile.goal, { profile = profile.copy(goal = it.take(150)) }, label = { Text("Your movement goal") }, modifier = Modifier.fillMaxWidth())
            ChoiceRow("Experience", listOf("Beginner", "Regular", "Experienced"), profile.experience) { profile = profile.copy(experience = it) }
            OutlinedTextField(profile.injuries, { profile = profile.copy(injuries = it.take(300)) }, label = { Text("Injuries or limitations (optional)") }, modifier = Modifier.fillMaxWidth())
            Text("Recording a limitation pauses automatic workout generation; Poodles cannot assess injury suitability.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(target, { target = it.take(5) }, label = { Text("Optional calorie target (kcal/day)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !validTarget, modifier = Modifier.fillMaxWidth())
            Text("Leave blank for tracking without a target. Poodles does not prescribe weight-loss goals.", style = MaterialTheme.typography.bodySmall)
            Text("Little comforts", style = MaterialTheme.typography.titleLarge)
            SettingSwitch("Gentle movement", "Poodles blinks and sways. Respects Android's motion settings.", profile.gentleMotion) { profile = profile.copy(gentleMotion = it) }
            SettingSwitch("Little sounds", "Soft chimes for small moments. Silent mode stays silent.", profile.sounds) { profile = profile.copy(sounds = it) }
            Text("Comfort memories", style = MaterialTheme.typography.titleLarge)
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
            Text("Your privacy", style = MaterialTheme.typography.titleLarge)
            Text("Messages, selected label photos and relevant preferences are processed by Cloudflare's AI service. Photo location metadata is removed. The service does not use your content for training without consent, and our backend does not save chats or photos. Poodles can make mistakes.", style = MaterialTheme.typography.bodyMedium)
            SettingSwitch("Remember our chats", "Save conversation history on this phone between visits. You can clear it from Chat.", profile.rememberChats) { profile = profile.copy(rememberChats = it) }
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
                Text("Continuing lets Poodles process the messages, label photos and relevant preferences you choose to send through our AI service. Saving chat history and comfort memories is optional in About you.", style = MaterialTheme.typography.bodyMedium)
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
        Switch(enabled, change)
    }
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
            OutlinedTextField(item.aliases, { edit(item.copy(aliases = it.take(1200))) }, label = { Text("Ingredient aliases, comma-separated") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(item.notes, { edit(item.copy(notes = it.take(400))) }, label = { Text("Tolerance notes / specific spices") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Text("Use exact ingredient names. Unknown aliases may be missed. Only change medical restrictions based on your own reliable information.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun LogFoodDialog(onDismiss: () -> Unit, save: (String, Double?, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("User estimate") }
    val valid = calories.isBlank() || (calories.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..10000.0 } == true)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("What did you have?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Food and portion") })
            OutlinedTextField(calories, { calories = it.take(8) }, label = { Text("Total calories, if known") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !valid)
            ChoiceRow("Source", listOf("User estimate", "Package label", "Mess estimate"), source) { source = it }
            Text("Leave calories blank when unknown. Enter calories for the portion actually eaten, not per 100 g.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank() && valid, onClick = { save(name, calories.toDoubleOrNull(), source) }) { Text("Log food") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun NumericDialog(title: String, label: String, initial: String, dismiss: () -> Unit, save: (Double?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val valid = text.isBlank() || text.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..10000.0 } == true
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(text, { text = it.take(8) }, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !valid) },
        confirmButton = { TextButton(enabled = valid, onClick = { save(text.toDoubleOrNull()) }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable internal fun AboutDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    val notices = remember { runCatching { context.assets.open("NOTICES.txt").bufferedReader().use { it.readText() } }.getOrDefault("License details are available with this app's source.") }
    var license by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("A private little companion") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Poodles is an AI companion for everyday support. He can make mistakes and cannot provide a diagnosis or certify food safety.")
            Text("Saved preferences and plans stay on this phone. Messages, selected photos and relevant context are processed by Cloudflare's AI service. Our backend stores allowance counters, not chats or photos. Cloudflare does not use your content for training without consent.")
            Text("Saving chats and comfort memories is optional. You can review and delete them. Android backup is disabled.")
            Text("Label reading supports English, French, German, Spanish and Italian. Always review extracted text. Nutrition values are estimates from a small food reference.")
            Text("Removing the app deletes its private data. No cloud restore is available.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { license = !license }) { Text(if (license) "Hide licenses" else "Licenses and model terms") }
            if (license) Text(notices, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Lovely") } })
}
