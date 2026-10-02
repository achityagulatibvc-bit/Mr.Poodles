package com.mrpoodles.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Prepare a bounded photo for the label service, stripping original location metadata. */
class LabelPhoto(private val context: Context) {
    companion object { val languages = listOf("English", "French", "German", "Spanish", "Italian") }
    suspend fun preview(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val ratio = minOf(1.0, 400.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
        }
    }
    suspend fun encode(uri: Uri, rotate: Boolean = false): String = withContext(Dispatchers.IO) {
        var bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val ratio = minOf(1.0, 1280.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
        }
        try {
            if (rotate) {
                val source = bitmap
                bitmap = Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(90f) }, true)
                if (source !== bitmap) source.recycle()
            }
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
                output.toByteArray()
            }
            check(bytes.size <= 550000) { "This photo is too large. Try a closer crop of the label." }
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }
}
