package com.mrpoodles.app

import org.junit.Assert.*
import org.junit.Test

class ServiceLimitsTest {
    @Test fun minuteAndDailyErrorsAreNotTheSameVaguePause() {
        val minute = ServiceLimits.parse("chat_minute", "42", 1000)
        assertEquals(43000, minute.retryAt)
        assertTrue(minute.message!!.contains("42 seconds"))
        val daily = ServiceLimits.parse("assistance_daily", "3600", 1000)
        assertTrue(daily.message!!.contains("chat allowance is separate"))
        assertEquals(3601000, daily.retryAt)
    }
    @Test fun malformedProviderHeadersDoNotBecomeUserFacingContent() {
        val limit = ServiceLimits.parse("secret arbitrary text", "nonsense", 0)
        assertEquals("provider_minute", limit.scope)
        assertEquals(60000, limit.retryAt)
        assertFalse(limit.message!!.contains("secret"))
    }
    @Test fun retryAfterHttpDatesAreHonored() {
        assertEquals(60000, ServiceLimits.parse("chat_minute", "Thu, 01 Jan 1970 00:01:00 GMT", 0).retryAt)
    }
    @Test fun photosAndToolsDoNotShareTheChatBucket() {
        assertEquals("chat", ServiceLimits.bucket("chat"))
        assertEquals("vision", ServiceLimits.bucket("vision"))
        assertEquals("assistance", ServiceLimits.bucket("recipe"))
    }
}
