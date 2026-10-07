package com.echo.livetranslate.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echo.livetranslate.data.HistoryLine
import com.echo.livetranslate.data.HistorySession
import com.echo.livetranslate.data.HistoryStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DAY_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { HistoryStore.get(context) }
    val scope = rememberCoroutineScope()

    var sessions by remember { mutableStateOf<List<HistorySession>>(emptyList()) }
    var openSession by remember { mutableStateOf<HistorySession?>(null) }
    var lines by remember { mutableStateOf<List<HistoryLine>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<HistoryLine>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) { sessions = store.sessions() }
    LaunchedEffect(openSession) {
        lines = openSession?.let { store.lines(it.id) } ?: emptyList()
    }
    LaunchedEffect(query) { results = store.search(query) }

    val session = openSession
    if (session != null) {
        SessionDetail(
            session = session,
            lines = lines,
            onBack = { openSession = null },
            onExport = { shareText(context, sessionTitle(session), formatLines(lines)) },
            onDelete = {
                scope.launch {
                    store.deleteSession(session.id)
                    openSession = null
                    reload++
                }
            }
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索全部字幕") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            if (query.isBlank() && sessions.isNotEmpty()) {
                TextButton(onClick = { scope.launch { store.clearAll(); reload++ } }) {
                    Text("清空全部", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (query.isNotBlank()) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                items(results, key = { it.id }) { line -> LineRow(line) }
            }
            return@Column
        }

        if (sessions.isEmpty()) {
            Text(
                "还没有记录。开启字幕后，每句定稿的原文和译文都会自动存到这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(sessions, key = { it.id }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { openSession = item },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                sessionTitle(item),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text("${item.lineCount} 句", style = MaterialTheme.typography.labelMedium)
                        }
                        if (item.preview.isNotEmpty()) {
                            Text(
                                item.preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDetail(
    session: HistorySession,
    lines: List<HistoryLine>,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Text(sessionTitle(session), modifier = Modifier.weight(1f))
            TextButton(onClick = onExport) { Text("导出") }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(lines, key = { it.id }) { line -> LineRow(line) }
        }
    }
}

@Composable
private fun LineRow(line: HistoryLine) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            TIME_FORMAT.format(Date(line.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(line.original, style = MaterialTheme.typography.bodyMedium)
        if (line.translated.isNotEmpty()) {
            Text(
                line.translated,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun sessionTitle(s: HistorySession): String =
    DAY_FORMAT.format(Date(s.startedAt)) + "  " + if (s.mode == "OCR") "OCR" else "音频"

private fun formatLines(lines: List<HistoryLine>): String = lines.joinToString("\n\n") { line ->
    buildString {
        append('[').append(TIME_FORMAT.format(Date(line.timestamp))).append("] ")
        append(line.original)
        if (line.translated.isNotEmpty()) append('\n').append(line.translated)
    }
}

private fun shareText(context: Context, title: String, body: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, body)
    context.startActivity(Intent.createChooser(intent, "导出字幕记录"))
}
