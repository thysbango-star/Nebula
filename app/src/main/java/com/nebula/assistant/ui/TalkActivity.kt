package com.nebula.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.nebula.assistant.wake.WakeWordService

/**
 * Invisible trampoline: lets the Notch start a voice conversation from inside any app
 * (Android only lets microphone services start while something of ours is visible).
 */
class TalkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            WakeWordService.talk(this)
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
