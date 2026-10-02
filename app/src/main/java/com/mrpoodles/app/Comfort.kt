package com.mrpoodles.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class PoodlesSounds(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private var loaded = false
    private val chime: Int
    private var lastPlay = 0L
    var enabled = false
    init {
        pool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
        chime = pool.load(context, R.raw.poodles_chime, 1)
    }
    fun play() {
        if (!enabled || !loaded || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.isMusicActive) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlay < 600) return
        lastPlay = now
        pool.play(chime, .16f, .16f, 1, 0, 1f)
    }
    fun close() = pool.release()
}

@Composable fun rememberPoodlesSounds(enabled: Boolean): PoodlesSounds {
    val context = LocalContext.current
    val sounds = remember { PoodlesSounds(context) }
    SideEffect { sounds.enabled = enabled }
    DisposableEffect(sounds) { onDispose { sounds.close() } }
    return sounds
}

private val notes = listOf(
    "One small thing is enough for now.",
    "Rest belongs in the plan too.",
    "You can begin again without being hard on yourself.",
    "A little water, a little stretch, a little kindness.",
    "You don't have to earn a gentle day.",
    "Some days, showing up looks like taking a break.",
    "Let's make room for something you enjoy.",
    "Your pace is still a pace.",
    "A messy day doesn't undo your care for yourself.",
    "Small comforts count. So do you."
)

@Composable fun PoodlesNote() {
    var index by rememberSaveable { mutableIntStateOf(java.time.LocalDate.now().dayOfYear % notes.size) }
    val motion = LocalGentleMotion.current
    CozyCard(color = Cream) {
        Text("A little note from Poodles", style = MaterialTheme.typography.labelLarge, color = Rose)
        AnimatedContent(index, transitionSpec = { fadeIn(tween(if (motion) 180 else 0)) togetherWith fadeOut(tween(if (motion) 120 else 0)) }, label = "A fresh little note") { item ->
            Text("“${notes[item]}”", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 4.dp))
        }
        TextButton(onClick = { index = (index + 1) % notes.size }) { Text("Another little note") }
    }
}
