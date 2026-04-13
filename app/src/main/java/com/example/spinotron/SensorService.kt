package com.example.spinotron

import android.app.*
import android.content.Intent
import android.hardware.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.math.*
import android.content.pm.ServiceInfo

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(v: Vec3) = Vec3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Vec3) = Vec3(x - v.x, y - v.y, z - v.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(v: Vec3) = x * v.x + y * v.y + z * v.z
    infix fun cross(v: Vec3) = Vec3(
        y * v.z - z * v.y,
        z * v.x - x * v.z,
        x * v.y - y * v.x
    )
    fun norm() = sqrt(x * x + y * y + z * z)
    fun normalize() = this * (1.0 / (norm() + 1e-9))
}

class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var gravity = Vec3(0.0, 0.0, 9.8)
    private var lastAngle: Double? = null
    
    private var lastHistoryUpdateMs = 0L
    private var angleAtLastMinute = 0.0

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        
        // On utilise TYPE_GRAVITY au lieu de ACCELEROMETER pour filtrer les mouvements brusques
        val samplingPeriodUs = 20_000 // 50Hz
        
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(this, it, samplingPeriodUs)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, samplingPeriodUs)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravity = Vec3(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (!SensorState.isRunning.value) {
                    lastAngle = null
                    return
                }

                val m = Vec3(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
                
                // --- CALCUL DE L'ORIENTATION ROBUSTE ---
                // 1. Le vecteur "Bas" (déjà donné par gravity)
                val down = gravity.normalize()
                
                // 2. Le vecteur "Est" (perpendiculaire au plan défini par la gravité et le champ magnétique)
                val east = (m cross down).normalize()
                
                // 3. Le vecteur "Nord" (dans le plan horizontal, pointant vers le pôle magnétique)
                val north = down cross east
                
                // L'angle du téléphone (Azimut) est l'angle de son axe Y (ou X) dans la base (Nord, Est)
                // Ici on calcule l'angle absolu du téléphone par rapport au Nord magnétique
                // On utilise atan2(composante_Est, composante_Nord)
                val currentAngle = atan2(east.x, north.x) 

                lastAngle?.let {
                    var dtheta = currentAngle - it
                    
                    // Gestion du passage de -PI à +PI
                    while (dtheta > PI) dtheta -= 2 * PI
                    while (dtheta < -PI) dtheta += 2 * PI
                    
                    // Filtrage des petits bruits magnétiques
                    if (abs(dtheta) < PI / 2) {
                        SensorState.totalAngle.value += dtheta
                        SensorState.currentDtheta.value = dtheta
                    }
                }
                
                lastAngle = currentAngle
                SensorState.currentAngle.value = currentAngle
                
                // --- MISE À JOUR DE L'HISTORIQUE TOUTES LES 10 SECONDES ---
                val now = System.currentTimeMillis()
                if (lastHistoryUpdateMs == 0L) {
                    lastHistoryUpdateMs = now
                    angleAtLastMinute = SensorState.totalAngle.value
                } else if (now - lastHistoryUpdateMs >= 10_000) {
                    val turnsInInterval = (SensorState.totalAngle.value - angleAtLastMinute) / (2 * PI)
                    SensorState.turnsHistory.add(turnsInInterval)
                    angleAtLastMinute = SensorState.totalAngle.value
                    lastHistoryUpdateMs = now
                }
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
            .setContentText("Calcul des rotations en cours…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }
}