package com.example.spinotron

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlin.math.*

class MainActivity : ComponentActivity() {

    private var timerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val tours = SensorState.totalAngle.value / (2 * PI)
            val elapsed = SensorState.elapsedMs.value
            val mm = (elapsed / 60000) % 60
            val ss = (elapsed / 1000) % 60
            val cs = (elapsed / 10) % 100

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Tours", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("%.2f".format(tours), fontSize = 80.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("%02d:%02d.%02d".format(mm, ss, cs), fontSize = 36.sp)
                        Spacer(modifier = Modifier.height(40.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(onClick = { toggleRunning() }) {
                                Text(if (SensorState.isRunning.value) "Pause" else "Démarrer")
                            }
                            Button(onClick = {
                                SensorState.isRunning.value = false
                                timerJob?.cancel()
                                stopService(Intent(this@MainActivity, SensorService::class.java))
                                SensorState.totalAngle.value = 0.0
                                SensorState.startTimeMs.value = 0L
                                SensorState.elapsedMs.value = 0L
                                SensorState.turnsHistory.clear()
                            }) {
                                Text("Reset")
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text("θ = ${"%.1f".format(Math.toDegrees(SensorState.currentAngle.value))}°", fontSize = 20.sp)
                        Text("dθ = ${"%.2f".format(Math.toDegrees(SensorState.currentDtheta.value))}°", fontSize = 20.sp)
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Petite visualisation de l'historique (Graphique en barres simple)
                        if (SensorState.turnsHistory.isNotEmpty()) {
                            Text("Tours par minute", fontSize = 16.sp, color = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .height(100.dp)
                                    .fillMaxWidth()
                                    .padding(horizontal = 32.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                val maxTurns = (SensorState.turnsHistory.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
                                SensorState.turnsHistory.forEach { turns ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight((turns / maxTurns).toFloat().coerceIn(0.01f, 1f))
                                            .drawBehind {
                                                drawRect(color = Color(0xFF6200EE))
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun toggleRunning() {
        if (SensorState.isRunning.value) {
            SensorState.isRunning.value = false
            stopService(Intent(this, SensorService::class.java))
            timerJob?.cancel()
        } else {
            SensorState.isRunning.value = true
            SensorState.startTimeMs.value = System.currentTimeMillis() - SensorState.elapsedMs.value
            startForegroundService(Intent(this, SensorService::class.java))
            timerJob = CoroutineScope(Dispatchers.Main).launch {
                while (isActive) {
                    SensorState.elapsedMs.value = System.currentTimeMillis() - SensorState.startTimeMs.value
                    delay(10)
                }
            }
        }
    }
}