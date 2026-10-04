@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.mrpoodles.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@Composable internal fun WorkoutWorkspace(state: ScreenState, vm: PoodlesViewModel) {
    val features by vm.featureStates.collectAsStateWithLifecycle()
    val conversation = features[Feature.WORKOUT] ?: FeatureConversation()
    val workout = conversation.result?.workout ?: state.data.workout
    val busy = state.busy && conversation.activeRequest != null
    val needsConsent = !state.data.profile.sourceLookupConsent
    val stale = workout?.let { (it.origin?.profileRevision ?: it.revision) != state.data.revision } == true
    val problems = workout?.takeIf { it.sourced != null }?.let { SourcedWorkoutRules.validate(it, state.data.profile) }.orEmpty()
    val selected = workout?.id == state.data.workout?.id
    var details by rememberSaveable { mutableStateOf(false) }
    var shelf by rememberSaveable { mutableStateOf(false) }
    var external by rememberSaveable { mutableStateOf(false) }
    // Timer state is workspace-owned, so compacting the context or opening Details does not reset it.
    var restDeadline by rememberSaveable { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(restDeadline) {
        now = System.currentTimeMillis()
        while (restDeadline > now) { delay(250); now = System.currentTimeMillis() }
    }
    val remaining = ((restDeadline - now + 999) / 1000).coerceAtLeast(0)
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Find a workout", style = MaterialTheme.typography.titleLarge)
            if (stale) Text("Your profile changed. Review this session again before using it.", color = MaterialTheme.colorScheme.error)
            problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            workout?.let { current ->
                if (current.sourced != null) SourcedWorkoutCard(current) else CozyCard(color = Cream) {
                    Text(current.title, style = MaterialTheme.typography.titleLarge)
                    Text("Legacy saved workout · no retrieved article evidence")
                    Text("Your earlier session is preserved. Ask for an article-backed session below.")
                    Text("Original rounds: ${current.rounds}")
                    current.exerciseIds.forEach { id ->
                        val historical = exercises.firstOrNull { it.id == id }
                        Text(historical?.name ?: "Historical movement unavailable: $id")
                        historical?.let { Text("${it.seconds} seconds · ${it.instruction}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (state.data.savedWorkouts.any { it.id == current.id }) Text("Workout saved.", Modifier.testTag("workout_saved"))
                else CozyAction("Save this workout", vm::saveWorkout,
                    enabled = selected && !state.busy && !stale && problems.isEmpty() && current.sourced != null)
                CozyAction("Mark workout complete", vm::completeWorkout,
                    enabled = selected && !state.busy && !stale && problems.isEmpty() && current.sourced != null)
                val completions = state.data.workoutCompletions.filter { it.workoutId == current.id }
                if (completions.isNotEmpty()) Text("Completed: ${completions.map { it.date }.distinct().joinToString()}", Modifier.testTag("workout_completions"))
            }
            TextButton({ shelf = true }) { Text("Saved workouts (${state.data.savedWorkouts.size})") }
            if (workout?.sourced != null) CozyCard {
                Text("Optional rest timer", style = MaterialTheme.typography.titleMedium)
                Text(if (restDeadline == 0L) "Rest as long as you need." else if (remaining > 0) "$remaining seconds remaining" else "Timer finished. Continue only when ready.", Modifier.testTag("workout_timer"))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ now = System.currentTimeMillis(); restDeadline = now + 30000 }, Modifier.fillMaxWidth()) { Text("Rest 30 sec") }
                    OutlinedButton({ now = System.currentTimeMillis(); restDeadline = now + 60000 }, Modifier.fillMaxWidth()) { Text("Rest 60 sec") }
                }
                if (restDeadline != 0L) TextButton({ restDeadline = 0L }) { Text("Clear timer") }
                Text("Your timer is a personal reminder, not a prescribed rest interval.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    ContextChatLayout(Modifier.testTag("workout_screen"), context = content,
        compactContext = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(workout?.title ?: "Find a workout", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton({ details = true }) { Text("Details") }
            }
        }, conversation = {
            LazyColumn(Modifier.fillMaxSize().testTag("workout_conversation"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (needsConsent) item { CozyCard {
                    Text("Look up published sources", style = MaterialTheme.typography.titleMedium)
                    Text("Workout terms go to Exa or Tavily for articles and YouTube for video metadata. Your request and relevant movement preferences go to Cloudflare for research notes. Unrelated chats are not sent. Results and drafts stay on this phone.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(external, { external = it })
                        Text("Also allow Groq if Cloudflare is unavailable", Modifier.weight(1f))
                    }
                    CozyAction("Allow source lookup", { vm.allowFoodSources(external) }, enabled = !state.busy && !state.profileSaving)
                    Text("You can change source permission in Settings.", style = MaterialTheme.typography.bodySmall)
                } }
                if (conversation.messages.isEmpty()) item {
                    Text("Try ‘Beginner no equipment workout’. Follow up with ‘Only 15 minutes’, ‘No jumping, quiet hostel’, ‘Push-ups are difficult’ or ‘Make it easier’.")
                }
                items(conversation.messages, key = { it.id }) { message -> CozyCard(color = if (message.role == "You") Blush else Cream) {
                    Text(message.role, style = MaterialTheme.typography.labelLarge)
                    Text(message.text)
                } }
                if (busy) item { Text("Poodles is checking the workout articles…") }
                conversation.error?.let { error -> item { CozyCard {
                    Text(error)
                    Text("Your draft and previous workout are still here.")
                    TextButton(vm::sendWorkout, enabled = !state.busy && !needsConsent) { Text("Try again") }
                } } }
                if (state.busy && !busy) item { Text("Poodles is finishing another task. Your draft stays here.") }
            }
        }, composer = {
            Surface(color = Paper) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(conversation.draft, { vm.editFoodDraft(Feature.WORKOUT, it) },
                        Modifier.weight(1f).testTag("workout_draft"), label = { Text("Your movement wish") }, maxLines = 3)
                    CozyAction(if (busy) "Stop" else "Send", { if (busy) vm.cancel() else vm.sendWorkout() }, Modifier.testTag("workout_send"),
                        enabled = busy || (!state.busy && !state.loading && !needsConsent && conversation.draft.isNotBlank()))
                }
            }
        })
    if (details) ModalBottomSheet(onDismissRequest = { details = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) { content() }
    }
    if (shelf) ModalBottomSheet(onDismissRequest = { shelf = false }) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().testTag("workout_shelf"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Saved workouts", style = MaterialTheme.typography.headlineSmall) }
            if (state.data.savedWorkouts.isEmpty()) item { Text("Saved sessions will appear here.") }
            items(state.data.savedWorkouts, key = { it.id }) { saved -> CozyCard {
                Text(saved.title)
                Text(if (saved.sourced == null) "Legacy session" else saved.sourced.subject)
                Text("${state.data.workoutCompletions.count { it.workoutId == saved.id }} completion records", style = MaterialTheme.typography.bodySmall)
                CozyAction("Open workout", { vm.selectWorkout(saved.id); shelf = false }, enabled = !state.busy)
            } }
        }
    }
}

@Composable internal fun SourcedWorkoutCard(workout: Workout) {
    val value = workout.sourced ?: return
    val opener = LocalUriHandler.current
    val timed = remember(value) { WorkoutProgramming.checked(value) }
    fun clock(seconds: Int) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    CozyCard(color = Cream) {
        Text(workout.title, style = MaterialTheme.typography.headlineMedium)
        Text("Article-backed instructions · programming adjustments labeled separately", fontWeight = FontWeight.Bold)
        Text(timed?.let { "AI-timed program · at most ${it.totalSeconds / 60} minutes" }
            ?: if (value.durationMinutes != null) "Timed schedule needs review. Ask Poodles to rebuild it before following time limits." else "Total duration: not stated / verified")
        value.programmingNotes.forEach { Text(it, fontWeight = FontWeight.Bold) }
        timed?.let { plan ->
            val cooldownStart = plan.totalSeconds - plan.stages.last().seconds
            Text("Start a clock at 0:00. Begin cooldown by ${clock(cooldownStart)}; hard stop at ${clock(plan.totalSeconds)}.",
                Modifier.testTag("workout_hard_stop"), fontWeight = FontWeight.Bold)
            Text("Stop each movement at its work-time, movement-time or rep limit, whichever comes first. Do not rush reps to fill the clock: their cadence and completion time are unknown. If you need extra rest, skip remaining main movements and start cooldown early. Never use cooldown time to finish sets.")
            Text("These are maximum time slots, not required activity time. You may finish early; do not repeat exercises to fill unused recovery time. Stage windows include work, rest and transitions.", style = MaterialTheme.typography.bodySmall)
        }
        listOf(Triple("warmup", "Warm-up", value.warmUp), Triple("main", "Movements", value.movements), Triple("cooldown", "Cooldown", value.cooldown)).forEach { (phase, label, movements) ->
            HorizontalDivider()
            Text(label, style = MaterialTheme.typography.titleLarge)
            val stage = timed?.stages?.singleOrNull { it.phase == phase }
            if (stage != null) {
                val start = timed?.stages.orEmpty().takeWhile { it.phase != phase }.sumOf { it.seconds }
                Text("Planned window ${clock(start)}–${clock(start + stage.seconds)} · ${stage.seconds} sec maximum", Modifier.testTag("workout_stage_$phase"))
                if (stage.recoverySeconds > 0) Text("Includes ${stage.recoverySeconds} sec spare recovery/early-finish allowance. No extra sets.", style = MaterialTheme.typography.bodySmall)
                val omitted = movements.filterIndexed { index, _ -> stage.blocks.none { it.movementIndex == index } }
                if (omitted.isNotEmpty()) Text("Not scheduled in this shorter program: ${omitted.joinToString { it.name }}.", style = MaterialTheme.typography.bodySmall)
            }
            val indices = stage?.blocks?.map { it.movementIndex } ?: movements.indices.toList()
            indices.forEach { index ->
                val movement = movements[index]
                val cap = stage?.blocks?.single { it.movementIndex == index }
                Text(movement.name, style = MaterialTheme.typography.titleMedium)
                cap?.let {
                    Text("AI limits: at most ${it.setCap} set${it.repCap?.let { reps -> ", up to $reps reps" }.orEmpty()}${it.doseSecondsCap?.let { seconds -> ", up to $seconds sec of this movement" }.orEmpty()}. Stop work after ${it.workSeconds} sec or the dose limit, whichever comes first.", fontWeight = FontWeight.Bold)
                    Text("Then ${it.restSeconds} sec rest${if (movement.restSeconds == null) " (AI allowance; article rest is unspecified)" else " (published rest retained)"} and ${it.transitionSeconds} sec to change position safely (AI allowance).")
                    Text("The article text below is reference only; the shorter caps above override its full sets/reps.", style = MaterialTheme.typography.labelLarge)
                }
                if (cap != null) Text(if (value.reducedDose && movement.publishedDose == null) "Earlier reduced-dose settings:" else "Article dose:", style = MaterialTheme.typography.labelLarge)
                Text(movement.publishedDose?.let { "Published dose: $it" }
                    ?: "Sets: ${movement.sets ?: "not stated"} · Reps: ${movement.reps ?: "not stated"} · Time: ${movement.seconds?.let { "$it sec" } ?: "not stated"}")
                Text("Rest: ${movement.restSeconds?.let { "$it sec" } ?: "not specified; rest as needed"}")
                movement.aiAdjustment?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Article instructions:", style = MaterialTheme.typography.labelLarge)
                Text(movement.instructions)
                if (movement.formCues.isEmpty()) Text("Form: see article instructions.", style = MaterialTheme.typography.bodySmall)
                else movement.formCues.forEach { Text("Form: $it") }
                Text("Easier option: ${movement.easierAlternative ?: "not stated in this source; ask Poodles to reduce the session"}")
            }
        }
        value.video?.takeIf { SourcedWorkoutRules.videoValid(it, value.origin, value.warmUp + value.movements + value.cooldown) }?.let { video ->
            HorizontalDivider()
            Text("Technique reference", style = MaterialTheme.typography.titleMedium)
            TextButton({ runCatching { opener.openUri(video.url) } }) { Text(video.title) }
            Text("Channel: ${video.channel}")
            Text(video.verificationNote, style = MaterialTheme.typography.bodySmall)
        } ?: Text("No matching video metadata available.", style = MaterialTheme.typography.bodySmall)
        Text("Move comfortably and stop if something hurts. This is general movement, not injury rehabilitation.", style = MaterialTheme.typography.bodySmall)
        EvidenceSources(value.origin.copy(retrieval = value.origin.retrieval?.let { it.copy(sources = it.sources.filter { source -> source.kind != "youtube_metadata" }) }))
    }
}
