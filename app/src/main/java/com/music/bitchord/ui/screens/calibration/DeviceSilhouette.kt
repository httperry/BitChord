/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.ui.screens.calibration

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.GLASS_EDGE_COLOR
import com.music.bitchord.ui.components.GLASS_EDGE_WIDTH
import com.music.bitchord.ui.components.LocalLiquidGlassEnabled
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.lightweightLiquidGlass

/**
 * Standardized 56x56dp placeholder vector container showing an illuminated IEM
 * or headphone silhouette.
 *
 * Strictly adheres to BitChord styling:
 * - Liquid Glass mode: [lightweightLiquidGlass] with specular highlight and glass edge.
 * - Material 3 fallback: [surfaceContainer] / [surfaceVariant] with OLED dark background.
 */
@Composable
fun DeviceSilhouette(
    formFactor: HeadphoneFormFactor,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    size: Dp = 56.dp,
) {
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val shape = RoundedCornerShape(14.dp)

    val fallbackColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }

    val glowColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        },
        animationSpec = tween(300),
        label = "glowColor",
    )

    val strokeColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        },
        animationSpec = tween(300),
        label = "strokeColor",
    )

    val accentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        },
        animationSpec = tween(300),
        label = "accentColor",
    )

    val containerModifier = if (isGlass) {
        modifier
            .size(size)
            .lightweightLiquidGlass(
                shape = shape,
                fallbackColor = fallbackColor,
            )
    } else {
        modifier
            .size(size)
            .clip(shape)
            .background(fallbackColor)
            .border(
                width = if (isSelected) 1.dp else 0.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = shape,
            )
    }

    Box(
        modifier = containerModifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size * 0.68f)) {
            // Illuminated backing glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowColor, Color.Transparent),
                    center = center,
                    radius = this.size.minDimension * 0.75f,
                ),
            )

            when (formFactor) {
                HeadphoneFormFactor.IEM -> drawIemSilhouette(
                    strokeColor = strokeColor,
                    accentColor = accentColor,
                    isSelected = isSelected,
                )
                HeadphoneFormFactor.OVER_EAR -> drawOverEarSilhouette(
                    strokeColor = strokeColor,
                    accentColor = accentColor,
                    isSelected = isSelected,
                )
                HeadphoneFormFactor.EARBUDS -> drawEarbudsSilhouette(
                    strokeColor = strokeColor,
                    accentColor = accentColor,
                    isSelected = isSelected,
                )
            }
        }
    }
}

private fun DrawScope.drawIemSilhouette(
    strokeColor: Color,
    accentColor: Color,
    isSelected: Boolean,
) {
    val w = size.width
    val h = size.height

    // Main ergonomic IEM body shell
    val bodyPath = Path().apply {
        moveTo(w * 0.30f, h * 0.18f)
        cubicTo(w * 0.65f, h * 0.12f, w * 0.88f, h * 0.35f, w * 0.85f, h * 0.60f)
        cubicTo(w * 0.82f, h * 0.78f, w * 0.65f, h * 0.90f, w * 0.45f, h * 0.88f)
        cubicTo(w * 0.32f, h * 0.86f, w * 0.22f, h * 0.75f, w * 0.20f, h * 0.62f)
        cubicTo(w * 0.18f, h * 0.45f, w * 0.15f, h * 0.26f, w * 0.30f, h * 0.18f)
        close()
    }

    // Nozzle / acoustic sound tube projecting out
    val nozzlePath = Path().apply {
        moveTo(w * 0.75f, h * 0.65f)
        lineTo(w * 0.95f, h * 0.78f)
        lineTo(w * 0.88f, h * 0.90f)
        lineTo(w * 0.68f, h * 0.75f)
        close()
    }

    // Subtle faceplate accent curve
    val faceplatePath = Path().apply {
        moveTo(w * 0.35f, h * 0.28f)
        cubicTo(w * 0.55f, h * 0.24f, w * 0.72f, h * 0.38f, w * 0.70f, h * 0.55f)
    }

    // Cable socket pin at the top
    drawRoundRect(
        color = accentColor,
        topLeft = Offset(w * 0.25f, h * 0.10f),
        size = Size(w * 0.18f, h * 0.08f),
        cornerRadius = CornerRadius(2f, 2f),
        style = Fill,
    )

    // Shell fill with subtle depth
    drawPath(
        path = bodyPath,
        color = strokeColor.copy(alpha = 0.12f),
        style = Fill,
    )

    // Shell outline
    drawPath(
        path = bodyPath,
        color = strokeColor,
        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // Nozzle tube
    drawPath(
        path = nozzlePath,
        color = strokeColor.copy(alpha = 0.20f),
        style = Fill,
    )
    drawPath(
        path = nozzlePath,
        color = strokeColor,
        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // Internal acoustic chamber accent
    drawPath(
        path = faceplatePath,
        color = accentColor,
        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawOverEarSilhouette(
    strokeColor: Color,
    accentColor: Color,
    isSelected: Boolean,
) {
    val w = size.width
    val h = size.height

    // Headband arc
    val headbandPath = Path().apply {
        moveTo(w * 0.16f, h * 0.55f)
        cubicTo(w * 0.16f, h * 0.10f, w * 0.84f, h * 0.10f, w * 0.84f, h * 0.55f)
    }

    drawPath(
        path = headbandPath,
        color = strokeColor,
        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
    )

    // Cushion underside arc
    val cushionPath = Path().apply {
        moveTo(w * 0.28f, h * 0.32f)
        cubicTo(w * 0.38f, h * 0.20f, w * 0.62f, h * 0.20f, w * 0.72f, h * 0.32f)
    }
    drawPath(
        path = cushionPath,
        color = accentColor,
        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
    )

    // Left earcup (oval)
    val leftCup = RoundRect(
        rect = Rect(w * 0.08f, h * 0.45f, w * 0.28f, h * 0.88f),
        cornerRadius = CornerRadius(w * 0.09f, h * 0.18f),
    )
    drawRoundRect(
        color = strokeColor.copy(alpha = 0.16f),
        topLeft = Offset(w * 0.08f, h * 0.45f),
        size = Size(w * 0.20f, h * 0.43f),
        cornerRadius = CornerRadius(w * 0.09f, h * 0.18f),
        style = Fill,
    )
    drawRoundRect(
        color = strokeColor,
        topLeft = Offset(w * 0.08f, h * 0.45f),
        size = Size(w * 0.20f, h * 0.43f),
        cornerRadius = CornerRadius(w * 0.09f, h * 0.18f),
        style = Stroke(width = 1.8.dp.toPx()),
    )

    // Right earcup (oval)
    drawRoundRect(
        color = strokeColor.copy(alpha = 0.16f),
        topLeft = Offset(w * 0.72f, h * 0.45f),
        size = Size(w * 0.20f, h * 0.43f),
        cornerRadius = CornerRadius(w * 0.09f, h * 0.18f),
        style = Fill,
    )
    drawRoundRect(
        color = strokeColor,
        topLeft = Offset(w * 0.72f, h * 0.45f),
        size = Size(w * 0.20f, h * 0.43f),
        cornerRadius = CornerRadius(w * 0.09f, h * 0.18f),
        style = Stroke(width = 1.8.dp.toPx()),
    )
}

private fun DrawScope.drawEarbudsSilhouette(
    strokeColor: Color,
    accentColor: Color,
    isSelected: Boolean,
) {
    val w = size.width
    val h = size.height

    // Earbud head (circular / oval bulb)
    drawCircle(
        color = strokeColor.copy(alpha = 0.16f),
        radius = w * 0.24f,
        center = Offset(w * 0.52f, h * 0.35f),
        style = Fill,
    )
    drawCircle(
        color = strokeColor,
        radius = w * 0.24f,
        center = Offset(w * 0.52f, h * 0.35f),
        style = Stroke(width = 1.8.dp.toPx()),
    )

    // Acoustic mesh grill dot in center
    drawCircle(
        color = accentColor,
        radius = w * 0.08f,
        center = Offset(w * 0.52f, h * 0.35f),
        style = Fill,
    )

    // Stem pointing downward
    val stemPath = Path().apply {
        moveTo(w * 0.46f, h * 0.56f)
        lineTo(w * 0.44f, h * 0.90f)
        cubicTo(w * 0.44f, h * 0.94f, w * 0.54f, h * 0.94f, w * 0.54f, h * 0.90f)
        lineTo(w * 0.56f, h * 0.56f)
        close()
    }

    drawPath(
        path = stemPath,
        color = strokeColor.copy(alpha = 0.14f),
        style = Fill,
    )
    drawPath(
        path = stemPath,
        color = strokeColor,
        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
