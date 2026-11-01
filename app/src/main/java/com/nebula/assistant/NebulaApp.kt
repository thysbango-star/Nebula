package com.nebula.assistant

import android.app.Application
import com.nebula.assistant.wake.Notifications

class NebulaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
