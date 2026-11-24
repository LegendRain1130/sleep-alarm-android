package com.example.sleepalarm

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

@Composable
internal fun NightEmblem(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension
        val crescent = Path().apply {
            moveTo(0.60f * unit, 0.14f * unit)
            cubicTo(0.34f * unit, 0.29f * unit, 0.40f * unit, 0.69f * unit, 0.79f * unit, 0.67f * unit)
            cubicTo(0.66f * unit, 0.91f * unit, 0.29f * unit, 0.94f * unit, 0.15f * unit, 0.68f * unit)
            cubicTo(0.02f * unit, 0.41f * unit, 0.24f * unit, 0.12f * unit, 0.60f * unit, 0.14f * unit)
            close()
        }
        drawPath(crescent, color)
        drawCircle(color, radius = 0.06f * unit, center = Offset(0.79f * unit, 0.29f * unit))
        drawCircle(color, radius = 0.032f * unit, center = Offset(0.66f * unit, 0.45f * unit))
    }
}
