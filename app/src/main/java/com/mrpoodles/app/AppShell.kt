@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.mrpoodles.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal enum class Destination(val title: String, val icon: ImageVector) {
    Today("Home", Icons.Rounded.Cottage), Check("Food check", Icons.Rounded.DocumentScanner),
    Meals("Meals", Icons.Rounded.Restaurant), Move("Workout", Icons.Rounded.Spa), Chat("Chat", Icons.Rounded.Forum),
    Recipe("Find a recipe", Icons.Rounded.Restaurant), FoodLog("Log what I ate", Icons.Rounded.EditNote)
}

@Composable fun PoodlesApp(vm: PoodlesViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val launchFinished = launchDelayFinished(vm.launchGate)
    PoodlesTheme(state.data.profile.gentleMotion) {
        val savedTabs = rememberSaveableStateHolder()
        var selected by rememberSaveable { mutableStateOf(Destination.Today.name) }
        val destination = Destination.entries.firstOrNull { it.name == selected } ?: Destination.Today
        var profileOpen by rememberSaveable { mutableStateOf(false) }
        var infoOpen by rememberSaveable { mutableStateOf(false) }
        val mealPage = rememberSaveable { mutableStateOf("Plan") }
        var recipeParent by rememberSaveable { mutableStateOf(Destination.Today.name) }
        var foodLogParent by rememberSaveable { mutableStateOf(Destination.Today.name) }
        fun openRecipe() { recipeParent = selected; selected = Destination.Recipe.name }
        fun openFoodLog() { foodLogParent = selected; selected = Destination.FoodLog.name }
        fun back() { selected = when (destination) {
            Destination.Recipe -> recipeParent
            Destination.FoodLog -> foodLogParent
            else -> Destination.Today.name
        } }
        val detail = destination == Destination.Recipe || destination == Destination.FoodLog
        val sounds = rememberPoodlesSounds(state.data.profile.sounds)
        var lastSound by remember { mutableIntStateOf(state.successSequence) }
        LaunchedEffect(state.successSequence) {
            if (state.successSequence > lastSound) sounds.play()
            lastSound = state.successSequence
        }
        LaunchedEffect(state.profileSaveSequence) { if (state.profileSaveSequence > 0) profileOpen = false }
        when {
            state.loading || !launchFinished -> SplashScreen()
            state.storageError -> Surface(Modifier.fillMaxSize(), color = Paper) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.Center) {
                    Poodles(Modifier.size(120.dp), "concerned")
                    Text("Your saved data needs attention", style = MaterialTheme.typography.headlineMedium)
                    Text("Existing files are preserved. Restart Poodles and check available storage.")
                }
            }
            profileOpen -> {
                val draft by vm.profileDraft.collectAsStateWithLifecycle()
                savedTabs.SaveableStateProvider("profile_${state.data.revision}") {
                    ProfileScreen(draft?.let { runCatching { json.decodeFromString<Profile>(it) }.getOrNull() } ?: state.data.profile,
                        state.profileSaving, state.error, vm::dismissError, vm::editProfileDraft,
                        { profileOpen = false }, { vm.saveProfile(it.copy(onboarding = true, cloudConsent = true)) },
                        memories = state.data.comfortMemories, onForget = vm::forgetMemory)
                }
            }
            !state.data.profile.onboarding || !state.data.profile.cloudConsent -> WelcomeScreen(
                state.profileSaving, state.error, vm::dismissError, { profileOpen = true },
                { vm.saveProfile(state.data.profile.copy(onboarding = true, cloudConsent = true)) })
            else -> {
                BackHandler(destination != Destination.Today) { back() }
                val keyboard = WindowInsets.isImeVisible
                Scaffold(containerColor = Paper, modifier = Modifier.imePadding(),
                    topBar = {
                        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (destination != Destination.Today) TextButton({ selected = Destination.Today.name }, Modifier.testTag("go_home")) { Text("Home") }
                            Column(Modifier.weight(1f)) {
                                if (!keyboard && LocalDensity.current.fontScale <= 1.4f && !detail)
                                    Text("Mr. Poodles", style = MaterialTheme.typography.titleLarge)
                            }
                            if (detail) IconButton({ back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Rose) }
                            else if (!keyboard) IconButton({ infoOpen = true }) { Icon(Icons.Rounded.Info, "About Poodles", tint = Rose) }
                            TextButton({ profileOpen = true }, Modifier.semantics { contentDescription = "About you" }) { Text("Settings") }
                        }
                    },
                    bottomBar = { if (!keyboard) PoodlesNavigation(when (destination) {
                        Destination.Recipe -> Destination.Meals
                        Destination.FoodLog -> Destination.entries.firstOrNull { it.name == foodLogParent } ?: Destination.Today
                        else -> destination
                    }) { selected = it.name } }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        if (state.busy && state.taskKind != "chat" && !(destination == Destination.Recipe && state.taskKind == "recipe") && !(destination == Destination.Check && state.taskKind == "food_check") && !(destination == Destination.Move && state.taskKind == "workout") && !(destination == Destination.FoodLog && state.taskKind == "food_log")) {
                            Row(Modifier.fillMaxWidth().background(Cream).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(state.status, Modifier.weight(1f).padding(10.dp), style = MaterialTheme.typography.bodySmall)
                                TextButton(vm::cancel) { Text("Stop") }
                            }
                        }
                        // The destination keys the entire composition, not only the state registry.
                        // Each screen owns its own scroll container; no shared non-chat layout is reused.
                        key(destination) {
                            savedTabs.SaveableStateProvider(destination.name) {
                                DestinationEntrance {
                                    when (destination) {
                                        Destination.Today -> ScreenColumn("today_screen") { TodayScreen(state) { action ->
                                            when (action) {
                                                HomeAction.CHECK -> selected = Destination.Check.name
                                                HomeAction.RECIPE -> openRecipe()
                                                HomeAction.WORKOUT -> selected = Destination.Move.name
                                                HomeAction.FOOD_LOG -> openFoodLog()
                                                HomeAction.PLAN -> { mealPage.value = "Plan"; selected = Destination.Meals.name }
                                                HomeAction.CHAT -> selected = Destination.Chat.name
                                            }
                                        } }
                                        Destination.Check -> FoodCheckWorkspace(state, vm)
                                        Destination.Meals -> ScreenColumn("meals_screen") { MealsScreen(state, vm, mealPage, onFindRecipe = ::openRecipe, onFoodLog = ::openFoodLog) }
                                        Destination.Move -> WorkoutWorkspace(state, vm)
                                        Destination.Chat -> ChatScreen(state, vm)
                                        Destination.Recipe -> RecipeWorkspace(state, vm) { mealPage.value = "Recipes"; selected = Destination.Meals.name }
                                        Destination.FoodLog -> FoodLogWorkspace(state, vm)
                                    }
                                }
                            }
                        }
                    }
                }
                state.error?.takeUnless { state.errorTask in listOf("recipe", "food_check", "food_log") }?.let { message -> AlertDialog(onDismissRequest = vm::dismissError,
                    title = { Text("A little pause") }, text = { Text(message) },
                    confirmButton = { TextButton(vm::dismissError) { Text("Got it") } }) }
                if (infoOpen) AboutDialog { infoOpen = false }
            }
        }
    }
}

@Composable internal fun PoodlesNavigation(selected: Destination, onSelect: (Destination) -> Unit) {
    Surface(color = Color(0xFFFFFDFB), shadowElevation = 5.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Destination.entries.filter { it != Destination.Recipe && it != Destination.FoodLog }.forEach { tab ->
                val active = tab == selected
                Column(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (active) Blush else Color.Transparent)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(tab) })
                    .testTag("tab_${tab.name}").padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(tab.icon, null, Modifier.size(22.dp), tint = if (active) Rose else Plum.copy(alpha = .7f))
                    Text(tab.title, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, color = Plum)
                }
            }
        }
    }
}

@Composable private fun ScreenColumn(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().testTag(tag).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        .padding(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable private fun DestinationEntrance(content: @Composable () -> Unit) {
    val motion = LocalGentleMotion.current
    val progress = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(motion) { if (motion) progress.animateTo(1f, tween(180)) else progress.snapTo(1f) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value; translationY = (1f - progress.value) * 12.dp.toPx() }) { content() }
}
