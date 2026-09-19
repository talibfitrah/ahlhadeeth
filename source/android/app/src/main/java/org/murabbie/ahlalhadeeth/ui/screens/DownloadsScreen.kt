package org.murabbie.ahlalhadeeth.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.DownloadEntry
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.openMedia
import org.murabbie.ahlalhadeeth.ui.EmptyState
import org.murabbie.ahlalhadeeth.ui.Loading
import org.murabbie.ahlalhadeeth.ui.Routes

/** مدير التنزيلات (نافذة التنزيلات في نسخة ويندوز) */
@Composable
fun DownloadsScreen(app: App, repo: Repository, nav: NavHostController) {
    val version by app.userDb.downloadsVersion.collectAsState()
    val progress by app.audioDownloads.progress.collectAsState()
    var list by remember { mutableStateOf<List<DownloadEntry>?>(null) }
    var toDelete by remember { mutableStateOf<DownloadEntry?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(version) { list = app.userDb.downloads() }

    val dir = app.audioDownloads.audioSource.downloadsDir()
    Scaffold(topBar = {
        AppTopBar("التنزيلات", nav, subtitle = "المجلد: ${dir.absolutePath}", actions = {
            IconButton(onClick = { app.audioDownloads.pauseAll() }) { Icon(Icons.Filled.Pause, contentDescription = "إيقاف الكل") }
            IconButton(onClick = { app.audioDownloads.resumeAll() }) { Icon(Icons.Filled.Refresh, contentDescription = "استئناف الكل") }
        })
    }) { padding ->
        val items = list
        val transfer by app.transfer.state.collectAsState()
        if (items == null) {
            Loading(Modifier.padding(padding)); return@Scaffold
        }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (transfer.visible) TransferStatusCard(app, nav, Modifier.padding(12.dp))
                EmptyState("لا توجد تنزيلات.\nمن شاشة الشريط أو الكتاب اختر «تنزيل» للاستماع بلا إنترنت.", Modifier.fillMaxSize())
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (transfer.visible) TransferStatusCard(app, nav, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            val done = items.count { it.state == DownloadEntry.DONE }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text("مكتمل: ${ArabicText.arabicDigits(done)} من ${ArabicText.arabicDigits(items.size)}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                if (done > 0) TextButton(onClick = { app.audioDownloads.clearCompleted() }) { Text("إزالة المكتملة من القائمة") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.code }) { d ->
                    val live = progress[d.code]
                    val bytes = live?.first ?: d.bytes
                    val total = live?.second ?: d.total
                    ListItem(
                        headlineContent = { Text(d.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                val status = when (d.state) {
                                    DownloadEntry.PENDING -> "في الانتظار"
                                    DownloadEntry.RUNNING -> "جارٍ التنزيل"
                                    DownloadEntry.PAUSED -> "متوقف"
                                    DownloadEntry.DONE -> "مكتمل"
                                    else -> "خطأ: ${d.error}"
                                }
                                val sizeText = if (total > 0) "${ArabicText.formatSize(bytes)} / ${ArabicText.formatSize(total)}" else ArabicText.formatSize(bytes)
                                Text("$status — $sizeText", style = MaterialTheme.typography.labelMedium, color = if (d.state == DownloadEntry.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (d.state == DownloadEntry.RUNNING || d.state == DownloadEntry.PAUSED) {
                                    Spacer(Modifier.height(4.dp))
                                    if (total > 0) LinearProgressIndicator(progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                        },
                        trailingContent = {
                            Row {
                                when (d.state) {
                                    DownloadEntry.RUNNING, DownloadEntry.PENDING -> IconButton(onClick = { app.audioDownloads.pause(d.code) }) { Icon(Icons.Filled.Pause, contentDescription = "إيقاف") }
                                    DownloadEntry.PAUSED, DownloadEntry.ERROR -> IconButton(onClick = { app.audioDownloads.resume(d.code) }) { Icon(Icons.Filled.Refresh, contentDescription = "استئناف") }
                                    DownloadEntry.DONE -> IconButton(onClick = { scope.launch { repo.chapter(d.code)?.let { openMedia(app, nav, it, 0) } } }) { Icon(Icons.Filled.PlayArrow, contentDescription = "تشغيل") }
                                }
                                IconButton(onClick = { toDelete = d }) { Icon(Icons.Filled.Delete, contentDescription = "حذف") }
                            }
                        },
                        modifier = Modifier.clickable { nav.navigate(Routes.tape(d.code)) },
                    )
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
    val td = toDelete
    if (td != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("حذف التنزيل") },
            text = { Text(if (td.state == DownloadEntry.DONE) "هل تريد حذف الملف الصوتي من الجهاز؟" else "إزالة هذا التنزيل وحذف الجزء المنزَّل؟") },
            confirmButton = { TextButton(onClick = { app.audioDownloads.remove(td.code, true); toDelete = null }) { Text("حذف الملف") } },
            dismissButton = { TextButton(onClick = { app.audioDownloads.remove(td.code, false); toDelete = null }) { Text("إزالة من القائمة فقط") } },
        )
    }
}
