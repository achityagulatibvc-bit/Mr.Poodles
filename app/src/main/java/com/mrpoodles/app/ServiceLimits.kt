package com.mrpoodles.app

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ServiceLimitException(val scope: String, val retryAt: Long, message: String) : IOException(message)

object ServiceLimits {
    private val scopes = setOf("chat_daily", "chat_minute", "assistance_daily", "assistance_minute", "provider_daily", "provider_minute",
        "research_provider", "research_recipe", "research_food_check", "research_workout", "research_food_log", "research_plan")
    fun bucket(task: String): String = if (task == "chat") "chat" else "assistance"
    fun parse(scopeHeader: String?, retryHeader: String?, now: Long = System.currentTimeMillis()): ServiceLimitException {
        val scope = scopeHeader?.takeIf { it in scopes } ?: "provider_minute"
        val seconds = (retryHeader?.toLongOrNull()
            ?: retryHeader?.let { runCatching { kotlin.math.ceil((ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now) / 1000.0).toLong() }.getOrNull() }
            ?: 60L).coerceIn(1, (Long.MAX_VALUE - now.coerceAtLeast(0)) / 1000)
        val wait = when { seconds >= 3600 -> "${(seconds + 3599) / 3600} hour(s)"; seconds >= 60 -> "${(seconds + 59) / 60} minute(s)"; else -> "$seconds seconds" }
        val message = when (scope) {
            "research_provider" -> "Source lookup needs a pause. Try again in about $wait; your draft and previous result are kept."
            "research_recipe", "research_food_check", "research_workout", "research_food_log", "research_plan" -> "This task's source lookup allowance is used. Try again in about $wait; your draft is kept."
            "chat_daily" -> "Today's chat allowance is used. It resets in about $wait. Recipes have a separate allowance."
            "assistance_daily" -> "Today's recipe/help allowance is used. It resets in about $wait. Your chat allowance is separate."
            "provider_daily" -> "The service's free daily compute is used. Try again in about $wait."
            else -> "Messages arrived a little quickly. Try again in about $wait; your message is kept."
        }
        return ServiceLimitException(scope, now + seconds * 1000, message)
    }
}
