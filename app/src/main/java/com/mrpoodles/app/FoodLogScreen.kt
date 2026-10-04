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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

@Composable internal fun FoodLogWorkspace(state: ScreenState, vm: PoodlesViewModel) {
    val features by vm.featureStates.collectAsStateWithLifecycle()
    val conversation = features[Feature.FOOD_LOG] ?: FeatureConversation()
    val prepared = conversation.preparedLog
    val busy = state.busy && conversation.activeRequest != null
    val needsConsent = !state.data.profile.sourceLookupConsent
    val ids = conversation.result?.intakeIds.orEmpty()
    val committed = ids.mapNotNull { id -> state.data.intake.find { it.id == id } }
    val pending = prepared?.takeIf { batch -> batch.operations.any { op -> state.data.intakeOperations.none { it.operationId == op.id } } }
    val saved = ids.isNotEmpty() && committed.size == ids.size && pending == null && !busy && conversation.error == null
    val selected = conversation.selectedDate ?: LocalDate.now().toString()
    val date = runCatching { LocalDate.parse(selected) }.getOrDefault(LocalDate.now())
    var dateDraft by rememberSaveable(selected) { mutableStateOf(selected) }
    var details by rememberSaveable { mutableStateOf(false) }
    var external by rememberSaveable { mutableStateOf(false) }
    val diary = state.data.intake.filter { it.date == selected }
    val known = diary.mapNotNull { it.kcal }.sum()
    val incomplete = diary.any { it.kcal == null }
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Log what I ate", style = MaterialTheme.typography.titleLarge)
            Text("Tell Poodles what you ate. Published nutrition is a rough estimate; missing values stay unknown.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.selectDiaryDate(date.minusDays(1).toString()) }, Modifier.weight(1f)) { Text("Previous day") }
                OutlinedButton({ vm.selectDiaryDate(date.plusDays(1).toString()) }, Modifier.weight(1f)) { Text("Next day") }
            }
            OutlinedTextField(dateDraft, { dateDraft = it }, Modifier.fillMaxWidth().testTag("food_log_date"),
                label = { Text("Diary date · YYYY-MM-DD") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ vm.selectDiaryDate(dateDraft) }, enabled = runCatching { LocalDate.parse(dateDraft) }.isSuccess) { Text("Show date") }
                TextButton({ vm.selectDiaryDate(LocalDate.now().toString()) }) { Text("Today") }
            }
            Text("$selected · ${foodLogNumber(known)} known kcal${if (incomplete) " · total incomplete" else ""}", Modifier.testTag("food_log_total"))
            Text("Browsing dates does not change an unsent log. Say ‘yesterday’ or include a date in your message; otherwise food is logged today.", style = MaterialTheme.typography.bodySmall)
            if (pending != null) {
                Text("Prepared estimate · not saved yet", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("food_log_pending"))
                pending.entries.forEach { FoodLogEntryDetails(it) }
                if (pending.entries.any { it.kcal == null }) {
                    Text("At least one food has unknown calories. Save the whole batch only if you want an unknown-nutrition entry.")
                    CozyAction("Save with unknown nutrition", vm::saveUnknownFoodLog,
                        enabled = !state.busy && !state.loading, modifier = Modifier.testTag("food_log_save_unknown"))
                    TextButton(vm::sendFoodLog, enabled = !state.busy && !needsConsent) { Text("Retry nutrition lookup") }
                }
            } else {
                if (saved) {
                    Text("Saved in your diary.", Modifier.testTag("food_log_saved"), fontWeight = FontWeight.Bold)
                    committed.forEach { FoodLogEntryDetails(it) }
                    CozyAction("Undo last food log", vm::undoFoodLog, enabled = !state.busy, modifier = Modifier.testTag("food_log_undo"))
                    Text("To change the amount, send ‘I ate two’ or ‘Half’. Half halves the current amount. Use Edit in Meals → Diary for a specific entry.", style = MaterialTheme.typography.bodySmall)
                }
                if (diary.isEmpty()) Text("No saved food for this date yet.")
                else {
                    Text("Saved on this date", style = MaterialTheme.typography.titleMedium)
                    diary.forEach { entry ->
                        Text("${entry.name} · ${entry.kcal?.let { "${foodLogNumber(it)} kcal" } ?: "calories unknown"}")
                    }
                }
            }
            if (conversation.result?.intakeIds.isNullOrEmpty()) conversation.result?.origin?.let { EvidenceSources(it) }
        }
    }
    ContextChatLayout(Modifier.testTag("food_log_screen"), context = content, compactContext = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Log what I ate", Modifier.weight(1f))
            TextButton({ details = true }) { Text("Details") }
        }
    }, conversation = {
        LazyColumn(Modifier.fillMaxSize().testTag("food_log_conversation"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (needsConsent) item { CozyCard {
                Text("Look up published nutrition", style = MaterialTheme.typography.titleMedium)
                Text("Food names go to Exa or Tavily for source lookup and Cloudflare for research notes. Unrelated chats are not sent. Your diary stays on this phone.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(external, { external = it })
                    Text("Also allow Groq if Cloudflare is unavailable", Modifier.weight(1f))
                }
                CozyAction("Allow source lookup", { vm.allowFoodSources(external) }, enabled = !state.busy && !state.profileSaving)
            } }
            if (conversation.messages.isEmpty()) item {
                Text("Try ‘Pyaaz kachori khayi hai’, ‘I ate two bananas yesterday’ or ‘How many calories in kachori?’. Questions never add food.")
            }
            items(conversation.messages, key = { it.id }) { message -> CozyCard(color = if (message.role == "You") Blush else Cream) {
                Text(message.role, style = MaterialTheme.typography.labelLarge)
                Text(message.text)
            } }
            if (busy) item { Text("Poodles is checking portions and published nutrition…") }
            conversation.error?.let { error -> item { CozyCard {
                Text(error)
                Text("Your draft and previous diary entries are still here.")
                TextButton(vm::sendFoodLog, enabled = !state.busy && !needsConsent) { Text("Try again") }
            } } }
            if (state.busy && !busy) item { Text("Poodles is finishing another task. Your draft stays here.") }
        }
    }, composer = {
        Surface(color = Paper) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(conversation.draft, { vm.editFoodDraft(Feature.FOOD_LOG, it) },
                    Modifier.weight(1f).testTag("food_log_draft"), label = { Text("What did you eat?") }, maxLines = 3)
                CozyAction(if (busy) "Stop" else "Send", { if (busy) vm.cancel() else vm.sendFoodLog() }, Modifier.testTag("food_log_send"),
                    enabled = busy || (!state.busy && !state.loading && !needsConsent && conversation.draft.isNotBlank()))
            }
        }
    })
    if (details) ModalBottomSheet(onDismissRequest = { details = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) { content() }
    }
}

@Composable private fun FoodLogEntryDetails(entry: Intake) {
    CozyCard(color = Cream) {
        Text(entry.name, style = MaterialTheme.typography.titleMedium)
        Text("${entry.date} · ${entry.portion?.let { "${foodLogNumber(it.amount)} ${it.description.ifBlank { it.unit }}" } ?: "Portion not recorded"}")
        if (entry.portion?.assumed == true) Text("Portion assumption — tell Poodles if the amount or size was different.", fontWeight = FontWeight.Bold)
        Text("Calories: ${entry.kcal?.let { "about ${foodLogNumber(it)} kcal" } ?: "unknown"}")
        Text("Protein: ${entry.protein?.let { "${foodLogNumber(it)} g" } ?: "unknown"} · Carbs: ${entry.carbs?.let { "${foodLogNumber(it)} g" } ?: "unknown"} · Fat: ${entry.fat?.let { "${foodLogNumber(it)} g" } ?: "unknown"}")
        entry.estimate?.let { Text(it.explanation, style = MaterialTheme.typography.bodySmall) }
        entry.origin?.let { EvidenceSources(it) }
    }
}
