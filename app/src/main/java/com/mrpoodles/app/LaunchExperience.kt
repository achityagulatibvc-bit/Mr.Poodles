package com.mrpoodles.app

import android.os.SystemClock
import androidx.compose.runtime.*
import kotlinx.coroutines.delay

/** Retained by the activity ViewModel; rotation and ordinary resume never restart the launch deadline. */
internal class ColdLaunchGate(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val startedAt = now()
    private var completed = false
    fun remainingMillis(): Long = if (completed) 0L else (2_000L - (now() - startedAt)).coerceIn(0L, 2_000L)
    fun complete() { completed = true }
}

@Composable internal fun launchDelayFinished(gate: ColdLaunchGate): Boolean {
    var finished by remember(gate) { mutableStateOf(gate.remainingMillis() == 0L) }
    LaunchedEffect(gate) {
        delay(gate.remainingMillis())
        gate.complete()
        finished = true
    }
    return finished
}
