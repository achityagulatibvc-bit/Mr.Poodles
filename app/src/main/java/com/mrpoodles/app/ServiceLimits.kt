package com.mrpoodles.app

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ServiceLimitException(val scope: String, val retryAt: Long, message: String) : IOException(message)

object ServiceLimits {
    private val scopes = setOf("chat_daily", "chat_minute", "assistance_daily", "assistance_minute", "vision_daily", "vision_minute", "provider_daily", "provider_minute")
    fun bucket(task: String): String = when (task) { "chat" -> "chat"; "vision" -> "vision"; else -> "assistance" }
    fun parse(scopeHeader: String?, retryHeader: String?, now: Long = System.currentTimeMillis()): ServiceLimitException {
        val scope = scopeHeader?.takeIf { it in scopes } ?: "provider_minute"
        val seconds = retryHeader?.toLongOrNull()?.coerceIn(1, 86400)
            ?: retryHeader?.let { runCatching { (ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now) / 1000 }.getOrNull()?.coerceIn(1, 86400) }
            ?: 60L
        val wait = when { seconds >= 3600 -> "${(seconds + 3599) / 3600} hour(s)"; seconds >= 60 -> "${(seconds + 59) / 60} minute(s)"; else -> "$seconds seconds" }
        val message = when (scope) {
            "chat_daily" -> "Today's chat allowance is used. It resets in about $wait. Recipes and photos have separate allowances."
            "assistance_daily" -> "Today's recipe/help allowance is used. It resets in about $wait. Your chat allowance is separate."
            "vision_daily" -> "Today's photo allowance is used. It resets in about $wait. Your chat allowance is separate."
            "provider_daily" -> "The service's free daily compute is used. Try again in about $wait."
            else -> "Messages arrived a little quickly. Try again in about $wait; your message is kept."
        }
        return ServiceLimitException(scope, now + seconds * 1000, message)
    }
}
