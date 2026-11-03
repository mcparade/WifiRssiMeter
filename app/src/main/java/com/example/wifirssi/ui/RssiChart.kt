package com.example.wifirssi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wifirssi.WifiSample

private const val Y_MAX = -30
private const val Y_MIN = -100

/** Scrolling RSSI line chart over the last N samples, with a fixed -100..-30 dBm axis. */
@Composable
fun RssiChart(samples: List<WifiSample>, capacity: Int, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val labelSizePx = with(LocalDensity.current) { 11.sp.toPx() }

    Box(modifier.fillMaxWidth().height(200.dp)) {
        Canvas(Modifier.matchParentSize()) {
            val leftPad = labelSizePx * 3f
            val chartW = size.width - leftPad
            val chartH = size.height
            fun yFor(rssi: Int): Float {
                val clamped = rssi.coerceIn(Y_MIN, Y_MAX)
                return chartH * (Y_MAX - clamped) / (Y_MAX - Y_MIN).toFloat()
            }

            val paint = android.graphics.Paint().apply {
                color = labelColor
                textSize = labelSizePx
                isAntiAlias = true
            }
            for (db in Y_MAX downTo Y_MIN step 10) {
                val y = yFor(db)
                drawLine(
                    gridColor, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    db.toString(), 0f, (y + labelSizePx / 3f).coerceIn(labelSizePx, chartH), paint,
                )
            }

            if (samples.isEmpty()) return@Canvas
            val step = chartW / (capacity - 1).coerceAtLeast(1)
            // Right-align so the newest sample is always at the right edge.
            val startX = leftPad + chartW - step * (samples.size - 1)
            val path = Path()
            samples.forEachIndexed { i, s ->
                val x = startX + i * step
                val y = yFor(s.rssi)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path, lineColor,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            val last = samples.last()
            drawCircle(colorForRssi(last.rssi), radius = 5.dp.toPx(),
                center = Offset(startX + (samples.size - 1) * step, yFor(last.rssi)))
        }
    }
}

fun colorForRssi(rssi: Int): Color = when {
    rssi >= -55 -> Color(0xFF2E7D32)  // excellent
    rssi >= -67 -> Color(0xFF7CB342)  // good
    rssi >= -75 -> Color(0xFFF9A825)  // fair
    rssi >= -85 -> Color(0xFFEF6C00)  // weak
    else -> Color(0xFFC62828)         // unusable
}

fun labelForRssi(rssi: Int): String = when {
    rssi >= -55 -> "Excellent"
    rssi >= -67 -> "Good"
    rssi >= -75 -> "Fair"
    rssi >= -85 -> "Weak"
    else -> "Very weak"
}
