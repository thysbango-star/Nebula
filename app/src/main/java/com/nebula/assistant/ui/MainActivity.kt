package com.nebula.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.nebula.assistant.agent.TaskEngine
import com.nebula.assistant.audio.Sounds
import com.nebula.assistant.brain.AgentBrain
import com.nebula.assistant.data.FileRecord
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.data.NebulaState
import com.nebula.assistant.data.WakeStatus
import com.nebula.assistant.docs.DocumentStore
import com.nebula.assistant.ui.app.Host
import com.nebula.assistant.ui.app.NebulaAppTheme
import com.nebula.assistant.ui.app.NebulaRoot
import com.nebula.assistant.ui.app.Pal
import com.nebula.assistant.ui.app.Perm
import com.nebula.assistant.ui.app.Permissions
import com.nebula.assistant.wake.WakeWordService

class MainActivity : ComponentActivity() {

    private lateinit var prefs: NebulaPrefs
    private lateinit var docs: DocumentStore
    private var perms by mutableStateOf<List<Perm>>(emptyList())
    private var theme by mutableStateOf("system")
    private var startTab by mutableIntStateOf(0)
    private var startTask by mutableStateOf<String?>(null)
    private var afterGrant: (() -> Unit)? = null
    private var player: android.media.MediaPlayer? = null

    private val runtime = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refresh()
        if (result.values.all { it }) afterGrant?.invoke()
        else if (result.keys.any { !shouldShowRequestPermissionRationale(it) && ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Allow it in App info → Permissions", Toast.LENGTH_LONG).show()
            startActivity(Permissions.appDetails(this))
        }
        afterGrant = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = NebulaPrefs(this)
        docs = DocumentStore(this)
        theme = prefs.theme
        readIntent(intent)
        val engine = TaskEngine.get(this)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

        val host = Host(
            openFile = ::openFile,
            shareFile = { f -> runCatching { startActivity(docs.shareIntent(f)) } },
            openUrl = { u -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } },
            voice = ::talk,
            requestPerm = ::request,
            restricted = { startActivity(Permissions.appDetails(this)) },
            setWake = ::setWake,
            wakeSensitivity = { s -> prefs.sensitivity = s; if (prefs.wakeEnabled) WakeWordService.send(this, WakeWordService.ACTION_RELOAD) },
            previewSound = { ring -> player?.release(); player = Sounds.preview(this, ring) },
            setDefaultSound = ::setDefaultSound,
            setTheme = { t -> prefs.theme = t; theme = t },
            getKey = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) } },
            version = version,
        )

        setContent {
            NebulaAppTheme(theme) {
                val dark = Pal.dark
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                    }
                }
                NebulaRoot(engine, prefs, perms, host, startTab, startTask)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Bring the wake word listener back if it was on but got stopped.
        if (prefs.wakeEnabled && has(Manifest.permission.RECORD_AUDIO)) {
            val status = NebulaState.wake.value.status
            if (status == WakeStatus.Off || status == WakeStatus.Error) WakeWordService.start(this)
        }
    }

    override fun onDestroy() { player?.release(); super.onDestroy() }

    private fun readIntent(i: Intent?) {
        when (i?.getStringExtra(EXTRA_TAB)) { "tasks" -> startTab = 1; "files" -> startTab = 2; "chat" -> startTab = 0 }
        i?.getStringExtra(EXTRA_TASK)?.let { startTask = it }
    }

    private fun refresh() { perms = Permissions.all(this) }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun request(p: Perm, then: (() -> Unit)? = null) {
        if (p.runtime.isNotEmpty()) { afterGrant = then; runtime.launch(p.runtime); return }
        val i = p.settings ?: return
        try { startActivity(i) } catch (e: Exception) { startActivity(Permissions.appDetails(this)) }
        if (p.restricted) Toast.makeText(this, "Greyed out? App info → ⋮ → Allow restricted settings", Toast.LENGTH_LONG).show()
    }

    private fun setWake(on: Boolean) {
        if (on && !has(Manifest.permission.RECORD_AUDIO)) {
            request(perms.first { it.id == "mic" }) { setWake(true) }
            return
        }
        prefs.wakeEnabled = on
        if (on) WakeWordService.start(this) else WakeWordService.send(this, WakeWordService.ACTION_STOP)
    }

    private fun talk(conversationId: String) {
        if (!has(Manifest.permission.RECORD_AUDIO)) { request(perms.first { it.id == "mic" }) { talk(conversationId) }; return }
        AgentBrain.focusConversationId = conversationId
        startActivity(Intent(this, AssistantActivity::class.java))
    }

    private fun openFile(f: FileRecord) {
        try { startActivity(docs.openIntent(f)) } catch (e: Exception) {
            Toast.makeText(this, "No app on this phone opens .${f.ext} files. Try Microsoft Office or Google Docs/Sheets/Slides.", Toast.LENGTH_LONG).show()
            runCatching { startActivity(docs.shareIntent(f)) }
        }
    }

    private fun setDefaultSound(ringtone: Boolean) {
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, "Allow “Modify system settings” for Nebula, then tap again.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        val ok = runCatching { Sounds.setDefault(this, ringtone) }.getOrDefault(false)
        Toast.makeText(this, if (ok) "Nebula ${if (ringtone) "ringtone" else "chime"} set." else "Couldn't set it on this phone.", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_TASK = "task"
    }
}
