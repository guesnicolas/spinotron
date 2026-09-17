package com.example.spinotron

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
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
    val historySeconds = HISTORY_INTERVAL_MS / 1_000

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

        if (state.turnsHistory.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Tours / ${historySeconds}s", fontSize = 16.sp, color = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(8.dp))
            TurnsHistoryBars(state.turnsHistory)
        }
    }
}

@Composable
private fun TurnsHistoryBars(history: List<Double>) {
    val maxTurns = (history.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    Row(
        modifier = Modifier
            .height(100.dp)
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        history.forEach { turns ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight((turns / maxTurns).toFloat().coerceIn(0.01f, 1f))
                    .drawBehind { drawRect(color = Color(0xFF6200EE)) },
            )
        }
    }
}
