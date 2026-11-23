package com.kannadadoc.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Holds the pre-blurred backdrop ImageBitmap and the workspace window bounds so cards
 * can sample their exact pixel-aligned blurred background slice during the Draw phase.
 */
@Stable
class FrostedBackdropState(
    val blurredBackdrop: ImageBitmap?
) {
    var workspaceBoundsInWindow by mutableStateOf(Rect.Zero)
}

@Composable
fun rememberFrostedBackdropState(
    context: Context,
    @DrawableRes drawableRes: Int,
    enabled: Boolean
): FrostedBackdropState {
    val blurredBitmap by produceState<ImageBitmap?>(initialValue = null, drawableRes, enabled) {
        value = if (!enabled) {
            null
        } else {
            withContext(Dispatchers.Default) {
                buildFrostedDarkBackdrop(context, drawableRes)
            }
        }
    }
    return remember(blurredBitmap) { FrostedBackdropState(blurredBitmap) }
}

/**
 * Renders a document card with a true frosted-glass blur effect in Dark Mode:
 * - Samples the exact pixel-aligned slice of the heavily blurred Home backdrop behind the card
 * - Applies a rich 60%-opaque (= 40% transparency) dark frosted tint so the card is grounded,
 *   distinct, and highly legible (never see-through)
 * - Adds a subtle glass top-to-bottom specular sheen and crisp hairline border
 * - In Light Mode, renders the clean warm parchment surface.
 */
@Composable
fun FrostedDocumentSurface(
    isDarkTheme: Boolean,
    backdropState: FrostedBackdropState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    var cardBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
    val lightBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
    val contentColor = MaterialTheme.colorScheme.onSurface
    val borderStroke = if (isDarkTheme) {
        BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
    } else {
        BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.30f))
    }

    val baseMod = modifier
        .onGloballyPositioned { coords ->
            if (isDarkTheme) {
                cardBoundsInWindow = coords.boundsInWindow()
            }
        }
        .clip(shape)
        .drawBehind {
            if (!isDarkTheme) {
                drawRect(color = lightBg)
                return@drawBehind
            }

            // 1. Draw exact ContentScale.Crop-aligned heavily blurred backdrop slice behind this card
            val bmp = backdropState.blurredBackdrop
            val wsBounds = backdropState.workspaceBoundsInWindow
            val cBounds = cardBoundsInWindow
            if (bmp != null && wsBounds.width > 1f && wsBounds.height > 1f && cBounds.width > 1f && cBounds.height > 1f) {
                val imgW = bmp.width
                val imgH = bmp.height
                val scale = maxOf(wsBounds.width / imgW.toFloat(), wsBounds.height / imgH.toFloat())
                val cropOffsetX = (imgW * scale - wsBounds.width) / 2f
                val cropOffsetY = (imgH * scale - wsBounds.height) / 2f

                val relLeft = (cBounds.left - wsBounds.left).coerceIn(0f, wsBounds.width)
                val relTop = (cBounds.top - wsBounds.top).coerceIn(0f, wsBounds.height)
                val relRight = (cBounds.right - wsBounds.left).coerceIn(relLeft, wsBounds.width)
                val relBottom = (cBounds.bottom - wsBounds.top).coerceIn(relTop, wsBounds.height)

                val srcLeft = ((relLeft + cropOffsetX) / scale).roundToInt().coerceIn(0, (imgW - 1).coerceAtLeast(0))
                val srcTop = ((relTop + cropOffsetY) / scale).roundToInt().coerceIn(0, (imgH - 1).coerceAtLeast(0))
                val srcRight = ((relRight + cropOffsetX) / scale).roundToInt().coerceIn(srcLeft + 1, imgW)
                val srcBottom = ((relBottom + cropOffsetY) / scale).roundToInt().coerceIn(srcTop + 1, imgH)

                drawImage(
                    image = bmp,
                    srcOffset = IntOffset(srcLeft, srcTop),
                    srcSize = IntSize((srcRight - srcLeft).coerceAtLeast(1), (srcBottom - srcTop).coerceAtLeast(1)),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Medium
                )
            }

            // 2. 60% opaque (= 40% transparency) dark frosted glass tint over the blurred backdrop
            drawRect(color = Color(0xFF141414).copy(alpha = 0.60f))

            // 3. Subtle frosted-glass top-to-bottom specular light reflection
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.09f),
                        Color.White.copy(alpha = 0.025f),
                        Color.Transparent
                    )
                )
            )
        }
        .border(borderStroke, shape)
        .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(modifier = baseMod, content = content)
    }
}

/**
 * Decodes the workspace backdrop to a compact downsampled bitmap, darkens it for OLED dark mode,
 * and applies a 3-pass separable box blur (approximating a lush ~44dp Gaussian frosted glass blur).
 */
private fun buildFrostedDarkBackdrop(context: Context, @DrawableRes resId: Int): ImageBitmap? {
    return runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, boundsOpts)
        val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight).coerceAtLeast(1)
        var sampleSize = 1
        while (maxDim / (sampleSize * 2) >= 180) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeResource(context.resources, resId, decodeOpts) ?: return null
        val mutable = if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
        }

        val w = mutable.width
        val h = mutable.height
        val pixels = IntArray(w * h)
        mutable.getPixels(pixels, 0, w, 0, 0, w, h)

        // Pre-darken tones to ~48% so the frosted dark-glass cards stay deep dark OLED-friendly
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (((c ushr 16) and 0xFF) * 48) / 100
            val g = (((c ushr 8) and 0xFF) * 48) / 100
            val b = ((c and 0xFF) * 48) / 100
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // 3-pass separable box blur (converges cleanly to a smooth Gaussian blur with radius ~10 on 180px)
        val tmp = IntArray(w * h)
        val radius = 10
        repeat(3) {
            boxBlurHorizontal(pixels, tmp, w, h, radius)
            boxBlurVertical(tmp, pixels, w, h, radius)
        }

        mutable.setPixels(pixels, 0, w, 0, 0, w, h)
        mutable.asImageBitmap()
    }.getOrNull()
}

private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
    val div = radius * 2 + 1
    for (y in 0 until h) {
        val row = y * w
        var rSum = 0
        var gSum = 0
        var bSum = 0
        for (i in -radius..radius) {
            val c = src[row + i.coerceIn(0, w - 1)]
            rSum += (c ushr 16) and 0xFF
            gSum += (c ushr 8) and 0xFF
            bSum += c and 0xFF
        }
        for (x in 0 until w) {
            dst[row + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
            val removeIdx = (x - radius).coerceIn(0, w - 1)
            val addIdx = (x + radius + 1).coerceIn(0, w - 1)
            val cRem = src[row + removeIdx]
            val cAdd = src[row + addIdx]
            rSum += ((cAdd ushr 16) and 0xFF) - ((cRem ushr 16) and 0xFF)
            gSum += ((cAdd ushr 8) and 0xFF) - ((cRem ushr 8) and 0xFF)
            bSum += (cAdd and 0xFF) - (cRem and 0xFF)
        }
    }
}

private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
    val div = radius * 2 + 1
    for (x in 0 until w) {
        var rSum = 0
        var gSum = 0
        var bSum = 0
        for (i in -radius..radius) {
            val c = src[i.coerceIn(0, h - 1) * w + x]
            rSum += (c ushr 16) and 0xFF
            gSum += (c ushr 8) and 0xFF
            bSum += c and 0xFF
        }
        for (y in 0 until h) {
            dst[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
            val removeIdx = (y - radius).coerceIn(0, h - 1)
            val addIdx = (y + radius + 1).coerceIn(0, h - 1)
            val cRem = src[removeIdx * w + x]
            val cAdd = src[addIdx * w + x]
            rSum += ((cAdd ushr 16) and 0xFF) - ((cRem ushr 16) and 0xFF)
            gSum += ((cAdd ushr 8) and 0xFF) - ((cRem ushr 8) and 0xFF)
            bSum += (cAdd and 0xFF) - (cRem and 0xFF)
        }
    }
}
