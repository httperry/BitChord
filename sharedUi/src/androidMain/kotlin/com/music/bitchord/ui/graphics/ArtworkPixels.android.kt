package com.music.bitchord.ui.graphics

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.palette.graphics.Palette
import coil3.Image
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap

actual fun ImageRequest.Builder.forPixelAccess(): ImageRequest.Builder =
    allowHardware(false).crossfade(false)

actual fun Image.toImageBitmap(): ImageBitmap = toBitmap().asImageBitmap()

actual fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val software = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    val hardware = software.copy(Bitmap.Config.HARDWARE, false)
    if (hardware != null) {
        software.recycle()
        return hardware.asImageBitmap()
    }
    return software.asImageBitmap()
}

actual fun paletteSwatches(image: ImageBitmap, maxColors: Int, clearFilters: Boolean): List<Swatch> {
    val builder = Palette.from(image.asAndroidBitmap()).maximumColorCount(maxColors)
    if (clearFilters) builder.clearFilters()
    return builder.generate().swatches.map { Swatch(it.rgb, it.population) }
}
