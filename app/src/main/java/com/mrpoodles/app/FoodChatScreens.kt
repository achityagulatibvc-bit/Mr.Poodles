@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
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
import java.util.Locale

private fun Double.quantity() = if (isFinite()) String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.') else "Unknown"

@Composable internal fun EvidenceSources(origin: ResultOrigin) {
    val opener = LocalUriHandler.current
    var quotes by rememberSaveable { mutableStateOf(false) }
    val sources = origin.retrieval?.sources.orEmpty()
    if (sources.isEmpty()) Text("Source: information you supplied. No internet verification.", style = MaterialTheme.typography.bodySmall)
    sources.forEach { source ->
        if (publicResearchUrl(source.url)) TextButton({ runCatching { opener.openUri(source.url) } }) { Text(source.title) }
        Text(listOfNotNull(source.author, source.publisher, "Retrieved ${source.retrievedAt}").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
    if (sources.isNotEmpty()) {
        TextButton({ quotes = !quotes }) { Text(if (quotes) "Hide source text" else "Review source text") }
        if (quotes) sources.forEach { Text(it.excerpt, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable internal fun SourcedRecipeCard(recipe: Recipe) {
    val value = recipe.sourced ?: return
    CozyCard(color = Cream) {
        Text(recipe.title, style = MaterialTheme.typography.headlineMedium)
        Text(if (value.adaptation == null) "Published recipe · source text reformatted" else "Adapted recipe · not the author's original", fontWeight = FontWeight.Bold)
        Text("${value.servings.quantity()} servings")
        value.originalServingDescription?.let { Text("Published yield: $it", style = MaterialTheme.typography.bodySmall) }
        value.servingBasisNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val waiting = value.waitingMinutes?.let { low ->
            value.waitingMinutesMax?.let { high -> "$low–$high min" } ?: "$low min"
        } ?: "see method / not stated"
        Text("Active: ${value.activeMinutes?.let { "$it min" } ?: "not stated"} · Cooking: ${value.cookingMinutes?.let { "$it min" } ?: "not stated"} · Waiting/chilling: $waiting")
        value.ingredients.forEach { part ->
            val amount = part.amount?.let { low -> low.quantity() + part.amountMax?.let { high -> "–${high.quantity()}" }.orEmpty() }
            Text(listOfNotNull(amount, part.unit, part.name).joinToString(" "))
            if (part.preparation.isNotBlank()) Text(part.preparation, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        value.instructions.forEachIndexed { index, step -> Text("${index + 1}. $step") }
        value.adaptation?.let { adaptation ->
            Text(adaptation.explanation)
            adaptation.substitutions.forEach { change ->
                Text("Original: ${change.original.name}\nReplacement: ${change.replacement.name}\n${change.reason}")
                change.evidence.forEach { Text("“${it.excerpt}”", style = MaterialTheme.typography.bodySmall) }
            }
            adaptation.instructionChanges.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Text("Nutrition is unknown. Original nutrition is not reused after ingredient or portion changes.")
        Text("Review the original method, actual packages and cooking conditions. Missing source details are not verified.", style = MaterialTheme.typography.bodySmall)
        EvidenceSources(value.origin)
    }
}

@Composable private fun FoodAssessmentCard(value: FoodAssessment) {
    CozyCard(color = if (value.outcome == AssessmentOutcome.AVOID) Blush else Cream) {
        Text(when (value.outcome) {
            AssessmentOutcome.AVOID -> "Avoid"
            AssessmentOutcome.NO_LISTED_CONFLICT_FOUND -> "No listed conflict found"
            AssessmentOutcome.NEED_MORE_INFORMATION -> "Need more information"
        }, style = MaterialTheme.typography.headlineSmall)
        Text(value.product)
        Text(listOfNotNull(value.brand, value.variant, value.country, value.labelVersion).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        value.findings.forEach { Text(it) }
        value.uncertainties.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        value.evidence.forEach { Text("“${it.excerpt}”", style = MaterialTheme.typography.bodySmall) }
        EvidenceSources(value.origin)
    }
}

@Composable internal fun RecipeWorkspace(state: ScreenState, vm: PoodlesViewModel, openShelf: () -> Unit) =
    FoodWorkspace(Feature.RECIPE, state, vm, openShelf)

@Composable internal fun FoodCheckWorkspace(state: ScreenState, vm: PoodlesViewModel) = FoodWorkspace(Feature.FOOD_CHECK, state, vm, {})

@Composable private fun FoodWorkspace(feature: Feature, state: ScreenState, vm: PoodlesViewModel, openShelf: () -> Unit) {
    val features by vm.featureStates.collectAsStateWithLifecycle()
    val conversation = features[feature] ?: FeatureConversation()
    val recipe = feature == Feature.RECIPE
    val tag = if (recipe) "recipe" else "check"
    val title = if (recipe) "Find a recipe" else "Can I eat this?"
    val currentRecipe = conversation.result?.recipe ?: state.draft.takeIf { recipe }
    val result = conversation.result
    val busy = conversation.activeRequest != null && state.busy
    val stale = result?.origin?.profileRevision?.let { it != state.data.revision } == true
    var details by rememberSaveable { mutableStateOf(false) }
    var productDetails by rememberSaveable { mutableStateOf(false) }
    var external by rememberSaveable { mutableStateOf(false) }
    val supplied = conversation.draft.startsWith("ingredients", true) || conversation.draft.contains(',') || conversation.completeLabel
    val needsConsent = !state.data.profile.sourceLookupConsent && (recipe || !supplied)
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (stale) Text("Your profile changed. Review this result again before using it.", color = MaterialTheme.colorScheme.error)
            if (recipe) {
                currentRecipe?.let {
                    if (it.sourced != null) SourcedRecipeCard(it) else RecipeCard(it, state.foods)
                    if (state.data.recipes.any { saved -> saved.id == it.id }) Text("Recipe saved.")
                    else CozyAction("Save this recipe", vm::saveDraft, enabled = !state.busy && !stale && state.draft != null)
                }
                TextButton(openShelf) { Text("Saved recipes") }
            } else result?.assessment?.let { FoodAssessmentCard(it) }
        }
    }
    ContextChatLayout(Modifier.testTag("${tag}_screen"), context = content,
        compactContext = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(currentRecipe?.title.takeIf { recipe } ?: title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                TextButton({ details = true }) { Text("Details") }
            }
        }, conversation = {
            LazyColumn(Modifier.fillMaxSize().testTag("${tag}_conversation"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (needsConsent) item {
                    CozyCard {
                        Text("Look up published sources", style = MaterialTheme.typography.titleMedium)
                        Text("Food terms go to Exa or Tavily to find public pages. Your request and relevant food preferences go to Cloudflare for research notes. Unrelated chats are not sent. Results and drafts stay on this phone.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(external, { external = it })
                            Text("Also allow Groq if Cloudflare is unavailable", Modifier.weight(1f))
                        }
                        CozyAction("Allow source lookup", { vm.allowFoodSources(external) }, enabled = !state.profileSaving && !state.busy)
                    }
                }
                if (!recipe) item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(conversation.foodKind == "product", { vm.editProductDetails(kind = "product") }, label = { Text("Packaged food") }, modifier = Modifier.heightIn(min = 48.dp))
                        FilterChip(conversation.foodKind == "dish", { vm.editProductDetails(kind = "dish") }, label = { Text("Dish") }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                    TextButton({ productDetails = !productDetails }) { Text("Product details") }
                    if (productDetails) {
                        OutlinedTextField(conversation.brand, { vm.editProductDetails(brand = it) }, label = { Text("Brand") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(conversation.variant, { vm.editProductDetails(variant = it) }, label = { Text("Exact variant") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(conversation.country, { vm.editProductDetails(country = it) }, label = { Text("Country") }, modifier = Modifier.fillMaxWidth())
                    }
                    if (supplied) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(conversation.completeLabel, { vm.editProductDetails(complete = it) })
                        Text("I supplied the complete ingredients and advisory statements.", Modifier.weight(1f))
                    }
                }
                if (conversation.messages.isEmpty()) item {
                    Text(if (recipe) "Try ‘tiramisu recipe’. Follow up with ‘No coconut’, ‘Make two servings’ or ‘Use hostel equipment’." else "Type a dish, exact product, public manufacturer link, or paste ingredients. Missing evidence never means safe.")
                }
                items(conversation.messages, key = { it.id }) { message -> CozyCard(color = if (message.role == "You") Blush else Cream) {
                    Text(message.role, style = MaterialTheme.typography.labelLarge)
                    Text(message.text)
                } }
                if (busy) item { Text("Poodles is checking the sources…") }
                conversation.error?.let { error -> item { CozyCard {
                    Text(error)
                    Text("Your draft and previous result are still here.")
                    TextButton({ vm.sendFood(feature) }, enabled = !state.busy) { Text("Try again") }
                } } }
                if (state.busy && !busy) item { Text("Poodles is finishing another task. Your draft stays here.") }
            }
        }, composer = {
            Surface(color = Paper) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(conversation.draft, { if (recipe) vm.editRecipeRequest(it) else vm.editFoodDraft(feature, it) },
                        Modifier.weight(1f).testTag("${tag}_draft"), label = { Text(if (recipe) "Your recipe wish" else "Ingredients, dish or product") }, maxLines = 3)
                    CozyAction(if (busy) "Stop" else "Send", { if (busy) vm.cancel() else vm.sendFood(feature) }, Modifier.testTag("${tag}_send"),
                        enabled = busy || (!state.busy && !state.loading && conversation.draft.isNotBlank() && (!needsConsent || !recipe)))
                }
            }
        })
    if (details) ModalBottomSheet(onDismissRequest = { details = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) { content() }
    }
}
