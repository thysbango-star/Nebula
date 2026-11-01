package com.nebula.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.nebula.assistant.brain.GeminiBrain
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.data.NebulaState
import com.nebula.assistant.data.WakeStatus
import com.nebula.assistant.ui.theme.NebulaTheme
import com.nebula.assistant.wake.WakeWordService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: NebulaPrefs

    private var perms by mutableStateOf(PermissionStatus())
    private var wakeEnabled by mutableStateOf(false)
    private var sensitivity by mutableFloatStateOf(0.5f)
    private var speakReplies by mutableStateOf(true)
    private var hasGeminiKey by mutableStateOf(false)
    private var geminiModel by mutableStateOf(NebulaPrefs.DEFAULT_MODEL)
    private var aiStatus by mutableStateOf<String?>(null)
    private var aiTesting by mutableStateOf(false)
    private var vibrance by mutableFloatStateOf(0.75f)

    /** What to do once the mic permission comes back granted. */
    private var afterMicGrant: (() -> Unit)? = null

    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshPermissions()
        if (granted) {
            afterMicGrant?.invoke()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            openAppSettings() // permanently denied: the only way is through Settings
        }
        afterMicGrant = null
    }

    private val notificationRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = NebulaPrefs(this)
        wakeEnabled = prefs.wakeEnabled
        sensitivity = prefs.sensitivity
        speakReplies = prefs.speakReplies
        hasGeminiKey = prefs.geminiKey.isNotBlank()
        geminiModel = prefs.geminiModel
        vibrance = prefs.vibranceIntensity

        setContent {
            NebulaTheme {
                val wake by NebulaState.wake.collectAsStateWithLifecycle()
                val level by NebulaState.micLevel.collectAsStateWithLifecycle()
                HomeScreen(
                    state = HomeUiState(
                        wake = wake,
                        micLevel = level,
                        wakeEnabled = wakeEnabled,
                        sensitivity = sensitivity,
                        speakReplies = speakReplies,
                        permissions = perms,
                        hasGeminiKey = hasGeminiKey,
                        geminiModel = geminiModel,
                        aiStatus = aiStatus,
                        aiTesting = aiTesting,
                        vibrance = vibrance,
                    ),
                    actions = HomeActions(
                        onWakeToggle = ::setWake,
                        onSensitivityChange = { sensitivity = it },
                        onSensitivityDone = {
                            prefs.sensitivity = sensitivity
                            if (wakeEnabled) WakeWordService.send(this, WakeWordService.ACTION_RELOAD)
                        },
                        onSpeakToggle = { speakReplies = it; prefs.speakReplies = it },
                        onTalk = ::talk,
                        onAllowMic = { requestMic() },
                        onAllowNotifications = ::requestNotifications,
                        onAllowOverlay = ::requestOverlay,
                        onAllowBattery = ::requestBattery,
                        onVibranceChange = { vibrance = it },
                        onVibranceDone = { prefs.vibranceIntensity = vibrance },
                        onSaveAi = ::saveAi,
                        onTestAi = ::testAi,
                        onGetKey = {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                            }
                        },
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        // Bring the listener back if it was turned on but got stopped (reboot, One UI, update…).
        if (prefs.wakeEnabled && perms.microphone) {
            val status = NebulaState.wake.value.status
            if (status == WakeStatus.Off || status == WakeStatus.Error) WakeWordService.start(this)
        }
    }

    // ------------------------------------------------------------------ actions

    private fun setWake(on: Boolean) {
        if (on && !perms.microphone) {
            requestMic { setWake(true) }
            return
        }
        prefs.wakeEnabled = on
        wakeEnabled = on
        if (on) {
            WakeWordService.start(this)
            if (!perms.notifications) requestNotifications()
        } else {
            WakeWordService.send(this, WakeWordService.ACTION_STOP)
        }
    }

    private fun saveAi(key: String, model: String) {
        val typed = NebulaPrefs.cleanKey(key)
        if (typed.isNotEmpty() && typed.length < 20) {
            aiStatus = "That doesn't look like a full key (${typed.length} characters). Paste the whole key."
            return
        }
        if (typed.isNotEmpty()) prefs.geminiKey = typed
        prefs.geminiModel = model.ifBlank { NebulaPrefs.DEFAULT_MODEL }
        hasGeminiKey = prefs.geminiKey.isNotBlank()
        geminiModel = prefs.geminiModel
        aiStatus = if (key.isNotBlank()) "Key saved. Tap Test to check it." else "Saved."
    }

    private fun testAi(key: String, model: String) {
        // Only test the typed text if it looks like a real key; otherwise test the saved one.
        val typed = NebulaPrefs.cleanKey(key)
        val useKey = if (typed.length >= 20) typed else prefs.geminiKey
        if (useKey.isBlank()) {
            aiStatus = "Paste your Gemini key first."
            return
        }
        aiTesting = true
        aiStatus = null
        lifecycleScope.launch {
            aiStatus = GeminiBrain(prefs).test(useKey, model.ifBlank { prefs.geminiModel })
            aiTesting = false
            geminiModel = prefs.geminiModel
            hasGeminiKey = prefs.geminiKey.isNotBlank()
        }
    }

    private fun talk() {
        if (!perms.microphone) {
            requestMic { talk() }
            return
        }
        startActivity(Intent(this, AssistantActivity::class.java))
    }

    private fun requestMic(then: (() -> Unit)? = null) {
        afterMicGrant = then
        micRequest.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun requestBattery() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun refreshPermissions() {
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        perms = PermissionStatus(
            microphone = granted(Manifest.permission.RECORD_AUDIO),
            notifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            overlay = Settings.canDrawOverlays(this),
            battery = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true,
        )
    }
}

data class PermissionStatus(
    val microphone: Boolean = false,
    val notifications: Boolean = false,
    val overlay: Boolean = false,
    val battery: Boolean = false,
) {
    val allSet get() = microphone && notifications && overlay && battery
}
