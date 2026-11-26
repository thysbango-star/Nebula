package com.nebula.assistant.beam

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Picks what to Beam with the system's own photo picker and file picker (no storage permission needed), or text /
 * a link, or the clipboard (only when you choose it). Then hands it to [BeamController], or to the Android share
 * sheet for "Quick Share & apps". Also the landing spot for the Quick Settings tile ("open").
 */
class BeamActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BeamController.init(this)
        if (intent.getStringExtra("action") == "open") { BeamController.open(); finish(); return }
        if (intent.getStringExtra("action") == "hotspot") {
            // Hotspot mode needs Android's nearby-Wi-Fi permission (location on Android 12 and older).
            val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.NEARBY_WIFI_DEVICES else android.Manifest.permission.ACCESS_FINE_LOCATION
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                if (ok) BeamController.useHotspot()
                else android.widget.Toast.makeText(this, "Nebula needs the nearby Wi-Fi permission to start a hotspot.", android.widget.Toast.LENGTH_LONG).show()
                finish()
            }.launch(perm)
            return
        }
        val key = intent.getStringExtra("target").orEmpty()
        val target = BeamController.target(key)
        if (target == null) { finish(); return }
        setContent { Sheet(target) }
    }

    private fun itemFor(u: Uri): BeamItem {
        var name = u.lastPathSegment ?: "file"; var size = 0L
        runCatching {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
            }
        }
        return BeamItem(u, name, size, contentResolver.getType(u) ?: "application/octet-stream")
    }

    private fun go(target: BeamTarget, items: List<BeamItem>) {
        if (items.isEmpty()) return
        val max = com.nebula.assistant.data.NebulaPrefs(this).beamMaxMb * 1024L * 1024L
        if (target !is BeamTarget.System && items.sumOf { it.size } > max) {
            android.widget.Toast.makeText(this, "That's over your Beam limit (${max / (1024 * 1024)} MB) — change it in Settings → Beam.", android.widget.Toast.LENGTH_LONG).show(); return
        }
        if (target == BeamTarget.System) {
            val files = items.mapNotNull { it.uri }
            val text = items.firstOrNull { it.text != null }?.text
            val send = when {
                files.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files)).setType(commonType(items))
                files.size == 1 -> Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, files[0]).setType(items.first { it.uri != null }.mime)
                else -> Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text).setType("text/plain")
            }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(Intent.createChooser(send, "Beam with")) }
                .onFailure { android.widget.Toast.makeText(this, "Android sharing isn't available — try Beam Link.", android.widget.Toast.LENGTH_LONG).show(); return }
        }
        BeamController.send(target, items)
        finish()
    }

    private fun commonType(items: List<BeamItem>): String {
        val types = items.mapNotNull { it.mime.takeIf { _ -> it.uri != null } }.distinct()
        return when { types.size == 1 -> types[0]; types.all { it.startsWith("image/") } -> "image/*"; types.all { it.startsWith("video/") } -> "video/*"
            types.all { it.startsWith("image/") || it.startsWith("video/") } -> "*/*"; else -> "*/*" }
    }

    @Composable
    private fun Sheet(target: BeamTarget) {
        val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris -> go(target, uris.map(::itemFor)) }
        val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> go(target, uris.map(::itemFor)) }
        var typing by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        var clip by remember { mutableStateOf<String?>(null) }
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { finish() }, contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF15182A), Color(0xFF0B0C12))))
                .border(1.dp, Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color(0x0AFFFFFF))), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clickable(enabled = false) {}.navigationBarsPadding().padding(20.dp)) {
                Text("BEAM", color = Color(0xFF7FD4FF), style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp))
                Text("Send to ${target.label}", color = Color.White, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp))
                Text(when (target) {
                    is BeamTarget.Nebula -> "They'll be asked to accept. Encrypted, directly over your Wi-Fi."
                    BeamTarget.System -> "Opens Android sharing — Quick Share and your apps."
                    BeamTarget.Link -> "Makes a QR code for any phone or computer on the same Wi-Fi."
                }, color = Color(0xFFA9ADBB), style = TextStyle(fontSize = 13.sp), modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
                when {
                    clip != null -> {
                        Text("Send this from your clipboard?", color = Color.White, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                        Text(clip!!.take(400), color = Color(0xFFC7CAD6), style = TextStyle(fontSize = 13.sp), modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF)).padding(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Pill("Send it", true) { go(target, listOf(BeamItem(null, "Clipboard", clip!!.length.toLong(), "text/plain", clip))) }; Pill("Cancel", false) { clip = null } }
                    }
                    typing -> {
                        BasicTextField(text, { text = it }, textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Color(0xFF7FD4FF)),
                            modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF)).padding(12.dp))
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Pill("Send", true) { if (text.isNotBlank()) go(target, listOf(BeamItem(null, "Text", text.length.toLong(), "text/plain", text))) }; Pill("Back", false) { typing = false } }
                    }
                    else -> {
                        Choice(Icons.Rounded.PhotoLibrary, "Photos & videos", "Pick several at once") { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                        Choice(Icons.Rounded.Description, "Files", "Documents, music, anything") { docs.launch(arrayOf("*/*")) }
                        Choice(Icons.Rounded.Link, "Text or a link", "Type or paste it") { typing = true }
                        Choice(Icons.Rounded.ContentPaste, "Clipboard", "You'll see it before it's sent") {
                            clip = getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this@BeamActivity)?.toString()?.takeIf { it.isNotBlank() } ?: run {
                                android.widget.Toast.makeText(this@BeamActivity, "Your clipboard is empty.", android.widget.Toast.LENGTH_SHORT).show(); null }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Choice(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x0FFFFFFF)).clickable(onClick = onClick).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x334D8DFF)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color(0xFF7FD4FF)) }
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, color = Color.White, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                Text(sub, color = Color(0xFF8A8FA0), style = TextStyle(fontSize = 12.sp))
            }
        }
    }

    @Composable
    private fun Pill(label: String, primary: Boolean, onClick: () -> Unit) =
        Text(label, color = if (primary) Color.White else Color(0xFFC7CAD6), style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (primary) Color(0xFF4D8DFF) else Color(0x1AFFFFFF)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp))
}
