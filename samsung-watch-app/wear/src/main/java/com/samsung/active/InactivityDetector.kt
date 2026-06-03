package com.samsung.active

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * Détecte qu'une personne est assise/inactive (travail sur ordi, télé, etc.)
 *
 * Stratégie double :
 *  1. Accéléromètre  → seuls les gros mouvements de bras comptent (lever du siège,
 *                       marche, sport). Taper sur un clavier ou bouger la souris
 *                       ne réinitialise PAS le timer.
 *  2. Podomètre      → chaque pas détecté confirme l'activité physique réelle.
 *
 * Un mouvement n'est retenu que s'il dure suffisamment (SUSTAINED_MOTION_MS) pour
 * éviter les faux positifs (gratter la tête, attraper sa tasse de café…).
 */
class InactivityDetector(context: Context) : SensorEventListener {

    private val sensorManager  = context.getSystemService(SensorManager::class.java)
    private val accelerometer  = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val stepDetector   = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private var lastMotionTime    = System.currentTimeMillis()
    private var motionStartTime   = 0L   // début d'un mouvement continu
    private var inMotion          = false

    private val _isInactive         = MutableStateFlow(false)
    val isInactive: StateFlow<Boolean> = _isInactive

    private val _inactiveDurationMs = MutableStateFlow(0L)
    val inactiveDurationMs: StateFlow<Long> = _inactiveDurationMs

    var thresholdMs: Long = 30 * 60 * 1000L

    private var running = false

    // ── Démarrer ──────────────────────────────────────────────────────────
    fun start() {
        if (running) return
        running = true
        lastMotionTime = System.currentTimeMillis()
        _isInactive.value = false
        inMotion = false

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        stepDetector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    // ── Arrêter ───────────────────────────────────────────────────────────
    fun stop() {
        running = false
        sensorManager.unregisterListener(this)
    }

    // ── Tick périodique ───────────────────────────────────────────────────
    fun tick() {
        if (!running) return
        val elapsed = System.currentTimeMillis() - lastMotionTime
        _inactiveDurationMs.value = elapsed
        _isInactive.value = elapsed >= thresholdMs
    }

    // ── Callback capteur ──────────────────────────────────────────────────
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> handleAccelerometer(event)
            Sensor.TYPE_STEP_DETECTOR       -> registerRealActivity()  // pas détecté = mouvement certain
        }
    }

    private fun handleAccelerometer(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        when {
            magnitude > BIG_MOTION_THRESHOLD -> {
                // Grand mouvement (lever du siège, marche, sport) → actif immédiatement
                registerRealActivity()
                inMotion = true
                motionStartTime = System.currentTimeMillis()
            }
            magnitude > SMALL_MOTION_THRESHOLD -> {
                // Mouvement moyen : on attend qu'il dure assez longtemps
                if (!inMotion) {
                    inMotion = true
                    motionStartTime = System.currentTimeMillis()
                } else {
                    val duration = System.currentTimeMillis() - motionStartTime
                    if (duration >= SUSTAINED_MOTION_MS) registerRealActivity()
                }
            }
            else -> {
                // Mouvement trop faible (frappe clavier, souris) → ignoré
                inMotion = false
            }
        }
    }

    private fun registerRealActivity() {
        lastMotionTime = System.currentTimeMillis()
        _isInactive.value = false
        inMotion = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        // Mouvement de bras significatif : se lever, marcher (m/s²)
        private const val BIG_MOTION_THRESHOLD   = 3.0f
        // Mouvement moyen à confirmer dans la durée (m/s²)
        private const val SMALL_MOTION_THRESHOLD = 1.2f
        // Durée minimale d'un mouvement moyen pour être retenu
        private const val SUSTAINED_MOTION_MS    = 2_000L  // 2 secondes
    }
}
