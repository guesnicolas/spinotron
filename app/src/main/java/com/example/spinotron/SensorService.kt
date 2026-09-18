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
import kotlin.math.atan2

/**
 * Suit la rotation du téléphone via le capteur fusionné TYPE_ROTATION_VECTOR (gyroscope +
 * accéléromètre + magnétomètre, combinés par le firmware). Contrairement à un calcul basé
 * uniquement sur gravité + champ magnétique, ce capteur reste stable quand le champ magnétique
 * est perturbé (métro, structures métalliques...) : le gyroscope porte le signal à court terme
 * pendant que le magnétomètre ne sert qu'à corriger la dérive à long terme.
 *
 * Le nombre de tours est défini comme la classe d'homotopie de l'orientation relative au
 * départ, dans le sous-ensemble de SO(3) où la personne n'est pas tête en bas. Soit
 * M = R·R₀ᵀ la rotation subie depuis l'appui sur "Démarrer" et u = M·ẑ l'image de la
 * verticale initiale : tant que u reste dans l'hémisphère haut, M vit dans
 * U = {M : (M·ẑ)·ẑ > 0}. La fibration SO(3) → S² étant triviale au-dessus d'un hémisphère
 * (contractile), U ≅ D² × S¹, donc π₁(U) = ℤ — et cet entier est exactement le nombre de
 * tours. On le lit en décomposant M = S(u)·R_z(ψ), où S(u) est la rotation d'arc minimal
 * ẑ → u (décomposition swing/twist) : ψ est la coordonnée S¹ de la trivialisation, et son
 * déroulement continu compte les tours.
 *
 * Deux propriétés que n'avaient pas les approches précédentes :
 *  - ψ est une fonction d'état de M, pas une intégrale de chemin : un cycle de ballottement
 *    qui ramène le téléphone à la même orientation ne laisse aucune dérive résiduelle
 *    (l'intégrale de ω·ẑ, elle, accumulait l'angle solide balayé — de l'ordre d'un tour
 *    fantôme pour quelques minutes de marche).
 *  - la seule singularité est u = −ẑ, c'est-à-dire tête en bas, à 180° du régime d'usage,
 *    au lieu du gimbal lock de getOrientation() qui tombe à 90° (téléphone vertical en poche).
 */
class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    private val currentRotationMatrix = FloatArray(9)

    // Axes de référence capturés au démarrage, exprimés dans le repère du téléphone :
    // la verticale du monde (b = R₀ᵀ·ẑ) et une direction horizontale (f = R₀ᵀ·x̂).
    private var referenceUp: DoubleArray? = null
    private val referenceForward = DoubleArray(3)
    private var lastTwistAngle: Double? = null

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
        referenceUp = null
        lastTwistAngle = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(currentRotationMatrix, event.values)

        if (referenceUp == null) captureReferenceFrame()

        val twist = twistAngleRad()
        if (twist != null) {
            lastTwistAngle?.let { previous ->
                var dpsi = twist - previous
                while (dpsi > PI) dpsi -= 2 * PI
                while (dpsi < -PI) dpsi += 2 * PI

                // Écarte les sauts improbables (bruit/ré-étalonnage) : à 50 Hz, une vraie
                // rotation ne peut pas dépasser 90° entre deux échantillons sans que le
                // téléphone tourne à plus de 12 tours/seconde.
                if (abs(dpsi) < PI / 2) {
                    SpinRepository.addRotation(dpsi)
                }
            }
            lastTwistAngle = twist
        }

        val elapsed = pausedElapsedMs + (SystemClock.elapsedRealtime() - runStartElapsedRealtime)
        SpinRepository.tick(elapsed)
    }

    /**
     * Mémorise l'orientation de départ R₀ sous la forme de deux axes du monde ramenés dans le
     * repère du téléphone : b = R₀ᵀ·ẑ (3e ligne de R₀) et f = R₀ᵀ·x̂ (1re ligne). Ils sont
     * orthonormés par construction, et donnent ψ = 0 au premier échantillon.
     */
    private fun captureReferenceFrame() {
        referenceUp = doubleArrayOf(
            currentRotationMatrix[6].toDouble(),
            currentRotationMatrix[7].toDouble(),
            currentRotationMatrix[8].toDouble(),
        )
        referenceForward[0] = currentRotationMatrix[0].toDouble()
        referenceForward[1] = currentRotationMatrix[1].toDouble()
        referenceForward[2] = currentRotationMatrix[2].toDouble()
    }

    /**
     * Angle de twist ψ autour de la verticale, pour M = R·R₀ᵀ décomposé en S(u)·R_z(ψ).
     * Renvoie null dans la configuration dégénérée "tête en bas", où le nombre de tours
     * n'est plus défini (u = −ẑ, sortie de U).
     */
    private fun twistAngleRad(): Double? {
        val b = referenceUp ?: return null
        val f = referenceForward
        val r = currentRotationMatrix

        // u = R·b : image de la verticale initiale. w = R·f : la direction horizontale de
        // référence. Les deux sont exprimées dans le repère du monde.
        val ux = r[0] * b[0] + r[1] * b[1] + r[2] * b[2]
        val uy = r[3] * b[0] + r[4] * b[1] + r[5] * b[2]
        val uz = r[6] * b[0] + r[7] * b[1] + r[8] * b[2]

        val wx = r[0] * f[0] + r[1] * f[1] + r[2] * f[2]
        val wy = r[3] * f[0] + r[4] * f[1] + r[5] * f[2]
        val wz = r[6] * f[0] + r[7] * f[1] + r[8] * f[2]

        if (1.0 + uz < 1e-3) return null

        // h = S(u)⁻¹·w, ramené dans le plan horizontal. Rodrigues autour de v = u × ẑ, réécrit
        // avec 1/(1+u_z) au lieu de 1/sin² pour rester lisse quand u est proche de ẑ — qui est
        // justement le cas nominal.
        val vx = uy
        val vy = -ux
        val k = (vx * wx + vy * wy) / (1.0 + uz)

        val hx = uz * wx + vy * wz + vx * k
        val hy = uz * wy - vx * wz + vy * k
        return atan2(hy, hx)
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
