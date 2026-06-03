package com.samsung.active

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Détection en deux phases :
 *
 * ACTIVE      → des pas sont détectés, personne en mouvement
 * DETECTING   → plus de pas depuis [CONFIRMATION_MS], on attend confirmation
 * SITTING     → assis confirmé, le chrono d'inactivité tourne
 * ALERT       → seuil dépassé, alerte déclenchée
 */
class InactivityDetector(context: Context) : SensorEventListener {

    enum class State { ACTIVE, DETECTING, SITTING, ALERT }

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val stepDetector  = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private var lastStepTime    = System.currentTimeMillis()
    private var sittingStartTime = 0L
    private var running = false

    val state = MutableStateFlow(State.ACTIVE)

    // Durée d'inactivité depuis que la position assise est confirmée
    private val _sittingDurationMs = MutableStateFlow(0L)
    val sittingDurationMs: StateFlow<Long> = _sittingDurationMs

    val isInactive: StateFlow<State> get() = state

    // Seuil principal (ex: 30 min)
    var thresholdMs: Long = 30 * 60 * 1000L

    fun start() {
        if (running) return
        running = true
        lastStepTime = System.currentTimeMillis()
        state.value = State.ACTIVE
        stepDetector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        running = false
        sensorManager.unregisterListener(this)
    }

    fun resetTimer() {
        lastStepTime = System.currentTimeMillis()
        sittingStartTime = 0L
        state.value = State.ACTIVE
        _sittingDurationMs.value = 0L
    }

    fun tick() {
        if (!running) return
        val now     = System.currentTimeMillis()
        val noSteps = now - lastStepTime

        when (state.value) {
            State.ACTIVE -> {
                // Personne vient de s'arrêter → phase détection
                if (noSteps > STEP_ACTIVE_COOLDOWN_MS) {
                    state.value = State.DETECTING
                }
            }
            State.DETECTING -> {
                // Assez longtemps sans pas → position assise confirmée
                if (noSteps >= CONFIRMATION_MS) {
                    sittingStartTime = now - (noSteps - CONFIRMATION_MS)
                    state.value = State.SITTING
                }
            }
            State.SITTING -> {
                val sitting = now - sittingStartTime
                _sittingDurationMs.value = sitting
                if (sitting >= thresholdMs) state.value = State.ALERT
            }
            State.ALERT -> {
                // On continue à mettre à jour le chrono
                _sittingDurationMs.value = now - sittingStartTime
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return
        // Pas détecté → personne active → tout reset
        lastStepTime = System.currentTimeMillis()
        sittingStartTime = 0L
        _sittingDurationMs.value = 0L
        state.value = State.ACTIVE
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun hasStepSensor() = stepDetector != null

    companion object {
        // Délai sans pas pour quitter l'état ACTIVE (5 secondes)
        private const val STEP_ACTIVE_COOLDOWN_MS = 5_000L
        // Délai sans pas pour confirmer la position assise (30 secondes)
        private const val CONFIRMATION_MS          = 30_000L
    }
}
