package com.samsung.active

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Détecte l'inactivité physique via l'accéléromètre de la Galaxy Watch.
 *
 * Principe : on calcule la magnitude de l'accélération filtrée (sans gravité).
 * Si elle reste sous MOTION_THRESHOLD pendant plus de [thresholdMs] ms → inactif.
 */
class InactivityDetector(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer  = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    // Temps de la dernière activité détectée
    private var lastMotionTime = System.currentTimeMillis()

    // Filtre passe-bas pour lisser les valeurs capteur
    private val gravity = FloatArray(3)

    // États observables
    private val _isInactive = MutableStateFlow(false)
    val isInactive: StateFlow<Boolean> = _isInactive

    private val _inactiveDurationMs = MutableStateFlow(0L)
    val inactiveDurationMs: StateFlow<Long> = _inactiveDurationMs

    // Seuil configurable (défaut : 30 minutes)
    var thresholdMs: Long = 30 * 60 * 1000L

    private var running = false

    // ── Démarrer ──────────────────────────────────────────────────────────
    fun start() {
        if (running) return
        running = true
        lastMotionTime = System.currentTimeMillis()
        _isInactive.value = false
        sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL   // ~5 Hz, économe en batterie
        )
    }

    // ── Arrêter ───────────────────────────────────────────────────────────
    fun stop() {
        running = false
        sensorManager.unregisterListener(this)
    }

    // ── Mise à jour appelée périodiquement par le service ─────────────────
    fun tick() {
        if (!running) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastMotionTime
        _inactiveDurationMs.value = elapsed
        _isInactive.value = elapsed >= thresholdMs
    }

    // ── Callback capteur ──────────────────────────────────────────────────
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Filtre passe-bas (α = 0.8) pour supprimer bruit haute fréquence
        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * x
        gravity[1] = alpha * gravity[1] + (1 - alpha) * y
        gravity[2] = alpha * gravity[2] + (1 - alpha) * z

        val dx = x - gravity[0]
        val dy = y - gravity[1]
        val dz = z - gravity[2]
        val magnitude = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

        if (magnitude > MOTION_THRESHOLD) {
            lastMotionTime = System.currentTimeMillis()
            if (_isInactive.value) {
                _isInactive.value = false
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        // Mouvement minimal pour être considéré comme "actif" (m/s²)
        private const val MOTION_THRESHOLD = 0.5f
    }
}
