package com.quickname.floating

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class QuickNameApp : Application() {

    companion object {
        const val CHANNEL_ID = "quickname_floating_service"
        lateinit var instance: QuickNameApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "QuickName Floating Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the QuickName floating button active above apps"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
