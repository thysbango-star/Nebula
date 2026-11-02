package com.nebula.assistant.ui.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.nebula.assistant.data.FileRecord
import com.nebula.assistant.docs.DocumentStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(files: List<FileRecord>, onOpen: (FileRecord) -> Unit, onShare: (FileRecord) -> Unit, onDelete: (FileRecord) -> Unit, onCreate: (String) -> Unit) {
    val p = Pal
    var filter by remember { mutableStateOf("All") }
    val types = listOf("All", "docx", "xlsx", "pptx", "pdf", "other")
    val shown = when (filter) { "All" -> files; "other" -> files.filter { it.ext !in setOf("docx", "xlsx", "pptx", "pdf") }; else -> files.filter { it.ext == filter } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp)) {
                Text("Files", style = MaterialTheme.typography.displayMedium)
                Text("${files.size} made by Nebula · saved in Downloads/Nebula", style = MaterialTheme.typography.bodyMedium, color = p.dim)
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(types) { t -> Chip(if (t == "All" || t == "other") t.replaceFirstChar { it.uppercase() } else t.uppercase(), filter == t) { filter = t } }
            }
        }
        if (files.isEmpty()) item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                Text("Nothing yet. Ask Nebula for a document, spreadsheet, presentation or PDF:", style = MaterialTheme.typography.bodyMedium, color = p.dim)
                Spacer(Modifier.height(12.dp))
                listOf("Make an Excel sheet tracking my monthly expenses", "Create a PowerPoint about artificial intelligence", "Write a professional CV template in Word")
                    .forEach { s -> Chip(s) { onCreate(s) }; Spacer(Modifier.height(8.dp)) }
            }
        }
        items(shown, key = { it.id }) { f ->
            Box(Modifier.padding(horizontal = 16.dp).animateItem(fadeInSpec = tween(250), placementSpec = spring(dampingRatio = 0.8f))) {
                NCard(Modifier.fillMaxWidth(), color = p.raised, radius = 22.dp, onClick = { onOpen(f) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileBadge(f.ext, 46.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(f.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${f.ext.uppercase()} · ${DocumentStore.kb(f.size.toInt())} · ${SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(f.updatedAt))}",
                                style = MaterialTheme.typography.bodySmall, color = p.dim)
                        }
                        Icon(Icons.Rounded.Share, "Share", tint = p.dim, modifier = Modifier.size(38.dp).clip(CircleShape).pressable({ onShare(f) }).padding(9.dp))
                        Icon(Icons.Rounded.DeleteOutline, "Delete", tint = p.dim, modifier = Modifier.size(38.dp).clip(CircleShape).pressable({ onDelete(f) }).padding(9.dp))
                    }
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding().height(1.dp)) }
    }
}
