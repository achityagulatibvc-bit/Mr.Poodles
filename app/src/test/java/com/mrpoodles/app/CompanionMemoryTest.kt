package com.mrpoodles.app

import org.junit.Assert.*
import org.junit.Test

class CompanionMemoryTest {
    @Test fun oneTemporaryRequestDoesNotBecomeAFixedPreference() {
        val memories = ComfortMemoryRules.learn(emptyList(), "Just listen today", 100)
        assertEquals("", ComfortMemoryRules.context(memories, 101))
        val repeated = ComfortMemoryRules.learn(ComfortMemoryRules.learn(memories, "just listen", 102), "just listen", 103)
        assertTrue(ComfortMemoryRules.context(repeated, 104).contains("Listening"))
    }
    @Test fun explicitPreferenceAppliesImmediatelyAndCanBeReversed() {
        val short = ComfortMemoryRules.learn(emptyList(), "I prefer short replies", 100)
        assertTrue(ComfortMemoryRules.context(short, 101).contains("Short"))
        val longer = ComfortMemoryRules.learn(short, "I prefer longer replies", 102)
        assertEquals(1, longer.size)
        assertTrue(ComfortMemoryRules.context(longer, 103).contains("longer"))
    }
    @Test fun quotedThirdPartyAndSensitiveEventsAreNotMemories() {
        for (text in listOf("She said no gifts", "Do you prefer short replies?", "My diagnosis is depression", "I was hurt yesterday", "My friend said \"no nicknames\"")) {
            assertTrue(ComfortMemoryRules.learn(emptyList(), text).isEmpty())
        }
    }
    @Test fun negativeStatementsNeverLearnTheOppositePreference() {
        assertTrue(ComfortMemoryRules.learn(emptyList(), "I don't like short replies").isEmpty())
        assertEquals("no", ComfortMemoryRules.learn(emptyList(), "I don't like gifts").single().value)
        assertEquals("no", ComfortMemoryRules.learn(emptyList(), "I hate nicknames").single().value)
    }
    @Test fun negativeBoundariesStopGiftsImmediately() {
        val memories = ComfortMemoryRules.learn(emptyList(), "Don't send gifts", 100)
        assertEquals("no", memories.single().value)
        assertTrue(memories.single().explicit)
        val conversation = List(6) { Message("Mr. Poodles", "hello") }
        assertFalse(CompanionReplyRules.allowGift(conversation, memories))
        assertTrue(CompanionReplyRules.allowGift(conversation, emptyList()))
    }
    @Test fun preferencesExpireAndUnrecognizedKeysNeverReachContext() {
        val old = listOf(ComfortMemory("length", "short", explicit = true, updatedAt = 0),
            ComfortMemory("diagnosis", "anything", explicit = true, updatedAt = Long.MAX_VALUE))
        assertEquals("", ComfortMemoryRules.context(old, 91L * 86400000))
    }
    @Test fun forgetIntentIsExplicitAndScoped() {
        assertEquals("gifts", ComfortMemoryRules.forgetKey("Forget my gift preference"))
        assertEquals("all", ComfortMemoryRules.forgetKey("Forget everything"))
        assertEquals("clarify", ComfortMemoryRules.forgetKey("Forget that"))
        assertEquals("clarify", ComfortMemoryRules.forgetKey("Ye bhool jao"))
        assertNull(ComfortMemoryRules.forgetKey("I like gifts"))
        assertNull(ComfortMemoryRules.forgetKey("I wish I could forget everything that happened"))
    }
    @Test fun metadataDoesNotFlashDuringSplitStreaming() {
        val raw = "[poodles:comfort:rose]\nYou don't have to fix everything today."
        for (length in 1..raw.indexOf(']')) assertEquals("", CompanionReplyRules.visible(raw.take(length)))
        val parsed = CompanionReplyRules.parse(raw)
        assertEquals("comfort", parsed.mood)
        assertEquals("rose", parsed.gift)
        assertFalse(parsed.text.contains("poodles:"))
    }
    @Test fun malformedMetadataFallsBackAndDistressHasNoGift() {
        assertEquals("listening", CompanionReplyRules.parse("[poodles:invalid:unknown]\nHello").mood)
        assertEquals("Hello", CompanionReplyRules.parse("Hello").text)
        assertEquals("none", CompanionReplyRules.parse("[poodles:concerned:rose]\nI'm listening.").gift)
    }
    @Test fun giftsHaveAConversationCooldown() {
        assertFalse(CompanionReplyRules.allowGift(emptyList(), emptyList()))
        assertFalse(CompanionReplyRules.allowGift(List(5) { Message("Mr. Poodles", "Hi", gift = "rose") }, emptyList()))
    }
    @Test fun oldSavedDataLoadsWithoutEnablingMemory() {
        val old = json.decodeFromString<AppData>("""{"profile":{"name":"Friend","rememberChats":true},"messages":[{"role":"You","text":"hello","id":"1"}]}""")
        assertFalse(old.profile.rememberComfort)
        assertTrue(old.comfortMemories.isEmpty())
        assertEquals("none", old.messages.single().gift)
        assertEquals("Friend", old.profile.name)
    }
}
