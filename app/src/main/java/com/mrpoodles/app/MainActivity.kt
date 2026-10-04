package com.mrpoodles.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Compose provides the timed illustration. Remove the native layer as soon as its frame is ready.
        splash.setOnExitAnimationListener { it.remove() }
        enableEdgeToEdge()
        setContent { PoodlesApp(viewModel()) }
    }
}
