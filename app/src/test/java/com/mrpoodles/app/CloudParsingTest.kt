package com.mrpoodles.app

import org.junit.Test
import org.junit.Assert.*

class CloudParsingTest {
    @Test fun removesOnlyOuterJsonFence() { assertEquals("{\"ok\":true}", cleanJsonReply("```json\n{\"ok\":true}\n```")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonObjectOutput() { cleanJsonReply("[]") }
    @Test fun errorsDoNotExposeProviderOrCredentialDetails() {
        for (code in listOf(400, 401, 403, 429, 500)) {
            assertFalse(friendlyHttpError(code).contains("key"))
            assertFalse(friendlyHttpError(code).contains("OpenRouter"))
        }
    }
    @Test fun newProfileEnablesOnlineHelpButNotPersistentChatOrSound() {
        val profile = json.decodeFromString<Profile>("{\"name\":\"Friend\",\"onboarding\":true}")
        assertTrue(profile.cloudConsent)
        assertFalse(profile.rememberChats)
        assertFalse(profile.sounds)
        assertTrue(profile.gentleMotion)
    }
    @Test fun explicitOnlineOptOutIsPreserved() {
        val profile = json.decodeFromString<Profile>("{\"cloudConsent\":false}")
        assertFalse(profile.cloudConsent)
    }
}
