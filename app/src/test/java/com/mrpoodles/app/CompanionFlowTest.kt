package com.mrpoodles.app

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompanionFlowTest {
    private lateinit var app: Application
    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication()
        LocalStore(app).save(AppData(profile = Profile(onboarding = true)))
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for companion state", condition())
    }
    private class FakeModel : CloudModel() {
        var fail = false
        var block: CompletableDeferred<String>? = null
        var lastInstructions = ""
        override fun cancel() = Unit
        override suspend fun generate(instructions: String, input: String, structured: Boolean, maxTokens: Int,
            task: String, history: List<Message>, image: String?, status: (String) -> Unit, stream: (String) -> Unit): String {
            lastInstructions = instructions
            if (fail) throw java.io.IOException("Test connection interrupted")
            return block?.await() ?: "[poodles:comfort:none]\nWe can take this at your pace."
        }
    }
    @Test fun retryUsesSameUserMessageAndDoesNotLearnTwice() {
        val model = FakeModel().apply { fail = true }
        val vm = PoodlesViewModel(app, SavedStateHandle(), model)
        await { !vm.state.value.loading }
        vm.chat("I prefer short replies")
        await { !vm.state.value.busy && vm.state.value.chatError != null }
        assertEquals(1, vm.state.value.data.messages.size)
        model.fail = false
        vm.retryChat()
        await { !vm.state.value.busy && vm.state.value.failedChat == null }
        assertEquals(listOf("You", "Mr. Poodles"), vm.state.value.data.messages.map { it.role })
        assertEquals(1, vm.state.value.data.comfortMemories.single().evidence)
        assertTrue(model.lastInstructions.contains("Short, gentle replies"))
    }
    @Test fun clearingDuringReplyCannotRestoreDeletedConversation() {
        val model = FakeModel().apply { block = CompletableDeferred() }
        val vm = PoodlesViewModel(app, SavedStateHandle(), model)
        await { !vm.state.value.loading }
        vm.chat("hello")
        await { vm.state.value.data.messages.size == 1 }
        vm.clearConversation()
        await { !vm.state.value.busy && vm.state.value.data.messages.isEmpty() }
        model.block!!.complete("[poodles:happy:rose]\nLate reply")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.state.value.data.messages.isEmpty())
        assertNull(vm.state.value.chatError)
        assertNull(vm.state.value.failedChat)
    }
    @Test fun persistentMemoryRequiresConsentIndependentlyOfChatHistory() {
        val vm = PoodlesViewModel(app, SavedStateHandle(), FakeModel())
        await { !vm.state.value.loading }
        vm.chat("I prefer short replies")
        await { !vm.state.value.busy && vm.state.value.data.messages.size == 2 }
        assertEquals(1, vm.state.value.data.comfortMemories.size)
        assertTrue(LocalStore(app).read().comfortMemories.isEmpty())
        assertTrue(LocalStore(app).read().messages.isEmpty())
        vm.saveProfile(vm.state.value.data.profile.copy(rememberComfort = true))
        await { !vm.state.value.profileSaving }
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.data.profile.rememberComfort)
        assertTrue(LocalStore(app).read().profile.rememberComfort)
        assertEquals(1, vm.state.value.data.comfortMemories.size)
        assertEquals("short", LocalStore(app).read().comfortMemories.single().value)
        assertTrue(LocalStore(app).read().messages.isEmpty())
        vm.forgetMemory("length")
        await { vm.state.value.data.comfortMemories.isEmpty() }
        assertTrue(LocalStore(app).read().comfortMemories.isEmpty())
        assertTrue(vm.state.value.data.messages.isEmpty())
    }
}
