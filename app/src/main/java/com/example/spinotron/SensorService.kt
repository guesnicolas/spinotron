package com.example.spinotron

import android.app.*
import android.content.Intent
import android.hardware.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.math.*
import android.content.pm.ServiceInfo
class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accel = floatArrayOf(0f, 0f, 9.8f)
    private val accelBuf = Array(3) { ArrayDeque<Float>(10) }
    private var lastAngle: Double? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, 50_000)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, 50_000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) {
                    if (accelBuf[i].size >= 10) accelBuf[i].removeFirst()
                    accelBuf[i].addLast(event.values[i])
                }
                accel = FloatArray(3) { i -> accelBuf[i].average().toFloat() }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
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
                    SensorState.totalAngle += dtheta
                }
                lastAngle = angle
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "spinotron"
        val chan = NotificationChannel(channelId, "Spinotron", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Spinotron")
            .setContentText("Enregistrement en cours…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }
}