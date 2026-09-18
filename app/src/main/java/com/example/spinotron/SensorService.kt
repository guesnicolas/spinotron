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
import kotlin.math.abs

/**
 * Suit la rotation du téléphone via le capteur fusionné TYPE_ROTATION_VECTOR (gyroscope +
 * accéléromètre + magnétomètre, combinés par le firmware). Contrairement à un calcul basé
 * uniquement sur gravité + champ magnétique, ce capteur reste stable quand le champ magnétique
 * est perturbé (métro, structures métalliques...) : le gyroscope porte le signal à court terme
 * pendant que le magnétomètre ne sert qu'à corriger la dérive à long terme.
 *
 * Le comptage n'utilise PAS l'azimut renvoyé par SensorManager.getOrientation() : cette
 * décomposition en angles d'Euler (azimuth/pitch/roll) a un point de singularité ("gimbal
 * lock") quand le téléphone est proche de la verticale (pitch ≈ 90°, typiquement en poche),
 * où de petits mouvements réels produisent des sauts d'azimut énormes. On calcule à la place
 * la rotation incrémentale directement entre deux matrices de rotation successives
 * (ΔR = Rₜ₋₁ᵀ · Rₜ, repère du téléphone), puis on la projette sur l'axe vertical du monde —
 * cet axe est directement lisible dans R (sa 3e ligne), pas besoin d'un capteur gravité séparé.
 * Cette projection n'a pas de singularité liée à l'inclinaison du téléphone.
 */
class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    private val currentRotationMatrix = FloatArray(9)
    private var previousRotationMatrix: FloatArray? = null

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
        previousRotationMatrix = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(currentRotationMatrix, event.values)

        previousRotationMatrix?.let { prev ->
            val dtheta = incrementalYawRad(prev, currentRotationMatrix)

            // Écarte les sauts improbables (bruit/ré-étalonnage) : à 50 Hz, une vraie rotation
            // ne peut pas dépasser 90° entre deux échantillons sans que le téléphone tourne à
            // plus de 12 tours/seconde.
            if (abs(dtheta) < Math.PI / 2) {
                SpinRepository.addRotation(dtheta)
            }
        }

        previousRotationMatrix = currentRotationMatrix.copyOf()

        val elapsed = pausedElapsedMs + (SystemClock.elapsedRealtime() - runStartElapsedRealtime)
        SpinRepository.tick(elapsed)
    }

    /**
     * Rotation autour de l'axe vertical du monde entre deux orientations successives du
     * téléphone, en radians. Repose uniquement sur les matrices de rotation (élément de
     * SO(3)) : pas de décomposition en angles d'Euler, donc pas de gimbal lock.
     */
    private fun incrementalYawRad(prev: FloatArray, curr: FloatArray): Double {
        // ΔR (repère téléphone) = prevᵀ · curr. Pour un petit pas de temps, ΔR ≈ I + [ω]×,
        // donc sa partie antisymétrique donne directement le vecteur rotation incrémental
        // exprimé dans le repère du téléphone — l'équivalent de ce que mesurerait un
        // gyroscope physique intégré sur cet intervalle, mais tiré de l'orientation fusionnée.
        fun dR(i: Int, j: Int): Float {
            var sum = 0f
            for (k in 0 until 3) sum += prev[k * 3 + i] * curr[k * 3 + j]
            return sum
        }

        val rx = (dR(2, 1) - dR(1, 2)) / 2f
        val ry = (dR(0, 2) - dR(2, 0)) / 2f
        val rz = (dR(1, 0) - dR(0, 1)) / 2f

        // Axe vertical du monde (Z, "haut") exprimé dans le repère du téléphone : c'est la
        // 3e ligne de la matrice de rotation, disponible directement, sans capteur gravité.
        val upX = curr[6]
        val upY = curr[7]
        val upZ = curr[8]

        return (rx * upX + ry * upY + rz * upZ).toDouble()
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
