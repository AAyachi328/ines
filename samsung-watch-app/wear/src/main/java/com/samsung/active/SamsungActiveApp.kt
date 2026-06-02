package com.samsung.active

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class SamsungActiveApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        // Canal pour l'alerte d'inactivité (priorité haute → vibre + son)
        NotificationChannel(
            CHANNEL_ALERT,
            "Alerte inactivité",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notification quand vous êtes assis trop longtemps"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            manager.createNotificationChannel(this)
        }

        // Canal pour la notification persistante du service (priorité basse)
        NotificationChannel(
            CHANNEL_SERVICE,
            "Surveillance active",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Service de détection d'inactivité en cours"
            manager.createNotificationChannel(this)
        }
    }

    companion object {
        const val CHANNEL_ALERT   = "inactivity_alert"
        const val CHANNEL_SERVICE = "inactivity_service"
    }
}
