package com.mrpoodles.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InvalidRecordsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownIngredientsAndInvalidServingsRenderRecoverableRecords() {
        val recipes = SnapshotMigration.decode(javaClass.getResource("/snapshots/damaged-v1.json")!!.readText()).recipes.take(2)
        compose.setContent { PoodlesTheme(motion = false) { Column { recipes.forEach { RecipeCard(it, emptyList()) } } } }
        compose.onNodeWithText("Old ingredient").assertExists()
        compose.onNodeWithText("Bad servings").assertExists()
        compose.onAllNodesWithText("Unknown kcal / serving").assertCountEquals(2)
        compose.onNodeWithText("60 g · Unknown ingredient (retired-food)").assertExists()
    }

    @Test fun failedManualLogRetainsInputAcrossRestorationAndClosesOnlyAfterSuccessfulRetry() {
        val app = RuntimeEnvironment.getApplication()
        LocalStore(app).save(AppData())
        val vm = PoodlesViewModel(app, SavedStateHandle())
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val state by vm.state.collectAsState()
            PoodlesTheme(motion = false) { Column(Modifier.verticalScroll(rememberScrollState())) { MealsScreen(state, vm) } }
        }
        compose.waitUntil(10000) { compose.runOnIdle { !vm.state.value.loading } }
        compose.onNodeWithText("Food diary").performClick()
        compose.onNodeWithText("Log food, snack or drink").performScrollTo().performClick()
        compose.onNodeWithText("Food and portion").performTextInput("A snack worth keeping")
        val pending = java.io.File(app.filesDir, "poodles-v1.json.pending")
        pending.mkdirs()
        pending.resolve("obstruction").writeText("test")
        try {
            compose.onNodeWithText("Log food").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.runOnIdle { vm.state.value.error != null } }
            compose.onNodeWithText("A snack worth keeping").assertExists()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("A snack worth keeping").assertExists()
        } finally { pending.deleteRecursively() }
        compose.onNodeWithText("Log food").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.runOnIdle { vm.state.value.data.intake.size == 1 } }
        compose.onNodeWithText("What did you have?").assertDoesNotExist()
        org.junit.Assert.assertEquals("A snack worth keeping", LocalStore(app).read().intake.single().name)
        org.junit.Assert.assertNull(LocalStore(app).read().intake.single().kcal)
    }
}
