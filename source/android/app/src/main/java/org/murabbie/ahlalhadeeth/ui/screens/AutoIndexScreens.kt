package org.murabbie.ahlalhadeeth.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import org.murabbie.ahlalhadeeth.data.AutoIndexJob
import org.murabbie.ahlalhadeeth.data.AutoIndexer
import org.murabbie.ahlalhadeeth.data.Chapter
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.Segment
import org.murabbie.ahlalhadeeth.data.autoIndexSummary
import org.murabbie.ahlalhadeeth.ui.AppTopBar

private fun pct(p: Float) = ArabicText.arabicDigits((p.coerceIn(0f, 1f) * 100).toInt()) + "٪"

/** بطاقة تقدّم عملية التفريغ: شريط ونسبة مئوية والحالة، وأزرار الإيقاف/المتابعة */
@Composable
fun AutoIndexProgressCard(app: App, st: AutoIndexJob.State, nav: NavHostController?, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st.running) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Text(autoIndexSummary(st), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (st.running) Text(pct(st.overall), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(progress = { st.overall.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            if (st.quotaStop && st.quotaMessage.isNotBlank()) Text(st.quotaMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (st.running) OutlinedButton(onClick = { app.autoIndex.cancel() }) { Text("إيقاف") }
                else if (st.pendingCount > 0) Button(onClick = { app.autoIndex.resume() }) { Text("متابعة (${ArabicText.arabicDigits(st.pendingCount)})") }
                if (!st.running && st.items.isNotEmpty()) TextButton(onClick = { app.autoIndex.clear() }) { Text("مسح") }
                if (nav != null && st.items.isNotEmpty()) TextButton(onClick = { nav.navigate(org.murabbie.ahlalhadeeth.ui.Routes.autoBatch(st.sheekhId, st.bookId)) { launchSingleTop = true } }) { Text("التفاصيل") }
            }
        }
    }
}

/**
 * الفهرسة والتفريغ التلقائيان لدرس واحد: تُنفَّذ عبر طابور التفريغ (تبقى بعد مغادرة الشاشة وتظهر نسبتها في الشريط السفلي)،
 * وتُعرض المواضع الناتجة من الدرس بعد اكتمالها.
 */
@Composable
fun AutoIndexScreen(app: App, repo: Repository, nav: NavHostController, code: Int) {
    val job = app.autoIndex
    val st by job.state.collectAsState()
    val sync by app.sharedSync.state.collectAsState()
    val contentVersion by app.userDb.content.version.collectAsState()
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    var existing by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var needConfirm by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    val item = st.items.firstOrNull { it.code == code }
    val itemRunning = st.running && item != null && !item.done

    fun begin() {
        val ch = chapter ?: return
        started = true; needConfirm = false
        job.start(listOf(ch), replace = true, bookName = ch.bookName)
    }
    LaunchedEffect(code) {
        chapter = repo.chapter(code)
        existing = repo.segments(code)
        val ch = chapter
        if (ch != null && !started) {
            val inQueue = st.items.any { it.code == code && !it.done }
            if (inQueue) started = true
            else if (existing.isNotEmpty()) needConfirm = true
            else begin()
        }
    }
    LaunchedEffect(contentVersion, item?.done) { existing = repo.segments(code) }

    val ch = chapter
    Scaffold(topBar = { AppTopBar("فهرسة وتفريغ تلقائي", nav, subtitle = ch?.displayTitle) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (sync.hasGemini) "يُفرَّغ صوت الدرس نفسه بالذكاء الاصطناعي (Gemini) تفريغًا كاملًا، ثم يُقسَّم مواضع بالدقيقة والثانية بعناوينها وأرقام الأحاديث والأسئلة كما في فهارس الأشرطة، مع نص التفريغ تحت كل موضع. تستمر العملية إن غادرت الشاشة، ونسبتها تظهر في الشريط السفلي."
                        else AutoIndexer.KEY_HINT + (if (ch?.isYouTube == true) ". بلا مفتاح يُكتفى بتفريغ يوتيوب إن وُجد (أضعف)." else "."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (needConfirm) {
                        Text("لهذا الدرس فهرس من ${ArabicText.arabicDigits(existing.size)} موضع. التفريغ التلقائي يستبدله كله.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { begin() }) { Text("استبدال الفهرس بالتفريغ التلقائي") }
                            OutlinedButton(onClick = { nav.popBackStack() }) { Text("رجوع") }
                        }
                    } else if (item != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (itemRunning) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                            Text(item.state, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), color = if (item.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            if (!item.done) Text(pct(item.progress), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        LinearProgressIndicator(progress = { if (item.done && item.error == null) 1f else item.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        if (st.running && !itemRunning && !item.done) Text("في الطابور: ينتظر انتهاء الدرس الجاري (${autoIndexSummary(st)})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (itemRunning) TextButton(onClick = { job.cancel() }) { Text("إيقاف") }
                            else if (item.done) OutlinedButton(onClick = { begin() }) { Text("إعادة التفريغ") }
                            else if (st.quotaStop || !st.running) Button(onClick = { job.resume() }) { Text("متابعة") }
                        }
                        if (st.quotaStop && st.quotaMessage.isNotBlank()) Text(st.quotaMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else if (!started && !needConfirm) {
                        Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp) }
                    }
                }
            }
            if (existing.isNotEmpty() && (item == null || item.done)) {
                Text("${ArabicText.arabicDigits(existing.size)} موضع في الدرس" + (if (item?.done == true && item.error == null) " — من التفريغ التلقائي" else ""), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(existing) { i, s ->
                        ListItem(
                            headlineContent = { Text("${ArabicText.arabicDigits(i + 1)}. ${s.line}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(ArabicText.formatTime(s.offsetStart) + (if (s.ques) " — سؤال" else "") + (if (s.hnum > 0) " — حديث ${ArabicText.arabicDigits(s.hnum)}" else ""), style = MaterialTheme.typography.labelSmall) },
                        )
                        HorizontalDivider()
                    }
                }
                Text("راجع العناوين (ضغطة مطوّلة على الموضع للتعديل)، ثم انشر التغييرات للجميع.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Spacer(Modifier.height(8.dp))
        }
    }
}

/** فهرسة تلقائية لدفعة دروس يختارها المستخدم (أو السلسلة كلها) في الخلفية مع نسبة مئوية لكل درس وإلغاء ومتابعة */
@Composable
fun AutoIndexBatchScreen(app: App, repo: Repository, nav: NavHostController, sheekhId: Int, bookId: Int, autoStart: Boolean) {
    val job = app.autoIndex
    val st by job.state.collectAsState()
    val sync by app.sharedSync.state.collectAsState()
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var bookName by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(false) }
    var onlyYouTube by remember { mutableStateOf(!sync.hasGemini) }
    var started by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectionTouched by remember { mutableStateOf(false) }
    val contentVersion by app.userDb.content.version.collectAsState()

    LaunchedEffect(sheekhId, bookId, contentVersion) {
        chapters = repo.chapters(sheekhId, bookId)
        bookName = repo.book(bookId)?.name ?: chapters.firstOrNull()?.bookName ?: ""
    }
    val candidates = chapters.filter { (replace || it.segCount == 0) && (!onlyYouTube || it.isYouTube) }
    // الافتراضي: كل المرشحين محدَّدون حتى يغيّر المستخدم
    LaunchedEffect(candidates) { if (!selectionTouched) selected = candidates.map { it.code }.toSet() }
    val chosen = candidates.filter { it.code in selected }
    LaunchedEffect(chapters, autoStart) {
        if (autoStart && !started && chapters.isNotEmpty() && !st.running) { started = true; job.start(candidates, replace, bookName) }
    }
    val showQueue = st.items.isNotEmpty()

    Scaffold(topBar = { AppTopBar("تفريغ الدروس تلقائيًا", nav, subtitle = bookName.ifBlank { st.bookName }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("تُفرَّغ الدروس المحدَّدة درسًا درسًا: تفريغ صوت الدرس بالذكاء الاصطناعي (Gemini) ثم استنباط المواضيع بالأزمنة مع نصوصها وإضافتها (تفريغ يوتيوب بديل فقط عند غياب المفتاح). تستمر العملية في الخلفية أثناء تنقلك في التطبيق وبعد مغادرته، ونسبتها تظهر في الشريط السفلي.", style = MaterialTheme.typography.bodySmall)
                    if (!showQueue) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = replace, onCheckedChange = { replace = it; selectionTouched = false }); Text("إظهار الدروس المفهرسة أيضًا (يُستبدل فهرسها)", style = MaterialTheme.typography.bodySmall) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = onlyYouTube, onCheckedChange = { onlyYouTube = it; selectionTouched = false }, enabled = sync.hasGemini); Text("دروس يوتيوب فقط" + (if (!sync.hasGemini) " (تفريغ الصوت يحتاج إلى مفتاح Gemini)" else ""), style = MaterialTheme.typography.bodySmall) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = chosen.isNotEmpty(), onClick = { job.clear(); job.start(chosen, replace, bookName) }) { Text("ابدأ (${ArabicText.arabicDigits(chosen.size)} درس)") }
                            TextButton(onClick = { selectionTouched = true; selected = if (selected.size == candidates.size) emptySet() else candidates.map { it.code }.toSet() }) { Text(if (selected.size == candidates.size && candidates.isNotEmpty()) "إلغاء التحديد" else "تحديد الكل") }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (st.running) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                            Text(autoIndexSummary(st), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            if (st.running) Text(pct(st.overall), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        LinearProgressIndicator(progress = { st.overall.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        if (st.quotaStop && st.quotaMessage.isNotBlank()) Text(st.quotaMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (st.running) OutlinedButton(onClick = { job.cancel() }) { Text("إيقاف") }
                            else if (st.pendingCount > 0) Button(onClick = { job.resume() }) { Text("متابعة (${ArabicText.arabicDigits(st.pendingCount)})") }
                            if (!st.running) TextButton(onClick = { job.clear() }) { Text("مسح النتائج") }
                        }
                    }
                }
            }
            if (!showQueue) {
                Text(if (candidates.isEmpty()) "لا دروس مرشحة (كلها مفهرسة، أو ليست من يوتيوب)." else "اختر الدروس (${ArabicText.arabicDigits(chosen.size)} من ${ArabicText.arabicDigits(candidates.size)} محدَّد):", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f)) {
                    items(candidates, key = { it.code }) { ch ->
                        val checked = ch.code in selected
                        ListItem(
                            leadingContent = { Checkbox(checked = checked, onCheckedChange = { on -> selectionTouched = true; selected = if (on) selected + ch.code else selected - ch.code }) },
                            headlineContent = { Text(ch.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text((if (ch.isYouTube) "يوتيوب" else "صوت/فيديو") + (if (ch.segCount > 0) " — له فهرس (${ArabicText.arabicDigits(ch.segCount)})" else ""), style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.clickable { selectionTouched = true; selected = if (checked) selected - ch.code else selected + ch.code },
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(st.items, key = { _, it -> it.code }) { i, it ->
                        val active = st.running && i == st.current
                        ListItem(
                            headlineContent = { Text(it.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column {
                                    Text(it.state, style = MaterialTheme.typography.labelSmall, color = if (it.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (active) LinearProgressIndicator(progress = { it.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                }
                            },
                            trailingContent = { if (active) Text(pct(it.progress), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) else if (it.done && it.error == null) Text("✓", color = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { nav.navigate(org.murabbie.ahlalhadeeth.ui.Routes.autoIndex(it.code)) },
                        )
                        HorizontalDivider()
                    }
                }
                if (!st.running && st.finishedAt > 0) Text(
                    if (st.quotaStop) "توقف التفريغ: ${ArabicText.arabicDigits(st.okCount)} درس فُرّغ، وبقي ${ArabicText.arabicDigits(st.pendingCount)} بانتظار تجدد حصة Gemini اليومية (نحو الساعة ٧ صباحًا بتوقيت غرينتش) — اضغط «متابعة» حينها."
                    else "انتهت الدفعة: ${ArabicText.arabicDigits(st.okCount)} درس فُرّغ، ${ArabicText.arabicDigits(st.errCount)} تعذر. راجع الفهارس ثم انشر التغييرات للجميع.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
