package com.mrpoodles.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** A lightweight illustrated setting. Decorations never intercept touches or sit behind body text. */
@Composable fun CozyScene(modifier: Modifier = Modifier, mood: String = "happy", hostel: Boolean = true, compact: Boolean = false) {
    val motion = LocalGentleMotion.current
    val cycle = if (motion) rememberInfiniteTransition(label = "Window light") else null
    val drift = cycle?.animateFloat(0f, 1f, infiniteRepeatable(tween(4200), RepeatMode.Reverse), label = "Floating petals")
    Box(modifier.background(Lavender.copy(alpha = .65f), RoundedCornerShape(30.dp)), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width; val h = size.height
            drawRoundRect(Cream, Offset(w * .12f, h * .12f), Size(w * .28f, h * .45f), CornerRadius(40f))
            drawLine(Color.White, Offset(w * .26f, h * .14f), Offset(w * .26f, h * .55f), 3.dp.toPx())
            drawLine(Color.White, Offset(w * .14f, h * .34f), Offset(w * .38f, h * .34f), 3.dp.toPx())
            drawOval(Blush, Offset(w * .08f, h * .76f), Size(w * .84f, h * .17f))
            drawRoundRect(Peach, Offset(w * .76f, h * .66f), Size(w * .12f, h * .15f), CornerRadius(8f))
            drawOval(Sage, Offset(w * .72f, h * .45f), Size(w * .12f, h * .19f))
            drawOval(Color(0xFFB9CFAF), Offset(w * .82f, h * .4f), Size(w * .1f, h * .24f))
            drawLine(Color(0xFF8B9F80), Offset(w * .82f, h * .67f), Offset(w * .82f, h * .5f), 2.dp.toPx())
            listOf(.48f to .18f, .68f to .28f, .9f to .2f).forEach { (x, y) ->
                val dy = (drift?.value ?: 0f) * 6.dp.toPx()
                drawLine(Color.White, Offset(w * x - 4.dp.toPx(), h * y + dy), Offset(w * x + 4.dp.toPx(), h * y + dy), 2.dp.toPx())
                drawLine(Color.White, Offset(w * x, h * y - 4.dp.toPx() + dy), Offset(w * x, h * y + 4.dp.toPx() + dy), 2.dp.toPx())
            }
        }
        Poodles(Modifier.size(if (compact) 100.dp else 168.dp).padding(bottom = 5.dp), mood, hostel)
    }
}

@Composable fun CompanionMascot(modifier: Modifier = Modifier, mood: String, hostel: Boolean = true) {
    var touched by remember { mutableStateOf(false) }
    val playful = mood !in listOf("concerned", "thinking")
    LaunchedEffect(touched) { if (touched) { delay(1600); touched = false } }
    val rotation by animateFloatAsState(if (touched && playful && LocalGentleMotion.current) 7f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "Penguin greeting")
    Poodles(modifier.graphicsLayer { rotationZ = rotation }.clickable(enabled = playful, onClickLabel = "Greet Poodles") { touched = true },
        if (touched && playful) "encouraging" else mood, hostel)
}

@Composable fun CozyAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && LocalGentleMotion.current) .96f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "Soft press")
    Button(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }.heightIn(min = 48.dp),
        enabled = enabled, interactionSource = interaction, shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) { Text(label) }
}

@Composable fun GiftIllustration(gift: String, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = if (gift == "rose") "A little rose from Poodles" else "An imaginary chocolate from Poodles" }) {
        val u = size.minDimension / 100f
        if (gift == "rose") {
            drawLine(Color(0xFF76976A), Offset(50*u, 40*u), Offset(45*u, 92*u), 4*u)
            drawOval(Sage, Offset(49*u, 56*u), Size(26*u, 13*u))
            listOf(30f to 15f, 48f to 18f, 22f to 30f, 44f to 34f).forEach { (x,y) -> drawCircle(Color(0xFFEFA9BD), 17*u, Offset(x*u+10*u,y*u+10*u)) }
            drawCircle(Rose.copy(alpha = .7f), 13*u, Offset(46*u, 36*u))
            drawArc(Blush, 20f, 265f, false, Offset(38*u,28*u), Size(16*u,16*u), style = Stroke(2*u))
        } else {
            drawRoundRect(Color(0xFF715044), Offset(19*u, 15*u), Size(62*u, 70*u), CornerRadius(7*u))
            repeat(2) { x -> repeat(2) { y -> drawRoundRect(Color(0xFF94705D), Offset((24+x*28)*u,(20+y*24)*u), Size(24*u,20*u), CornerRadius(3*u)) } }
            drawRoundRect(Blush, Offset(15*u, 53*u), Size(70*u, 35*u), CornerRadius(5*u))
            val heart = Path().apply { moveTo(50*u, 79*u); cubicTo(22*u,60*u, 43*u,54*u, 50*u,64*u); cubicTo(57*u,54*u,78*u,60*u,50*u,79*u) }
            drawPath(heart, Rose)
        }
    }
}

@Composable fun SplashScreen() {
    Surface(color = Paper, modifier = Modifier.fillMaxSize().testTag("launch_splash")) {
        Column(Modifier.padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CozyScene(Modifier.fillMaxWidth().height(220.dp), "sleepy")
            Spacer(Modifier.height(26.dp))
            Text("Mr. Poodles", style = MaterialTheme.typography.headlineLarge)
            Text("a softer little place", Modifier.padding(top = 8.dp), color = Rose, letterSpacing = 1.sp)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Rose)
        }
    }
}
