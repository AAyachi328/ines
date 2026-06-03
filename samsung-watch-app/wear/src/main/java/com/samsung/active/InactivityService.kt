package com.samsung.active

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.samsung.active.presentation.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Service de premier plan (foreground service) qui tourne en permanence
 * et déclenche les alertes quand l'utilisateur est inactif trop longtemps.
 */
class InactivityService : LifecycleService() {

    private lateinit var detector: InactivityDetector
    private lateinit var vibrator: Vibrator

    private var tickJob: Job? = null
    private var alertCooldown = false   // évite de répéter l'alerte en boucle

    // Seuil transmis depuis MainActivity via Intent extra
    var thresholdMinutes: Int = 30

    override fun onCreate() {
        super.onCreate()
        detector = InactivityDetector(this)
        vibrator  = getSystemService(Vibrator::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        thresholdMinutes = intent?.getIntExtra(EXTRA_THRESHOLD_MINUTES, 30) ?: 30
        detector.thresholdMs = thresholdMinutes * 60 * 1000L

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESET -> {
                detector.resetTimer()
                alertCooldown = false
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIF_SERVICE_ID, buildServiceNotification())
        detector.start()
        startTickLoop()

        return START_STICKY
    }

    // ── Boucle de tick toutes les 30 s ────────────────────────────────────
    private fun startTickLoop() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (true) {
                detector.tick()

                if (detector.state.value == InactivityDetector.State.ALERT && !alertCooldown) {
                    triggerInactivityAlert()
                    alertCooldown = true
                } else if (detector.state.value == InactivityDetector.State.ACTIVE) {
                    alertCooldown = false
                }

                // Mettre à jour la notification de service avec le temps écoulé
                updateServiceNotification()

                delay(TICK_INTERVAL_MS)
            }
        }
    }

    // ── Alerte : vibration + notification ─────────────────────────────────
    private fun triggerInactivityAlert() {
        // Vibration longue sur la montre (pattern SOS)
        val pattern = longArrayOf(0, 500, 200, 500, 200, 800)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))

        // Notification haute priorité
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_SHOW_ALERT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mins = thresholdMinutes
        val suggestions = listOf(
            "Levez-vous et marchez 5 min 🚶",
            "10 squats maintenant ! 💪",
            "Étirez dos et épaules 🧘",
            "10 pompes, allez ! 🏋️",
            "Montez les escaliers 2x 🏃"
        )
        val notification = NotificationCompat.Builder(this, SamsungActiveApp.CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Assis depuis $mins min !")
            .setContentText(suggestions.random())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        val notifManager = getSystemService(android.app.NotificationManager::class.java)
        notifManager.notify(NOTIF_ALERT_ID, notification)
    }

    // ── Notification de service (persistante, basse priorité) ─────────────
    private fun buildServiceNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, InactivityService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SamsungActiveApp.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Samsung Active")
            .setContentText("Surveillance en cours…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Arrêter", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateServiceNotification() {
        val elapsed = detector.inactiveDurationMs.value
        val mins    = elapsed / 60000
        val secs    = (elapsed % 60000) / 1000
        val text = when (detector.state.value) {
            InactivityDetector.State.ACTIVE    -> "🟢 Actif"
            InactivityDetector.State.DETECTING -> "⚪ Détection position assise…"
            InactivityDetector.State.SITTING   -> "🟡 Assis depuis $mins min $secs s"
            InactivityDetector.State.ALERT     -> "🔴 Trop assis ! Levez-vous !"
        }

        val notif = NotificationCompat.Builder(this, SamsungActiveApp.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Samsung Active")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val notifManager = getSystemService(android.app.NotificationManager::class.java)
        notifManager.notify(NOTIF_SERVICE_ID, notif)
    }

    override fun onDestroy() {
        tickJob?.cancel()
        detector.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        const val ACTION_STOP              = "com.samsung.active.STOP"
        const val ACTION_RESET             = "com.samsung.active.RESET"
        const val EXTRA_THRESHOLD_MINUTES  = "threshold_minutes"
        private const val NOTIF_SERVICE_ID = 1
        private const val NOTIF_ALERT_ID   = 2
        private const val TICK_INTERVAL_MS = 30_000L  // toutes les 30 secondes
    }
}
