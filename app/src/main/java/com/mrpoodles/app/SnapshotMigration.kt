package com.mrpoodles.app

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable data class RecoveredRecord(val path: String, val raw: JsonElement, val reason: String)

/** Read-only migration. Original bytes are untouched until a subsequent successful atomic save. */
object SnapshotMigration {
    fun decode(text: String): AppData {
        val root = json.parseToJsonElement(text).jsonObject.toMutableMap()
        val version = root["schemaVersion"]?.jsonPrimitive?.int ?: 1
        require(version in 1..2) { "This snapshot needs a compatible app version." }
        val recovered = root["recoveredRecords"]?.let {
            json.decodeFromJsonElement<List<RecoveredRecord>>(it)
        }.orEmpty().toMutableList()
        fun <T> records(key: String, serializer: KSerializer<T>) {
            val items = root[key] ?: return
            // A malformed collection is preserved as a whole, not silently discarded.
            if (items !is JsonArray) {
                recovered += RecoveredRecord(key, items, "Expected a list")
                root[key] = JsonArray(emptyList())
                return
            }
            root[key] = JsonArray(items.mapIndexedNotNull { index, raw ->
                val prepared = stableIds(raw, "$key[$index]")
                try { json.decodeFromJsonElement(serializer, prepared); prepared }
                catch (_: Exception) {
                    recovered += RecoveredRecord("$key[$index]", raw, "Record could not be decoded")
                    null
                }
            })
        }
        records("recipes", Recipe.serializer())
        records("recentRecipes", Recipe.serializer())
        records("meals", Meal.serializer())
        records("intake", Intake.serializer())
        records("messages", Message.serializer())
        records("checks", CheckRecord.serializer())
        records("comfortMemories", ComfortMemory.serializer())
        records("savedWorkouts", Workout.serializer())
        records("workoutCompletions", WorkoutCompletion.serializer())
        // A broken operation ledger must block writes; dropping a receipt could replay an old action.
        fun <T> optional(key: String, serializer: KSerializer<T>) {
            val raw = root[key]?.takeUnless { it == JsonNull } ?: return
            val prepared = stableIds(raw, key)
            try { json.decodeFromJsonElement(serializer, prepared); root[key] = prepared }
            catch (_: Exception) {
                recovered += RecoveredRecord(key, raw, "Record could not be decoded")
                root[key] = JsonNull
            }
        }
        optional("workout", Workout.serializer())
        optional("recipeDraft", Recipe.serializer())
        root["schemaVersion"] = JsonPrimitive(2)
        root["recoveredRecords"] = json.encodeToJsonElement(recovered)
        val decoded = json.decodeFromJsonElement<AppData>(JsonObject(root))
        return decoded.copy(features = decoded.features.mapValues { (_, state) -> state.recover() })
    }

    private fun stableIds(raw: JsonElement, path: String): JsonElement {
        if (raw !is JsonObject) return raw
        val values = raw.toMutableMap()
        val needsId = "title" in values || "date" in values && ("name" in values || "slot" in values || "workoutId" in values) || "role" in values
        if (needsId && "id" !in values) values["id"] = JsonPrimitive(
            UUID.nameUUIDFromBytes("$path:$raw".toByteArray(Charsets.UTF_8)).toString())
        values["recipe"]?.let { values["recipe"] = stableIds(it, "$path.recipe") }
        return JsonObject(values)
    }
}
