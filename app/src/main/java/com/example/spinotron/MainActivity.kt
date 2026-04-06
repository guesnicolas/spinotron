package com.example.spinotron

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlin.math.*

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accel = floatArrayOf(0f, 0f, 9.8f)
    private val accelBuf = Array(3) { ArrayDeque<Float>(10) }
    private var totalAngle = mutableStateOf(0.0)
    private var currentAngle = mutableStateOf(0.0)
    private var currentDtheta = mutableStateOf(0.0)
    private var lastAngle: Double? = null
    private var isRunning = mutableStateOf(false)
    private var elapsedMs = mutableStateOf(0L)
    private var timerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        registerSensors()

        setContent {
            val tours = SensorState._totalAngle.value / (2 * PI)
            val elapsed = elapsedMs.value
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
                                Text(if (isRunning.value) "Pause" else "Démarrer")
                            }
                            Button(onClick = {
                                SensorState.isRunning.value = false
                                timerJob?.cancel()
                                stopService(Intent(this@MainActivity, SensorService::class.java))
                                SensorState.totalAngle = 0.0
                                SensorState.startTimeMs.value = 0L
                                elapsedMs.value = 0L
                            }) {
                                Text("Reset")
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text("θ = ${"%.1f".format(Math.toDegrees(currentAngle.value))}°", fontSize = 20.sp)
                        Text("dθ = ${"%.2f".format(Math.toDegrees(currentDtheta.value))}°", fontSize = 20.sp)
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
            SensorState.startTimeMs.value = System.currentTimeMillis() - elapsedMs.value
            startForegroundService(Intent(this, SensorService::class.java))
            timerJob = CoroutineScope(Dispatchers.Main).launch {
                while (isActive) {
                    elapsedMs.value = System.currentTimeMillis() - SensorState.startTimeMs.value
                    delay(10)
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) {
                    if (accelBuf[i].size >= 30) accelBuf[i].removeFirst()
                    accelBuf[i].addLast(event.values[i])
                }
                accel = FloatArray(3) { i -> accelBuf[i].average().toFloat() }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (!isRunning.value) return

                val B = event.values.copyOf()
                val gNorm = sqrt(accel[0]*accel[0] + accel[1]*accel[1] + accel[2]*accel[2]) + 1e-9f
                val gHat = FloatArray(3) { i -> accel[i] / gNorm }
                val dot = B[0]*gHat[0] + B[1]*gHat[1] + B[2]*gHat[2]
                val Bperp = FloatArray(3) { i -> B[i] - dot*gHat[i] }
                val bNorm = sqrt(Bperp[0]*Bperp[0] + Bperp[1]*Bperp[1] + Bperp[2]*Bperp[2]) + 1e-9f
                val Bn = FloatArray(3) { i -> Bperp[i] / bNorm }
                val angle = atan2(Bn[2].toDouble(), Bn[0].toDouble())

                lastAngle?.let {
                    val dtheta = (angle - it + 3 * PI) % (2 * PI) - PI
                    if (abs(dtheta) > PI / 2) return  // ignore les sauts > 90°
                    SensorState.totalAngle += dtheta
                    currentDtheta.value = dtheta
                }
                lastAngle = angle
                currentAngle.value = angle
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, 50_000)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, 50_000)
        }
    }

    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this) }
    override fun onResume() { super.onResume(); registerSensors() }
}