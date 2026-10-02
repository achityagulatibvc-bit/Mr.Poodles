package com.mrpoodles.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable internal fun PhotoPreview(uri: String, rotated: Boolean) {
    val context = LocalContext.current
    val image by produceState<Bitmap?>(null, uri) {
        value = runCatching { LabelPhoto(context).preview(Uri.parse(uri)) }.getOrNull()
    }
    CozyCard(color = Lavender) {
        image?.let { bitmap -> Image(bitmap.asImageBitmap(), "Selected label photo",
            Modifier.fillMaxWidth().height(150.dp).graphicsLayer { rotationZ = if (rotated) 90f else 0f }, contentScale = ContentScale.Fit) }
            ?: Text("Choose the photo again if it is no longer available.")
    }
}
