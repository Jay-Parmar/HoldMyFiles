package dev.jay.holdmyfiles.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
internal fun BrandMark(
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(background, RoundedCornerShape(16.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val folder = Path().apply {
                moveTo(size.width * 0.16f, size.height * 0.30f)
                lineTo(size.width * 0.39f, size.height * 0.30f)
                lineTo(size.width * 0.48f, size.height * 0.40f)
                lineTo(size.width * 0.84f, size.height * 0.40f)
                lineTo(size.width * 0.84f, size.height * 0.78f)
                lineTo(size.width * 0.16f, size.height * 0.78f)
                close()
            }
            drawPath(folder, foreground)

            val left = Offset(size.width * 0.35f, size.height * 0.57f)
            val right = Offset(size.width * 0.66f, size.height * 0.57f)
            val lower = Offset(size.width * 0.50f, size.height * 0.69f)
            val lineWidth = size.minDimension * 0.035f
            drawLine(background, left, right, lineWidth, StrokeCap.Round)
            drawLine(background, left, lower, lineWidth, StrokeCap.Round)
            drawLine(background, right, lower, lineWidth, StrokeCap.Round)
            val nodeRadius = size.minDimension * 0.055f
            drawCircle(background, nodeRadius, left)
            drawCircle(background, nodeRadius, right)
            drawCircle(background, nodeRadius, lower)
        }
    }
}

@Composable
internal fun NetworkArtwork(
    color: Color,
    mutedColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.025f
        val outerRadius = size.minDimension * 0.42f
        val center = Offset(size.width * 0.55f, size.height * 0.52f)

        drawArc(
            color = mutedColor,
            startAngle = 205f,
            sweepAngle = 220f,
            useCenter = false,
            topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
            size = Size(outerRadius * 2, outerRadius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = 215f,
            sweepAngle = 200f,
            useCenter = false,
            topLeft = Offset(
                center.x - outerRadius * 0.66f,
                center.y - outerRadius * 0.66f,
            ),
            size = Size(outerRadius * 1.32f, outerRadius * 1.32f),
            style = Stroke(width = stroke * 1.2f, cap = StrokeCap.Round),
        )

        val points = listOf(
            Offset(size.width * 0.20f, size.height * 0.74f),
            Offset(size.width * 0.55f, size.height * 0.52f),
            Offset(size.width * 0.84f, size.height * 0.28f),
        )
        drawLine(mutedColor, points[0], points[1], stroke, StrokeCap.Round)
        drawLine(mutedColor, points[1], points[2], stroke, StrokeCap.Round)
        points.forEachIndexed { index, point ->
            drawCircle(
                color = if (index == 1) color else mutedColor,
                radius = size.minDimension * if (index == 1) 0.075f else 0.055f,
                center = point,
            )
        }
    }
}

@Composable
internal fun SecurityMark(
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(background, RoundedCornerShape(14.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val shield = Path().apply {
                moveTo(size.width * 0.50f, size.height * 0.16f)
                lineTo(size.width * 0.78f, size.height * 0.28f)
                lineTo(size.width * 0.74f, size.height * 0.62f)
                quadraticTo(
                    size.width * 0.70f,
                    size.height * 0.78f,
                    size.width * 0.50f,
                    size.height * 0.86f,
                )
                quadraticTo(
                    size.width * 0.30f,
                    size.height * 0.78f,
                    size.width * 0.26f,
                    size.height * 0.62f,
                )
                lineTo(size.width * 0.22f, size.height * 0.28f)
                close()
            }
            drawPath(shield, foreground, style = Stroke(width = size.minDimension * 0.06f))
            drawLine(
                color = foreground,
                start = Offset(size.width * 0.50f, size.height * 0.37f),
                end = Offset(size.width * 0.50f, size.height * 0.58f),
                strokeWidth = size.minDimension * 0.06f,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = foreground,
                radius = size.minDimension * 0.035f,
                center = Offset(size.width * 0.50f, size.height * 0.69f),
            )
        }
    }
}
