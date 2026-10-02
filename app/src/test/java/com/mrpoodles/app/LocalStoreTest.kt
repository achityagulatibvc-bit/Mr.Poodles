package com.mrpoodles.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalStoreTest {
    @Test fun repeatedCommitsAreVisibleToANewReader() {
        val app = RuntimeEnvironment.getApplication()
        val store = LocalStore(app)
        store.save(AppData(profile = Profile(name = "First")))
        store.save(AppData(profile = Profile(name = "Second", rememberComfort = true),
            comfortMemories = listOf(ComfortMemory("length", "short", explicit = true))))
        val restored = LocalStore(app).read()
        assertEquals("Second", restored.profile.name)
        assertEquals("short", restored.comfortMemories.single().value)
    }
    @Test fun failedWritePreservesTheCommittedSnapshot() {
        val app = RuntimeEnvironment.getApplication()
        val store = LocalStore(app)
        store.save(AppData(profile = Profile(name = "Keep me")))
        val pending = File(app.filesDir, "poodles-v1.json.pending")
        pending.mkdirs()
        File(pending, "occupied").writeText("test obstruction")
        try {
            assertThrows(java.io.IOException::class.java) { store.save(AppData(profile = Profile(name = "Lost"))) }
            assertEquals("Keep me", store.read().profile.name)
        } finally { pending.deleteRecursively() }
    }
    @Test fun corruptSavedDataIsNotOverwrittenByReading() {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.filesDir, "poodles-v1.json")
        file.writeText("invalid snapshot")
        assertThrows(Exception::class.java) { LocalStore(app).read() }
        assertEquals("invalid snapshot", file.readText())
    }
}
