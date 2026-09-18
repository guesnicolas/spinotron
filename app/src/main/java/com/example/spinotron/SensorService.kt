package com.example.spinotron

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlin.math.PI
import kotlin.math.abs

/**
 * Suit l'azimut du téléphone via le capteur fusionné TYPE_ROTATION_VECTOR (gyroscope +
 * accéléromètre + magnétomètre, combinés par le firmware). Contrairement à un calcul basé
 * uniquement sur gravité + champ magnétique, ce capteur reste stable quand le champ magnétique
 * est perturbé (métro, structures métalliques...) : le gyroscope porte le signal à court terme
 * pendant que le magnétomètre ne sert qu'à corriger la dérive à long terme.
 */
class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var lastAngle: Double? = null

    private var runStartElapsedRealtime = 0L
    private var pausedElapsedMs = 0L

    override fun onCreate() {
        super.onCreate()
        // La variante à 3 arguments (avec le type de service) n'existe qu'à partir d'Android 10
        // (API 29). L'appeler sur un appareil plus ancien fait planter le service au démarrage.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor == null) {
            // Ce capteur virtuel a besoin d'un magnétomètre (et idéalement d'un gyroscope) ;
            // certains téléphones d'entrée de gamme n'embarquent qu'un accéléromètre, auquel
            // cas mesurer une rotation autour de l'axe vertical est physiquement impossible.
            SpinRepository.setSensorUnavailable()
            stopSelf()
            return
        }

        pausedElapsedMs = SpinRepository.state.value.elapsedMs
        runStartElapsedRealtime = SystemClock.elapsedRealtime()
        SpinRepository.setRunning(true)

        val samplingPeriodUs = 20_000 // ~50 Hz
        sensorManager.registerListener(this, rotationVectorSensor, samplingPeriodUs)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        SpinRepository.setRunning(false)
        lastAngle = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val currentAngle = orientation[0].toDouble() // azimut par rapport au Nord magnétique, en radians

        lastAngle?.let { previous ->
            var dtheta = currentAngle - previous
            while (dtheta > PI) dtheta -= 2 * PI
            while (dtheta < -PI) dtheta += 2 * PI

            // Écarte les sauts improbables (bruit/ré-étalonnage) : à 50 Hz, une vraie rotation
            // ne peut pas dépasser 90° entre deux échantillons sans que le téléphone tourne à
            // plus de 12 tours/seconde.
            if (abs(dtheta) < PI / 2) {
                SpinRepository.addRotation(dtheta, currentAngle)
            } else {
                SpinRepository.setCurrentAngle(currentAngle)
            }
        } ?: SpinRepository.setCurrentAngle(currentAngle)

        lastAngle = currentAngle

        val elapsed = pausedElapsedMs + (SystemClock.elapsedRealtime() - runStartElapsedRealtime)
        SpinRepository.tick(elapsed)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        // Les channels de notification n'existent qu'à partir d'Android 8 (API 26) ;
        // sur un appareil plus ancien, NotificationCompat.Builder ignore juste le channel id.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "Spinotron", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Spinotron")
            .setContentText("Comptage des rotations en cours…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "spinotron"
        const val NOTIFICATION_ID = 1
    }
}
