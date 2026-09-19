package org.murabbie.ahlalhadeeth.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.Chapter
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.TransferJob
import org.murabbie.ahlalhadeeth.data.YouTubeMedia
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.Routes

/** سطر ملخّص لحالة النقل: «٣ / ١٢ — رفع «…» ٤٥٪ — ١٫٢ م.ب/ث» */
fun transferSummary(st: TransferJob.State): String {
    if (st.running) {
        return "${ArabicText.arabicDigits(st.doneCount)} / ${ArabicText.arabicDigits(st.items.size)}" + (if (st.phase.isNotBlank()) " — ${st.phase}" else "") + (if (st.speedBps > 0) " — ${ArabicText.formatSize(st.speedBps)}/ث" else "")
    }
    return "انتهى: ${ArabicText.arabicDigits(st.okCount)} على الخادم" + (if (st.errCount > 0) "، تعذر ${ArabicText.arabicDigits(st.errCount)}" else "") + (if (st.pendingCount > 0) "، بقي ${ArabicText.arabicDigits(st.pendingCount)}" else "") + (if (st.published) " — نُشر للجميع" else "")
}

/** بطاقة حالة النقل تُعرض في شاشات التنزيلات والمحتوى المضاف (تفتح شاشة النقل) */
@Composable
fun TransferStatusCard(app: App, nav: NavHostController, modifier: Modifier = Modifier) {
    val st by app.transfer.state.collectAsState()
    if (!st.visible) return
    Card(modifier.fillMaxWidth().clickable { nav.navigate(Routes.transfer(st.sheekhId, st.bookId)) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (st.running) "نقل الدروس إلى الخادم" else "نقل الدروس إلى الخادم — انتهى") + (if (st.bookName.isNotBlank()) ": ${st.bookName}" else ""), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!st.running) IconButton(onClick = { app.transfer.dismiss() }) { Icon(Icons.Filled.Close, contentDescription = "إخفاء") }
            }
            Text(transferSummary(st), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { st.doneCount.toFloat() / st.items.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            Text("اضغط لعرض التفاصيل", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** نقل دروس المشرف في سلسلة إلى خادم البيانات (الهاتف جسر مؤقت) — الحالة تبقى وتُتابَع من أي شاشة */
@Composable
fun TransferScreen(app: App, repo: Repository, nav: NavHostController, sheekhId: Int, bookId: Int, autoStart: Boolean, initialQuality: Int = -1) {
    val job = app.transfer
    val st by job.state.collectAsState()
    val sync by app.sharedSync.state.collectAsState()
    val contentVersion by app.userDb.content.version.collectAsState()
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var bookName by remember { mutableStateOf("") }
    var bookKey by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(if (initialQuality >= 0) initialQuality else app.settings.value.lastImportQuality) }
    var started by remember { mutableStateOf(false) }
    // الشاشة تعرض القائمة الجارية إن كانت لسلسلة أخرى
    val showingJob = st.items.isNotEmpty() && (st.sheekhId != sheekhId || st.bookId != bookId)
    val effSheekh = if (showingJob) st.sheekhId else sheekhId
    val effBook = if (showingJob) st.bookId else bookId

    LaunchedEffect(effSheekh, effBook, contentVersion) {
        chapters = if (effSheekh != 0 && effBook != 0) repo.chapters(effSheekh, effBook) else emptyList()
        val b = if (effBook != 0) repo.book(effBook) else null
        bookName = b?.name ?: chapters.firstOrNull()?.bookName ?: st.bookName
        bookKey = app.userDb.content.bookKey(-effBook).ifBlank { "b$effBook" }
    }
    val pending = chapters.filter { it.needsTransfer }
    val onServer = chapters.count { it.isOnServer }
    val subDir = "media/" + bookKey.replace(Regex("[^A-Za-z0-9_-]"), "")
    LaunchedEffect(chapters, autoStart) {
        if (autoStart && !started && pending.isNotEmpty() && !st.running && sync.isAdmin) { started = true; job.clear(); job.start(pending, subDir, quality, effSheekh, effBook, bookName) }
    }
    val elapsed = if (st.startedAt > 0) ((if (st.running) System.currentTimeMillis() else st.finishedAt) - st.startedAt) / 1000 else 0L

    Scaffold(topBar = { AppTopBar("نقل الدروس إلى الخادم", nav, subtitle = bookName) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("الهاتف هنا جسر مؤقت فقط: يُنزَّل كل درس (من يوتيوب بأربعة اتصالات متوازية، أو من رابطه المباشر) ويُرفع الدرس السابق في الوقت نفسه إلى خادم البيانات (NAS)، ثم يُحذف من الهاتف ويصير الخادم مصدره عند كل المستخدمين. يستمر النقل في الخلفية بإشعار، ويبقى ظاهرًا في شريط أسفل التطبيق وفي شاشة التنزيلات، ويُستأنف من حيث توقف حتى بعد إغلاق التطبيق. بعد الاكتمال تُنشر التغييرات للجميع تلقائيًا.", style = MaterialTheme.typography.bodySmall)
                    Text("الدروس خارج الخادم: ${ArabicText.arabicDigits(pending.size)} — على الخادم: ${ArabicText.arabicDigits(onServer)}" + (if (!sync.isAdmin) " — يلزم دخول مشرف" else ""), style = MaterialTheme.typography.labelMedium)
                    if (!st.running) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (q in listOf(YouTubeMedia.QUALITY_AUDIO, YouTubeMedia.QUALITY_VIDEO_SD, YouTubeMedia.QUALITY_VIDEO_HD)) {
                            FilterChip(selected = quality == q, onClick = { quality = q; app.settings.update { it.copy(lastImportQuality = q) } }, label = { Text(YouTubeMedia.qualityLabel(q)) })
                        }
                    } else Text("الجودة: ${YouTubeMedia.qualityLabel(st.quality)}", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!st.running) {
                            if (st.items.isNotEmpty() && st.pendingCount > 0 && sync.isAdmin) Button(onClick = { job.retryPending() }) { Text("متابعة النقل (${ArabicText.arabicDigits(st.pendingCount)})") }
                            else if (st.items.isNotEmpty() && st.errCount > 0 && sync.isAdmin) Button(onClick = { job.retryPending() }) { Text("إعادة ما تعذر (${ArabicText.arabicDigits(st.errCount)})") }
                            else Button(enabled = pending.isNotEmpty() && sync.isAdmin, onClick = { job.clear(); job.start(pending, subDir, quality, effSheekh, effBook, bookName) }) { Text("ابدأ النقل (${ArabicText.arabicDigits(pending.size)})") }
                        } else OutlinedButton(onClick = { job.cancel() }) { Text("إيقاف مؤقت") }
                        if (!st.running && st.items.isNotEmpty()) TextButton(onClick = { job.clear() }) { Text("مسح القائمة") }
                    }
                    if (st.items.isNotEmpty()) {
                        LinearProgressIndicator(progress = { st.doneCount.toFloat() / st.items.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        Text(transferSummary(st), style = MaterialTheme.typography.labelMedium)
                        Text("نُقل ${ArabicText.formatSize(st.bytesDone)}" + (if (elapsed > 0) " في ${ArabicText.formatTime(elapsed * 1000)}" else "") + (if (st.resumed) " — استُؤنف تلقائيًا" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (st.items.isEmpty()) {
                LazyColumn(Modifier.weight(1f)) {
                    items(chapters, key = { it.code }) { ch ->
                        ListItem(headlineContent = { Text(ch.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(if (ch.isOnServer) "على الخادم" else if (ch.isYouTube) "يوتيوب — سيُنقل" else if (ch.needsTransfer) "رابط مباشر — سيُنقل" else "ملف محلي", style = MaterialTheme.typography.labelSmall) })
                        HorizontalDivider()
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(st.items, key = { it.code }) { it ->
                        ListItem(
                            headlineContent = { Text(it.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column {
                                    Text(it.state + (if (it.phase in 1..2 && it.total > 0) " — ${ArabicText.formatSize(it.bytes)} / ${ArabicText.formatSize(it.total)}" else ""), style = MaterialTheme.typography.labelSmall, color = if (it.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (it.phase in 1..2 && it.total > 0) LinearProgressIndicator(progress = { it.bytes.toFloat() / it.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
                if (!st.running && st.finishedAt > 0) {
                    Text((if (st.published) st.publishMessage else if (st.publishMessage.isNotBlank()) st.publishMessage else if (st.okCount > 0) "انشر التغييرات للجميع ليُشغَّل من الخادم عند كل المستخدمين." else ""), style = MaterialTheme.typography.bodySmall, color = if (st.published || st.publishMessage.isBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!st.published && st.okCount > 0) Button(onClick = { nav.navigate(Routes.MANAGE) }) { Text("إلى النشر") }
                        if (effSheekh != 0 && effBook != 0) OutlinedButton(onClick = { nav.navigate(Routes.autoBatch(effSheekh, effBook)) }) { Text("فهرسة تلقائية") }
                    }
                }
            }
        }
    }
}
