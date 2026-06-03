package com.samsung.active.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

/**
 * Écran principal affiché sur le cadran de la Galaxy Watch.
 * Petite interface ronde adaptée à l'écran circulaire Wear OS.
 */
class MainActivity : AppCompatActivity() {

    // ── Vues ──────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvThreshold: TextView
    private lateinit var btnToggleText: TextView
    private lateinit var alertPanel: View
    private lateinit var tvAlertMessage: TextView

    // ── État ──────────────────────────────────────────────────────────────
    private var isRunning = false
    private var thresholdMinutes = 30
    private var uiJob: Job? = null
    private var alertSuppressedUntil = 0L  // timestamp jusqu'où l'alerte est silencieuse

    // Détecteur local pour afficher le timer en temps réel dans l'UI
    private lateinit var detector: InactivityDetector

    private val uiScope = CoroutineScope(Dispatchers.Main)

    // ── Permissions ───────────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startSurveillance()
        else showError("Permissions requises !")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        detector = InactivityDetector(this)
        detector.thresholdMs = thresholdMinutes * 60 * 1000L

        bindViews()
        setupClickListeners()
        updateUI()

        // Déclenchement depuis la notification d'alerte
        if (intent?.action == ACTION_SHOW_ALERT) showAlertPanel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_SHOW_ALERT) showAlertPanel()
    }

    override fun onDestroy() {
        uiJob?.cancel()
        if (!isRunning) detector.stop()
        super.onDestroy()
    }

    // ── Init vues ─────────────────────────────────────────────────────────
    private fun bindViews() {
        tvStatus      = findViewById(R.id.tv_status)
        tvTimer       = findViewById(R.id.tv_timer)
        tvThreshold   = findViewById(R.id.tv_threshold)
        btnToggleText = findViewById(R.id.btn_toggle_text)
        alertPanel    = findViewById(R.id.alert_panel)
        tvAlertMessage = findViewById(R.id.tv_alert_message)
    }

    private fun setupClickListeners() {
        // Démarrer / Arrêter
        btnToggleText.setOnClickListener {
            if (isRunning) stopSurveillance() else checkPermissionsAndStart()
        }

        // Seuil : appui court = +10 min (cycle 10→20→30→45→60→10…)
        tvThreshold.setOnClickListener {
            val steps = listOf(2, 10, 20, 30, 45, 60)
            val idx = steps.indexOf(thresholdMinutes)
            thresholdMinutes = steps[(idx + 1) % steps.size]
            detector.thresholdMs = thresholdMinutes * 60 * 1000L
            tvThreshold.text = "${thresholdMinutes}min"
        }

        // "Se lever" : reset le timer → la personne a un nouveau seuil complet
        findViewById<View>(R.id.btn_get_up).setOnClickListener {
            alertPanel.visibility = View.GONE
            detector.resetTimer()
            alertSuppressedUntil = System.currentTimeMillis() + thresholdMinutes * 60 * 1000L
            showMotivation()
        }
        // "Plus tard" : snooze 10 min sans reset du timer
        findViewById<View>(R.id.btn_snooze).setOnClickListener {
            alertPanel.visibility = View.GONE
            alertSuppressedUntil = System.currentTimeMillis() + 10 * 60 * 1000L
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────
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

    // ── Surveillance ──────────────────────────────────────────────────────
    private fun startSurveillance() {
        isRunning = true
        detector.start()
        startForegroundService(
            Intent(this, InactivityService::class.java).apply {
                putExtra(InactivityService.EXTRA_THRESHOLD_MINUTES, thresholdMinutes)
            }
        )
        startUILoop()
        updateUI()
    }

    private fun stopSurveillance() {
        isRunning = false
        detector.stop()
        uiJob?.cancel()
        stopService(Intent(this, InactivityService::class.java))
        updateUI()
    }

    // ── Boucle UI (1 s) ───────────────────────────────────────────────────
    private fun startUILoop() {
        uiJob?.cancel()
        uiJob = uiScope.launch {
            while (true) {
                detector.tick()
                updateTimerDisplay()
                if (detector.isInactive.value && System.currentTimeMillis() > alertSuppressedUntil)
                    showAlertPanel()
                delay(1_000)
            }
        }
    }

    // ── Affichage ─────────────────────────────────────────────────────────
    private fun updateUI() {
        if (isRunning) {
            tvStatus.text = "🔵  Surveillance active"
            btnToggleText.text = "Arrêter"
        } else {
            tvStatus.text = "⚪  En veille"
            btnToggleText.text = "Démarrer"
            tvTimer.text = "--:--"
        }
        tvThreshold.text = "${thresholdMinutes}min"
    }

    private fun updateTimerDisplay() {
        val ms   = detector.inactiveDurationMs.value
        val mins = ms / 60000
        val secs = (ms % 60000) / 1000
        tvTimer.text = "%02d:%02d".format(mins, secs)

        // Couleur selon proximité du seuil
        val ratio = ms.toFloat() / detector.thresholdMs
        tvTimer.setTextColor(
            when {
                ratio >= 1f   -> 0xFFFF4B4B.toInt()  // rouge
                ratio >= 0.8f -> 0xFFFFB300.toInt()  // orange
                else          -> 0xFFFFFFFF.toInt()  // blanc
            }
        )
    }

    private fun showAlertPanel() {
        tvAlertMessage.text = "Vous êtes assis\ndepuis $thresholdMinutes min !\nLevez-vous 🚶"
        alertPanel.visibility = View.VISIBLE
    }

    private fun showMotivation() {
        tvStatus.text = "💪  Allez, bougez !"
        Handler(Looper.getMainLooper()).postDelayed({ updateUI() }, 4_000)
    }

    private fun showError(msg: String) {
        tvStatus.text = "❌  $msg"
    }

    companion object {
        const val ACTION_SHOW_ALERT = "com.samsung.active.SHOW_ALERT"
    }
}
