package com.phonedrop.v2.presentation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun PhoneDropLogoIcon(
    modifier: Modifier = Modifier.size(28.dp),
    accentColor: Color = Color(0xFFDFC49B),
    bgColor: Color = Color(0xFF151D19)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        drawRoundRect(
            color = bgColor,
            cornerRadius = CornerRadius(w * 0.24f, w * 0.24f)
        )
        drawRoundRect(
            color = accentColor.copy(alpha = 0.45f),
            cornerRadius = CornerRadius(w * 0.24f, w * 0.24f),
            style = Stroke(width = w * 0.045f)
        )

        // Phone outline
        drawRoundRect(
            color = accentColor,
            topLeft = Offset(w * 0.31f, h * 0.17f),
            size = Size(w * 0.38f, h * 0.55f),
            cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
            style = stroke
        )
        // Drop beam inside phone
        drawLine(accentColor, Offset(w * 0.50f, h * 0.33f), Offset(w * 0.50f, h * 0.56f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
        drawLine(accentColor, Offset(w * 0.41f, h * 0.48f), Offset(w * 0.50f, h * 0.57f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
        drawLine(accentColor, Offset(w * 0.59f, h * 0.48f), Offset(w * 0.50f, h * 0.57f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
        // Laptop base line
        drawLine(accentColor, Offset(w * 0.20f, h * 0.81f), Offset(w * 0.80f, h * 0.81f), strokeWidth = w * 0.065f, cap = StrokeCap.Round)
    }
}

@Composable
fun NavIconFile(color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.095f
        val p = Path().apply {
            moveTo(w * 0.15f, h * 0.22f)
            lineTo(w * 0.42f, h * 0.22f)
            lineTo(w * 0.50f, h * 0.32f)
            lineTo(w * 0.85f, h * 0.32f)
            lineTo(w * 0.85f, h * 0.80f)
            lineTo(w * 0.15f, h * 0.80f)
            close()
        }
        drawPath(p, color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun NavIconClipboard(color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.095f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.22f, h * 0.22f),
            size = Size(w * 0.56f, h * 0.64f),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.36f, h * 0.13f),
            size = Size(w * 0.28f, h * 0.14f),
            cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
            style = Stroke(width = sw)
        )
        drawLine(color, Offset(w * 0.35f, h * 0.46f), Offset(w * 0.65f, h * 0.46f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.35f, h * 0.63f), Offset(w * 0.58f, h * 0.63f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun NavIconRemote(color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.095f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.15f, h * 0.18f),
            size = Size(w * 0.70f, h * 0.64f),
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
            style = Stroke(width = sw)
        )
        drawLine(color, Offset(w * 0.15f, h * 0.62f), Offset(w * 0.85f, h * 0.62f), strokeWidth = sw * 0.85f)
        drawLine(color, Offset(w * 0.50f, h * 0.62f), Offset(w * 0.50f, h * 0.82f), strokeWidth = sw * 0.85f)
        drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.50f, h * 0.40f))
    }
}

@Composable
fun NavIconScreen(color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.095f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.14f, h * 0.20f),
            size = Size(w * 0.72f, h * 0.48f),
            cornerRadius = CornerRadius(w * 0.09f, w * 0.09f),
            style = Stroke(width = sw)
        )
        drawLine(color, Offset(w * 0.34f, h * 0.80f), Offset(w * 0.66f, h * 0.80f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.50f, h * 0.68f), Offset(w * 0.50f, h * 0.80f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun IconUsbCable(color: Color, modifier: Modifier = Modifier.size(15.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.12f
        drawLine(color, Offset(w * 0.14f, h * 0.50f), Offset(w * 0.80f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.36f, h * 0.50f), Offset(w * 0.58f, h * 0.24f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.44f, h * 0.50f), Offset(w * 0.64f, h * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
        drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.84f, h * 0.50f))
        drawCircle(color, radius = w * 0.08f, center = Offset(w * 0.62f, h * 0.22f))
    }
}

@Composable
fun IconWirelessWave(color: Color, modifier: Modifier = Modifier.size(15.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.12f
        drawArc(
            color = color,
            startAngle = -140f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(w * 0.10f, h * 0.22f),
            size = Size(w * 0.80f, h * 0.80f),
            style = Stroke(width = sw, cap = StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = -135f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.26f, h * 0.40f),
            size = Size(w * 0.48f, h * 0.48f),
            style = Stroke(width = sw, cap = StrokeCap.Round)
        )
        drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.50f, h * 0.78f))
    }
}

@Composable
fun IconSettingsSliders(color: Color, modifier: Modifier = Modifier.size(17.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.10f
        drawLine(color, Offset(w * 0.15f, h * 0.28f), Offset(w * 0.85f, h * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.15f, h * 0.50f), Offset(w * 0.85f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.15f, h * 0.72f), Offset(w * 0.85f, h * 0.72f), strokeWidth = sw, cap = StrokeCap.Round)
        drawCircle(color, radius = w * 0.10f, center = Offset(w * 0.35f, h * 0.28f))
        drawCircle(color, radius = w * 0.10f, center = Offset(w * 0.66f, h * 0.50f))
        drawCircle(color, radius = w * 0.10f, center = Offset(w * 0.42f, h * 0.72f))
    }
}

@Composable
fun IconUploadCloud(color: Color, modifier: Modifier = Modifier.size(26.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        drawLine(color, Offset(w * 0.50f, h * 0.66f), Offset(w * 0.50f, h * 0.20f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.32f, h * 0.38f), Offset(w * 0.50f, h * 0.20f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.68f, h * 0.38f), Offset(w * 0.50f, h * 0.20f), strokeWidth = sw, cap = StrokeCap.Round)
        val tray = Path().apply {
            moveTo(w * 0.20f, h * 0.64f)
            lineTo(w * 0.20f, h * 0.80f)
            lineTo(w * 0.80f, h * 0.80f)
            lineTo(w * 0.80f, h * 0.64f)
        }
        drawPath(tray, color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun IconCloseX(color: Color, modifier: Modifier = Modifier.size(14.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.14f
        drawLine(color, Offset(w * 0.24f, h * 0.24f), Offset(w * 0.76f, h * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.76f, h * 0.24f), Offset(w * 0.24f, h * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun IconChevronUp(color: Color, modifier: Modifier = Modifier.size(13.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.14f
        val p = Path().apply {
            moveTo(w * 0.20f, h * 0.66f)
            lineTo(w * 0.50f, h * 0.32f)
            lineTo(w * 0.80f, h * 0.66f)
        }
        drawPath(p, color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun IconChevronDown(color: Color, modifier: Modifier = Modifier.size(13.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.14f
        val p = Path().apply {
            moveTo(w * 0.20f, h * 0.34f)
            lineTo(w * 0.50f, h * 0.68f)
            lineTo(w * 0.80f, h * 0.34f)
        }
        drawPath(p, color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun IconBackspaceKey(color: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        val p = Path().apply {
            moveTo(w * 0.32f, h * 0.24f)
            lineTo(w * 0.86f, h * 0.24f)
            lineTo(w * 0.86f, h * 0.76f)
            lineTo(w * 0.32f, h * 0.76f)
            lineTo(w * 0.12f, h * 0.50f)
            close()
        }
        drawPath(p, color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.48f, h * 0.39f), Offset(w * 0.68f, h * 0.61f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.68f, h * 0.39f), Offset(w * 0.48f, h * 0.61f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun IconEnterKey(color: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        val p = Path().apply {
            moveTo(w * 0.78f, h * 0.26f)
            lineTo(w * 0.78f, h * 0.60f)
            lineTo(w * 0.24f, h * 0.60f)
        }
        drawPath(p, color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.38f, h * 0.44f), Offset(w * 0.22f, h * 0.60f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.38f, h * 0.76f), Offset(w * 0.22f, h * 0.60f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}
