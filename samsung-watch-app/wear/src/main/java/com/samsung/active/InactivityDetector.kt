package com.samsung.active

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Détecte l'inactivité physique uniquement via le podomètre.
 * Chaque pas détecté réinitialise le timer.
 * Si aucun pas pendant [thresholdMs] → la personne est assise → alerte.
 */
class InactivityDetector(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val stepDetector  = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private var lastStepTime = System.currentTimeMillis()
    private var running = false

    private val _isInactive         = MutableStateFlow(false)
    val isInactive: StateFlow<Boolean> = _isInactive

    private val _inactiveDurationMs = MutableStateFlow(0L)
    val inactiveDurationMs: StateFlow<Long> = _inactiveDurationMs

    var thresholdMs: Long = 30 * 60 * 1000L

    fun start() {
        if (running) return
        running = true
        lastStepTime = System.currentTimeMillis()
        _isInactive.value = false
        if (stepDetector != null) {
            sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        running = false
        sensorManager.unregisterListener(this)
    }

    fun tick() {
        if (!running) return
        val elapsed = System.currentTimeMillis() - lastStepTime
        _inactiveDurationMs.value = elapsed
        _isInactive.value = elapsed >= thresholdMs
    }

    // Chaque pas détecté → personne active → reset
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            lastStepTime = System.currentTimeMillis()
            _isInactive.value = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun hasStepSensor() = stepDetector != null
}
