package com.mrpoodles.app

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** One versioned, atomic snapshot keeps this small single-user app recoverable. */
class LocalStore(context: Context) {
    private val file = File(context.filesDir, "poodles-v1.json")
    private val pending = File(context.filesDir, "poodles-v1.json.pending")
    private val legacyBackup = File(context.filesDir, "poodles-v1.json.bak")
    @Synchronized fun read(): AppData {
        // Preserve compatibility with snapshots written by the previous AtomicFile implementation.
        if (legacyBackup.exists()) {
            json.decodeFromString<AppData>(legacyBackup.readText())
            Files.move(legacyBackup.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        return if (!file.exists()) AppData() else json.decodeFromString<AppData>(file.readText())
    }
    @Synchronized fun save(data: AppData) {
        try {
            pending.outputStream().use { output ->
                output.write(json.encodeToString(AppData.serializer(), data).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // Unlike AtomicFile.finishWrite, move reports commit failures instead of merely logging them.
            // Both paths share a directory/filesystem. A failed atomic move leaves the old snapshot intact.
            Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { pending.delete() }
    }
}
