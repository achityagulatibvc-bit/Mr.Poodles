@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.mrpoodles.app

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun ChatScreen(state: ScreenState, vm: PoodlesViewModel) {
    var draft by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf<String?>(null) }
    var editProfile by rememberSaveable { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val keyboard = WindowInsets.isImeVisible
    val replying = state.busy && state.taskKind == "chat"
    val stream by vm.stream.collectAsStateWithLifecycle()
    LaunchedEffect(state.data.messages.lastOrNull()?.id) {
        if (state.data.messages.lastOrNull()?.role == "You" && sent != null) {
            if (draft == sent) draft = ""
            sent = null
        }
        if (!list.canScrollForward || state.data.messages.lastOrNull()?.role == "You") {
            if (list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
    }
    LaunchedEffect(stream) {
        if (replying && !list.isScrollInProgress && list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == list.layoutInfo.totalItemsCount - 1) {
            list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), Int.MAX_VALUE)
        }
    }
    LaunchedEffect(state.memoryNotice) {
        if (state.memoryNotice != null && sent != null) {
            if (draft == sent) draft = ""
            sent = null
        }
    }
    Column(Modifier.fillMaxSize().testTag("chat_screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).background(Lavender, RoundedCornerShape(24.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CompanionMascot(Modifier.size(if (keyboard) 58.dp else 86.dp), if (replying) "thinking" else state.companionMood, state.data.profile.mode == "Hostel")
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text("A chat with Poodles", style = MaterialTheme.typography.titleMedium)
                Text(if (replying) "Putting a little thought into it…" else "A softer place to land.", style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton({ details = true }) { Icon(Icons.Rounded.MoreHoriz, "Conversation options") }
                DropdownMenu(details, { details = false }) {
                    DropdownMenuItem(text = { Text(if (editProfile) "Back to chatting" else "Ask for a profile change") }, onClick = { editProfile = !editProfile; details = false })
                    DropdownMenuItem(text = { Text("Clear conversation") }, onClick = { clear = true; details = false })
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_messages"), state = list,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.data.messages.isEmpty()) item("welcome") {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Come as you are.", style = MaterialTheme.typography.headlineMedium)
                    Text("A good day, a messy day, or nothing to say. There's room for all of it here.", color = Plum)
                    Text("Poodles is an AI companion. Your messages are processed to reply to you.", style = MaterialTheme.typography.bodySmall, color = Rose)
                }
            }
            items(state.data.messages, key = { it.id }) { message ->
                val mine = message.role == "You"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    Surface(color = if (mine) Blush else Color.White,
                        shape = RoundedCornerShape(22.dp, 22.dp, if (mine) 6.dp else 22.dp, if (mine) 22.dp else 6.dp),
                        modifier = Modifier.widthIn(max = 340.dp).padding(start = if (mine) 24.dp else 0.dp, end = if (mine) 0.dp else 24.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!mine) Text("Poodles", style = MaterialTheme.typography.labelMedium, color = Rose)
                            Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (message.gift != "none") GiftMoment(message)
                }
            }
            if (replying) item("reply") {
                Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Poodles", color = Rose, style = MaterialTheme.typography.labelMedium)
                        Text(stream.ifBlank { "One little moment…" })
                    }
                }
            }
            if ((state.failedChat != null || state.chatError != null) && !replying) item("retry") {
                CozyCard(color = Cream) {
                    Text(state.chatError ?: "Your message is still here.", style = MaterialTheme.typography.bodyMedium)
                    if (state.failedChat != null) TextButton(vm::retryChat, enabled = !state.busy) { Text("Try reply again") }
                }
            }
            state.memoryNotice?.let { notice -> item("memory_notice") {
                CozyCard(color = Sage) { Text(notice); TextButton(vm::dismissMemoryNotice) { Text("Got it") } }
            } }
            state.proposal?.let { proposal -> item("proposal") {
                CozyCard(color = Cream) {
                    Text("Does this feel right?", style = MaterialTheme.typography.titleLarge)
                    Text("${proposal.field}: ${proposal.value}")
                    Text(proposal.explanation)
                    Row { TextButton(vm::rejectProposal) { Text("Keep current") }; CozyAction("Save change", vm::acceptProposal) }
                }
            } }
        }
        Surface(color = Paper) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                if (editProfile) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Suggest a profile change", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton({ editProfile = false }) { Text("Back to chat") }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft, { draft = it.take(1200) }, Modifier.weight(1f).testTag("chat_draft"),
                        placeholder = { Text(if (editProfile) "What should we change?" else "Tell Poodles…") },
                        maxLines = if (keyboard) 3 else 4, shape = RoundedCornerShape(24.dp))
                    FilledIconButton(onClick = {
                        if (replying) vm.cancel()
                        else if (editProfile) vm.proposeChange(draft)
                        else { sent = draft; vm.chat(draft) }
                    }, enabled = replying || (draft.isNotBlank() && !state.busy), modifier = Modifier.size(52.dp)) {
                        Icon(if (replying) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward, if (replying) "Stop reply" else "Send to Poodles")
                    }
                }
            }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("A fresh conversation?") },
        text = { Text("This deletes this conversation. Comfort memories can be reviewed separately in About you.") },
        confirmButton = { TextButton({ vm.clearConversation(); clear = false }) { Text("Clear conversation") } },
        dismissButton = { TextButton({ clear = false }) { Text("Keep it") } })
}

@Composable private fun GiftMoment(message: Message) {
    var dismissed by rememberSaveable(message.id) { mutableStateOf(false) }
    var shown by rememberSaveable(message.id) { mutableStateOf(false) }
    val motion = LocalGentleMotion.current
    val entry = remember(message.id) { Animatable(if (shown || !motion) 1f else 0f) }
    LaunchedEffect(message.id) {
        if (!shown) { shown = true; if (motion) entry.animateTo(1f, tween(450)) else entry.snapTo(1f) }
    }
    if (!dismissed) {
        Row(Modifier.graphicsLayer { alpha = entry.value; translationY = (1f - entry.value) * 12.dp.toPx() }
            .padding(top = 8.dp).background(Cream, RoundedCornerShape(22.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GiftIllustration(message.gift, Modifier.size(60.dp))
            Text(if (message.gift == "rose") "A little rose, just because." else "A tiny imaginary treat.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton({ dismissed = true }) { Icon(Icons.Rounded.Close, "Dismiss gift", Modifier.size(18.dp)) }
        }
    }
}
