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
    @Test fun toolsDoNotShareTheChatBucket() {
        assertEquals("chat", ServiceLimits.bucket("chat"))
        assertEquals("assistance", ServiceLimits.bucket("recipe"))
    }
    @Test fun monthlyResearchWaitsAreNotShortenedToOneDay() {
        val limit = ServiceLimits.parse("research_provider", "2592000", 1000)
        assertEquals(2592001000L, limit.retryAt)
        assertTrue(limit.message!!.contains("draft"))
        assertEquals(60001L, ServiceLimits.parse("research_recipe", "Thu, 01 Jan 1970 00:01:00 GMT", 1).retryAt)
    }
}
