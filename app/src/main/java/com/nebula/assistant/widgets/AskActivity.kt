package com.nebula.assistant.widgets

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.assistant.ui.MainActivity

/**
 * The Ask Nebula widget's text box: type, send, and the question goes straight into a new chat in Nebula
 * (already sent — no retyping).
 */
class AskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefill = intent.getStringExtra("prefill").orEmpty()
        setContent { Sheet(prefill) }
    }

    private fun send(q: String) {
        if (q.isBlank()) return
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PROMPT, q.trim())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    @Composable
    private fun Sheet(prefill: String) {
        var text by remember { mutableStateOf(TextFieldValue(prefill, TextRange(prefill.length))) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(Modifier.fillMaxSize().background(Color(0x88000000)).clickable { finish() }.imePadding(), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF15182A), Color(0xFF0B0C12))))
                .border(1.dp, Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color(0x0AFFFFFF))), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clickable(enabled = false) {}.navigationBarsPadding().padding(18.dp)) {
                Text("ASK NEBULA", color = Color(0xFF5FD8FF), style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp))
                Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0x14FFFFFF))
                    .border(1.dp, Color(0x334DD6FF), RoundedCornerShape(22.dp)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        if (text.text.isEmpty()) Text("Ask anything…", color = Color(0xFF8A8FA0), style = TextStyle(fontSize = 16.sp))
                        BasicTextField(text, { text = it }, textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Color(0xFF5FD8FF)),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send(text.text) }),
                            maxLines = 5, modifier = Modifier.fillMaxWidth().focusRequester(focus))
                    }
                    Box(Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF5FD8FF), Color(0xFF4D8DFF), Color(0xFF8B5CF6))))
                        .clickable { send(text.text) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
