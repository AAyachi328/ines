package com.samsung.active.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.samsung.active.InactivityDetector
import com.samsung.active.InactivityService
import com.samsung.active.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvThreshold: TextView
    private lateinit var alertPanel: View
    private lateinit var tvAlertMessage: TextView

    private var thresholdMinutes = 30
    private var uiJob: Job? = null
    private var alertSuppressedUntil = 0L

    private lateinit var detector: InactivityDetector
    private val uiScope = CoroutineScope(Dispatchers.Main)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startSurveillance()
        else tvStatus.text = "❌ Permissions manquantes"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        detector = InactivityDetector(this)
        detector.thresholdMs = thresholdMinutes * 60 * 1000L

        tvStatus      = findViewById(R.id.tv_status)
        tvTimer       = findViewById(R.id.tv_timer)
        tvThreshold   = findViewById(R.id.tv_threshold)
        alertPanel    = findViewById(R.id.alert_panel)
        tvAlertMessage = findViewById(R.id.tv_alert_message)

        tvThreshold.text = "${thresholdMinutes}min"

        // Changer le seuil en tapant dessus
        tvThreshold.setOnClickListener {
            val steps = listOf(2, 10, 20, 30, 45, 60)
            val idx = steps.indexOf(thresholdMinutes)
            thresholdMinutes = steps[(idx + 1) % steps.size]
            detector.thresholdMs = thresholdMinutes * 60 * 1000L
            tvThreshold.text = "${thresholdMinutes}min"
        }

        findViewById<View>(R.id.btn_get_up).setOnClickListener {
            alertPanel.visibility = View.GONE
            detector.resetTimer()
            startService(Intent(this, InactivityService::class.java).apply {
                action = InactivityService.ACTION_RESET
            })
            alertSuppressedUntil = System.currentTimeMillis() + thresholdMinutes * 60 * 1000L
            tvStatus.text = "💪 Allez, bougez !"
        }

        findViewById<View>(R.id.btn_snooze).setOnClickListener {
            alertPanel.visibility = View.GONE
            alertSuppressedUntil = System.currentTimeMillis() + 10 * 60 * 1000L
        }

        if (intent?.action == ACTION_SHOW_ALERT) showAlertPanel()

        // Démarrage automatique
        checkPermissionsAndStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_SHOW_ALERT) showAlertPanel()
    }

    override fun onDestroy() {
        uiJob?.cancel()
        super.onDestroy()
    }

    private fun checkPermissionsAndStart() {
        val required = arrayOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.BODY_SENSORS
        )
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startSurveillance()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startSurveillance() {
        detector.start()
        startForegroundService(
            Intent(this, InactivityService::class.java).apply {
                putExtra(InactivityService.EXTRA_THRESHOLD_MINUTES, thresholdMinutes)
            }
        )
        startUILoop()
    }

    private fun startUILoop() {
        uiJob?.cancel()
        uiJob = uiScope.launch {
            while (true) {
                detector.tick()
                updateTimerDisplay()
                // L'alerte est gérée dans updateTimerDisplay()
                delay(1_000)
            }
        }
    }

    private fun updateTimerDisplay() {
        val ms   = detector.sittingDurationMs.value
        val mins = ms / 60000
        val secs = (ms % 60000) / 1000

        when (detector.state.value) {
            InactivityDetector.State.ACTIVE -> {
                tvStatus.text = "🟢 Actif"
                tvTimer.text  = "--:--"
                tvTimer.setTextColor(0xFFFFFFFF.toInt())
                alertPanel.visibility = View.GONE
            }
            InactivityDetector.State.DETECTING -> {
                tvStatus.text = "⚪ Détection…"
                tvTimer.text  = "--:--"
                tvTimer.setTextColor(0xFFAAAAAA.toInt())
            }
            InactivityDetector.State.SITTING -> {
                tvStatus.text = "🟡 Assis"
                tvTimer.text  = "%02d:%02d".format(mins, secs)
                val ratio = ms.toFloat() / detector.thresholdMs
                tvTimer.setTextColor(when {
                    ratio >= 0.8f -> 0xFFFFB300.toInt()
                    else          -> 0xFFFFFFFF.toInt()
                })
            }
            InactivityDetector.State.ALERT -> {
                tvStatus.text = "🔴 Trop assis !"
                tvTimer.text  = "%02d:%02d".format(mins, secs)
                tvTimer.setTextColor(0xFFFF4B4B.toInt())
                if (System.currentTimeMillis() > alertSuppressedUntil)
                    showAlertPanel()
            }
        }
    }

    private fun showAlertPanel() {
        tvAlertMessage.text = "Assis depuis\n$thresholdMinutes min !\nLevez-vous 🚶"
        alertPanel.visibility = View.VISIBLE
    }

    companion object {
        const val ACTION_SHOW_ALERT = "com.samsung.active.SHOW_ALERT"
    }
}
