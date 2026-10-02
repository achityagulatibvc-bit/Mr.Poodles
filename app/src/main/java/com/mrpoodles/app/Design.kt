package com.mrpoodles.app

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.drawscope.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Rose = Color(0xFFA4486C)
val Plum = Color(0xFF523C50)
val Blush = Color(0xFFF9DDE7)
val Paper = Color(0xFFFFF9F3)
val Sage = Color(0xFFE1EDDC)
val Cream = Color(0xFFFFEFCB)
val Lavender = Color(0xFFECE3F6)
val Peach = Color(0xFFFFE3D6)
val LocalGentleMotion = staticCompositionLocalOf { true }

@Composable fun PoodlesTheme(motion: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var systemMotion by remember { mutableStateOf(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            systemMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Rose, onPrimary = Color.White, primaryContainer = Blush,
            onPrimaryContainer = Plum, secondary = Color(0xFF66745D), secondaryContainer = Sage,
            background = Paper, surface = Color(0xFFFFFDFB), onSurface = Plum, onBackground = Plum,
            surfaceVariant = Color(0xFFF4E9EB), onSurfaceVariant = Color(0xFF705E68), outline = Color(0xFFB39AA5),
            error = Color(0xFF9A3434)),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp)),
        shapes = Shapes(small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(22.dp), large = RoundedCornerShape(30.dp)),
        content = { CompositionLocalProvider(LocalGentleMotion provides (motion && systemMotion && resumed), content = content) }
    )
}

/** Original vector artwork inspired by the user's description of their plush toy. */
@Composable fun Poodles(modifier: Modifier = Modifier, mood: String = "happy", hostel: Boolean = true) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val motion = active && LocalGentleMotion.current && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    val cycle = if (motion) rememberInfiniteTransition(label = "Poodles' gentle breathing") else null
    val breath = cycle?.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Breathing")
    val blink = cycle?.animateFloat(1f, 1f, infiniteRepeatable(keyframes { durationMillis = 5200; 1f at 0; 1f at 4700; .08f at 4780; 1f at 4900 }), label = "Blink")
    val tilt by animateFloatAsState(when (mood) { "thinking", "curious", "listening" -> -5f; "comfort" -> 4f; else -> 0f }, tween(if (motion) 360 else 0), label = "Head tilt")
    Canvas(modifier.graphicsLayer {
        translationY = -(breath?.value ?: 0f) * 3.dp.toPx()
        rotationZ = tilt + (if (mood == "encouraging") (breath?.value ?: 0f) * 2f else 0f)
    }.semantics { contentDescription = "Mr. Poodles, a $mood penguin" }) {
        val scale = size.minDimension / 200f
        fun point(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun oval(color: Color, x: Float, y: Float, w: Float, h: Float) = drawOval(color, point(x, y), Size(w * scale, h * scale))
        val dark = Color(0xFF38363E)
        oval(Color(0xFFE8C7D0), 27f, 173f, 147f, 14f)
        oval(Color(0xFFF3BD57), 44f, 166f, 40f, 18f)
        oval(Color(0xFFF3BD57), 117f, 166f, 40f, 18f)
        oval(dark, 16f, if (mood == "encouraging" || mood == "happy") 73f else 88f, 40f, 65f)
        oval(dark, 147f, if (mood == "comfort") 100f else 87f, 38f, 65f)
        oval(dark, 33f, 26f, 137f, 151f)
        oval(Color(0xFFFFF8EC), 48f, 63f, 108f, 105f)
        oval(Color(0xFFFFF8EC), 52f, 48f, 50f, 56f)
        oval(Color(0xFFFFF8EC), 100f, 48f, 50f, 56f)
        oval(Color(0xFFF3B8C6), 54f, 94f, 19f, 9f)
        oval(Color(0xFFF3B8C6), 132f, 94f, 19f, 9f)
        scale(1f, blink?.value ?: 1f, point(102f, 81f)) {
        if (mood in listOf("thinking", "curious", "concerned", "listening")) {
            oval(dark, 73f, 77f, 8f, 10f); oval(dark, 125f, 77f, 8f, 10f)
            oval(Color.White, 75f, 78f, 2.5f, 3f); oval(Color.White, 127f, 78f, 2.5f, 3f)
            if (mood == "concerned") { drawLine(dark, point(72f, 70f), point(83f, 74f), 3f * scale); drawLine(dark, point(124f, 74f), point(135f, 70f), 3f * scale) }
        } else if (mood == "sleepy") {
            drawLine(dark, point(69f, 83f), point(84f, 85f), 3f * scale, StrokeCap.Round)
            drawLine(dark, point(123f, 85f), point(138f, 83f), 3f * scale, StrokeCap.Round)
        } else {
            drawArc(dark, 10f, 160f, false, point(66f, 71f), Size(21f * scale, 16f * scale), style = Stroke(3.5f * scale, cap = StrokeCap.Round))
            drawArc(dark, 10f, 160f, false, point(118f, 71f), Size(21f * scale, 16f * scale), style = Stroke(3.5f * scale, cap = StrokeCap.Round))
        }
        }
        val beak = Path().apply { moveTo(92f * scale, 92f * scale); quadraticTo(103f * scale, 84f * scale, 114f * scale, 92f * scale); quadraticTo(103f * scale, 111f * scale, 92f * scale, 92f * scale) }
        drawPath(beak, Color(0xFFF0B647))
        // A tiny bow is part of Poodles' original wardrobe, not a borrowed game asset.
        drawPath(Path().apply { moveTo(100f * scale, 116f * scale); lineTo(85f * scale, 108f * scale); lineTo(86f * scale, 124f * scale); close() }, Rose.copy(alpha = .65f))
        drawPath(Path().apply { moveTo(100f * scale, 116f * scale); lineTo(116f * scale, 108f * scale); lineTo(115f * scale, 124f * scale); close() }, Rose.copy(alpha = .65f))
        oval(Rose, 96f, 112f, 8f, 8f)
        if (hostel) {
            drawLine(Color(0xFFB85F7D), point(146f, 109f), point(129f, 157f), 8f * scale, StrokeCap.Round)
            oval(Color(0xFFCF86A0), 145f, 121f, 22f, 35f)
        } else {
            drawRoundRect(Color(0xFFE8C9D4), point(71f, 126f), Size(62f * scale, 37f * scale), androidx.compose.ui.geometry.CornerRadius(8f * scale))
            drawLine(Color(0xFFB85F7D), point(79f, 128f), point(74f, 109f), 4f * scale)
            drawLine(Color(0xFFB85F7D), point(125f, 128f), point(131f, 109f), 4f * scale)
        }
        oval(Color.White.copy(alpha = .12f), 44f, 35f, 23f, 10f)
    }
}

@Composable fun CozyCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
    val motion = LocalGentleMotion.current
    Surface(modifier.fillMaxWidth().animateContentSize(tween(if (motion) 220 else 0)), color = color, shape = RoundedCornerShape(26.dp), tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Rose.copy(alpha = .09f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
