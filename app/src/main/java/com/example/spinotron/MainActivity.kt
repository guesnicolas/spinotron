package com.example.spinotron

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.PI

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpinScreen(
                        onToggleRunning = ::toggleRunning,
                        onReset = ::reset,
                    )
                }
            }
        }
    }

    private fun toggleRunning() {
        val running = SpinRepository.state.value.isRunning
        if (running) {
            stopService(Intent(this, SensorService::class.java))
        } else {
            startForegroundService(Intent(this, SensorService::class.java))
        }
    }

    private fun reset() {
        stopService(Intent(this, SensorService::class.java))
        SpinRepository.reset()
    }
}

@Composable
private fun SpinScreen(onToggleRunning: () -> Unit, onReset: () -> Unit) {
    val state by SpinRepository.state.collectAsStateWithLifecycle()

    val tours = state.totalAngleRad / (2 * PI)
    val mm = (state.elapsedMs / 60_000) % 60
    val ss = (state.elapsedMs / 1_000) % 60
    val cs = (state.elapsedMs / 10) % 100

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Tours", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("%.2f".format(tours), fontSize = 80.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text("%02d:%02d.%02d".format(mm, ss, cs), fontSize = 36.sp)
        Spacer(modifier = Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onToggleRunning) {
                Text(if (state.isRunning) "Pause" else "Démarrer")
            }
            Button(onClick = onReset) {
                Text("Reset")
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text("θ = ${"%.1f".format(Math.toDegrees(state.currentAngleRad))}°", fontSize = 20.sp)
        Text("dθ = ${"%.2f".format(Math.toDegrees(state.currentDeltaRad))}°", fontSize = 20.sp)

        if (state.trend.size >= 2) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Tours cumulés dans le temps",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TrendChart(state.trend)
        }
    }
}

private fun formatMmSs(ms: Long): String {
    val mm = (ms / 60_000) % 60
    val ss = (ms / 1_000) % 60
    return "%02d:%02d".format(mm, ss)
}

@Composable
private fun TrendChart(trend: List<TrendPoint>) {
    val minTurns = trend.minOf { it.totalTurns }
    val maxTurns = trend.maxOf { it.totalTurns }
    // Évite un graphe plat si le nombre de tours n'a presque pas bougé.
    val range = (maxTurns - minTurns).coerceAtLeast(0.05)
    val minTime = trend.first().elapsedMs
    val maxTime = trend.last().elapsedMs.coerceAtLeast(minTime + 1L)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text("%.2f tours".format(maxTurns), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)

        val lineColor = MaterialTheme.colorScheme.primary
        val zeroLineColor = MaterialTheme.colorScheme.outlineVariant
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) {
            val w = size.width
            val h = size.height

            fun xOf(t: Long) = (t - minTime).toFloat() / (maxTime - minTime).toFloat() * w
            fun yOf(turns: Double) = h - ((turns - minTurns) / range).toFloat() * h

            // Ligne de repère à 0 tour, si elle est visible dans la plage affichée.
            if (minTurns <= 0.0 && maxTurns >= 0.0) {
                drawLine(
                    color = zeroLineColor,
                    start = Offset(0f, yOf(0.0)),
                    end = Offset(w, yOf(0.0)),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }

            for (i in 0 until trend.size - 1) {
                val a = trend[i]
                val b = trend[i + 1]
                drawLine(
                    color = lineColor,
                    start = Offset(xOf(a.elapsedMs), yOf(a.totalTurns)),
                    end = Offset(xOf(b.elapsedMs), yOf(b.totalTurns)),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        Text("%.2f tours".format(minTurns), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatMmSs(minTime), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            Text(formatMmSs(maxTime), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}
