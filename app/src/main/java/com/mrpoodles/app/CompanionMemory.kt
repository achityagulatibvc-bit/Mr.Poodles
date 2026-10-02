package com.mrpoodles.app

import kotlinx.serialization.Serializable

/** Only allowlisted comfort preferences are retained. No private life events or diagnoses are extracted. */
@Serializable data class ComfortMemory(val key: String, val value: String, val evidence: Int = 1,
    val explicit: Boolean = false, val updatedAt: Long = System.currentTimeMillis()) {
    val description: String get() = when (key) {
        "length" -> if (value == "short") "Short, gentle replies" else "Room for longer replies"
        "support" -> if (value == "listen") "Listening before advice" else "Practical help when you ask"
        "nicknames" -> if (value == "no") "No pet names" else "Occasional affectionate nicknames"
        "gifts" -> if (value == "no") "No surprise gifts" else "Occasional little gifts"
        "humor" -> if (value == "no") "A calm tone without jokes" else "A little gentle humor"
        else -> "A comfort preference"
    }
}

object ComfortMemoryRules {
    val keys = setOf("length", "support", "nicknames", "gifts", "humor")
    fun learn(existing: List<ComfortMemory>, text: String, now: Long = System.currentTimeMillis()): List<ComfortMemory> {
        val t = text.lowercase().replace('’', '\'')
        // Questions, quotations and third-party statements are not instructions about the user's preferences.
        if ('?' in t || '"' in t || t.contains("she said") || t.contains("he said") || t.contains("they said")) return existing
        val durable = Regex("\\b(always|usually|prefer|from now on|remember that|i like|i love|i hate|never)\\b").containsMatchIn(t)
        val candidates = mutableListOf<Pair<String, String>>()
        fun has(pattern: String) = Regex(pattern).containsMatchIn(t)
        val negativeLength = has("\\b(don't|do not|never|hate|dislike)\\b.*\\b(short|brief|long|detail)\\b")
        if (!negativeLength && has("\\b(keep (it|replies|your replies) short|shorter replies|short replies|be brief|less text)\\b")) candidates += "length" to "short"
        if (!negativeLength && has("\\b(longer replies|more detail|explain in detail)\\b")) candidates += "length" to "long"
        if (has("\\b(just listen|no advice|don't give (me )?advice|without advice|bas suno)\\b")) candidates += "support" to "listen"
        else if (has("\\b(i prefer advice|i like practical advice|help me solve|give me advice)\\b")) candidates += "support" to "help"
        if (has("\\b(no (pet names|nicknames)|don't (use|call me).*?(pet names|nicknames|sunshine|sweetpea|lovely)|stop.*?(pet names|nicknames)|i (hate|dislike|don't like) (pet names|nicknames))\\b")) candidates += "nicknames" to "no"
        else if (has("\\bi (like|love) (the |your )?(nicknames|pet names)\\b")) candidates += "nicknames" to "yes"
        if (has("\\b(no (more )?(gifts|roses|chocolates)|stop (the |sending )?gifts|don't (send|give).*?(gifts|roses|chocolates)|i (hate|dislike|don't like) (gifts|roses|chocolates))\\b")) candidates += "gifts" to "no"
        else if (has("\\bi (like|love) (the |your )?(gifts|roses|chocolates)\\b")) candidates += "gifts" to "yes"
        if (has("\\b(no jokes|don't joke|stop joking|i (hate|dislike|don't like) (jokes|humor))\\b")) candidates += "humor" to "no"
        else if (has("\\bi (like|love) (the |your )?(jokes|humor)\\b")) candidates += "humor" to "yes"
        return candidates.fold(existing.filter { it.key in keys }) { memories, (key, value) ->
            val old = memories.find { it.key == key }
            val explicit = durable || (value == "no")
            memories.filterNot { it.key == key } + ComfortMemory(key, value,
                if (old?.value == value) (old.evidence + 1).coerceAtMost(10) else 1,
                explicit || (old?.value == value && old.explicit), now)
        }.takeLast(5)
    }
    fun context(memories: List<ComfortMemory>, now: Long = System.currentTimeMillis()): String = memories
        .filter { it.key in keys && (it.explicit || it.evidence >= 3) && now - it.updatedAt < 90L * 86400000 }
        .joinToString("; ") { it.description }
    fun forgetKey(text: String): String? {
        val t = text.lowercase().trim()
        if (!Regex("^(please |poodles,? |can you (please )?|could you (please )?|ye |yeh |sab )?(forget|bhool)\\b").containsMatchIn(t)) return null
        return when {
            Regex("\\b(everything|all|sab)\\b").containsMatchIn(t) -> "all"
            Regex("\\b(nickname|nicknames|pet names)\\b").containsMatchIn(t) -> "nicknames"
            Regex("\\b(gift|gifts|rose|roses|chocolate|chocolates)\\b").containsMatchIn(t) -> "gifts"
            Regex("\\b(advice|listen|listening)\\b").containsMatchIn(t) -> "support"
            Regex("\\b(short|long|length|replies)\\b").containsMatchIn(t) -> "length"
            Regex("\\b(joke|jokes|humor)\\b").containsMatchIn(t) -> "humor"
            else -> "clarify"
        }
    }
}

data class CompanionReply(val text: String, val mood: String = "listening", val gift: String = "none")
object CompanionReplyRules {
    val moods = setOf("listening", "thinking", "happy", "comfort", "concerned", "sleepy", "encouraging")
    private val header = Regex("^\\[poodles:([a-z]+):([a-z]+)]\\s*", RegexOption.IGNORE_CASE)
    /** A header may arrive split across SSE chunks. Never flash machine metadata into the conversation. */
    fun visible(raw: String): String {
        val t = raw.trimStart()
        if (t.isNotEmpty() && "[poodles:".startsWith(t, true)) return ""
        if (t.startsWith("[poodles:", true)) return if (']' in t) t.substringAfter(']').trimStart() else ""
        return t
    }
    fun parse(raw: String): CompanionReply {
        val match = header.find(raw.trimStart())
        val mood = match?.groupValues?.get(1)?.lowercase()?.takeIf { it in moods } ?: "listening"
        val gift = match?.groupValues?.get(2)?.lowercase()?.takeIf { it in setOf("rose", "chocolate") } ?: "none"
        return CompanionReply(visible(raw).trim(), mood, if (mood in setOf("concerned", "thinking")) "none" else gift)
    }
    fun allowGift(messages: List<Message>, memories: List<ComfortMemory>): Boolean =
        memories.none { it.key == "gifts" && it.value == "no" } &&
            messages.count { it.role == "Mr. Poodles" } >= 3 &&
            messages.takeLast(12).none { it.gift != "none" }
}
