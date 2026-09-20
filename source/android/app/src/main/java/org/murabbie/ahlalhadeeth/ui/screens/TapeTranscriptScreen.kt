package org.murabbie.ahlalhadeeth.ui.screens

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.Chapter
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.Segment
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.openMedia
import org.murabbie.ahlalhadeeth.ui.Loading
import org.murabbie.ahlalhadeeth.ui.highlighted
import java.io.File

/**
 * التفريغ الكامل للشريط: نص متصل بعناوين المقاطع وأزمنتها، متزامن مع المشغّل،
 * مع بحث داخل النص وتكبير الخط والنسخ والمشاركة والتصدير ملفًا نصيًا.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TapeTranscriptScreen(app: App, repo: Repository, nav: NavHostController, code: Int, initialSeq: Int, initialQuery: String) {
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    var segments by remember { mutableStateOf<List<Segment>?>(null) }
    var writes by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var fontSize by remember { mutableStateOf(19f) }
    var query by remember { mutableStateOf(initialQuery) }
    var showSearch by remember { mutableStateOf(initialQuery.isNotBlank()) }
    var follow by remember { mutableStateOf(true) }
    var matchCursor by remember { mutableStateOf(0) }
    val playerState by app.player.state.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    LaunchedEffect(code) {
        chapter = repo.chapter(code)
        val segs = repo.segments(code)
        segments = segs
        writes = repo.writesOfChapter(code)
        if (initialSeq > 0) {
            val idx = segs.indexOfFirst { it.seq == initialSeq }
            if (idx >= 0) listState.scrollToItem(idx)
        }
    }

    val ch = chapter
    val segs = segments
    val words = remember(query) { ArabicText.queryWords(query) }
    // المقاطع التي فيها تطابق مع كلمات البحث
    val matchIndices = remember(words, segs, writes) {
        if (words.isEmpty() || segs == null) emptyList() else segs.indices.filter { i ->
            val s = segs[i]
            ArabicText.findMatches(s.line, words).isNotEmpty() || (writes[s.id]?.let { ArabicText.findMatches(it, words).isNotEmpty() } ?: false)
        }
    }
    LaunchedEffect(matchIndices) {
        matchCursor = 0
        if (matchIndices.isNotEmpty()) {
            follow = false
            listState.animateScrollToItem(matchIndices[0])
        }
    }

    val isCurrentTape = playerState.chapter?.code == code
    val currentIndex = remember(playerState.positionMs, isCurrentTape, segs) {
        if (!isCurrentTape || segs == null) -1 else {
            var idx = -1
            for (i in segs.indices) if (segs[i].offsetStart <= playerState.positionMs) idx = i else break
            idx
        }
    }
    LaunchedEffect(currentIndex, follow) {
        if (follow && currentIndex >= 0 && playerState.isPlaying && !listState.isScrollInProgress) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    fun fullText(): String {
        val c = ch ?: return ""
        val sb = StringBuilder()
        sb.append(c.sheekhName).append(" — ").append(c.bookName).append(" — ").append(c.displayTitle).append(" (").append(c.fileName).append(")\n")
        sb.append("التفريغ الكامل للشريط — من برنامج أهل الحديث والأثر (alathar.net)\n\n")
        segs?.forEach { s ->
            sb.append("[").append(ArabicText.formatTime(s.offsetStart)).append("] ").append(s.line).append('\n')
            writes[s.id]?.let { sb.append(it).append("\n\n") }
        }
        return sb.toString()
    }

    fun exportFile() {
        val c = ch ?: return
        scope.launch {
            runCatching {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val safeName = "${c.sheekhName} - ${c.bookName} - ${c.displayTitle} (${c.fileName})".replace(Regex("[\\\\/:*?\"<>|]"), "-").take(80) // حد اسم الملف ٢٥٥ بايت والحرف العربي بايتان
                val f = File(dir, "$safeName.txt")
                f.writeText(fullText())
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, safeName).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(i, "حفظ التفريغ أو مشاركته"))
            }
        }
    }

    Scaffold(topBar = {
        AppTopBar(
            title = ch?.let { "تفريغ: ${it.displayTitle} (${it.fileName})" } ?: "التفريغ الكامل",
            nav = nav,
            subtitle = ch?.let { "${it.sheekhName} — ${it.bookName}" },
            actions = {
                IconButton(onClick = { showSearch = !showSearch; if (!showSearch) query = "" }) { Icon(Icons.Filled.Search, contentDescription = "بحث في التفريغ") }
                IconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(12f) }) { Icon(Icons.Filled.TextDecrease, contentDescription = "تصغير الخط") }
                IconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(40f) }) { Icon(Icons.Filled.TextIncrease, contentDescription = "تكبير الخط") }
            },
        )
    }) { padding ->
        if (ch == null || segs == null) {
            Loading(Modifier.padding(padding)); return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showSearch) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("كلمة للبحث داخل التفريغ") },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "مسح") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { }),
                )
                if (words.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (matchIndices.isEmpty()) "لا تطابق" else "تطابق في ${ArabicText.arabicDigits(matchIndices.size)} مقطع — ${ArabicText.arabicDigits(matchCursor + 1)}", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        IconButton(enabled = matchIndices.isNotEmpty(), onClick = {
                            matchCursor = (matchCursor - 1 + matchIndices.size) % matchIndices.size
                            scope.launch { listState.animateScrollToItem(matchIndices[matchCursor]) }
                        }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "السابق") }
                        IconButton(enabled = matchIndices.isNotEmpty(), onClick = {
                            matchCursor = (matchCursor + 1) % matchIndices.size
                            scope.launch { listState.animateScrollToItem(matchIndices[matchCursor]) }
                        }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "التالي") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("متابعة التشغيل") })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(AnnotatedString(fullText())) }) { Icon(Icons.Filled.ContentCopy, contentDescription = "نسخ التفريغ كله") }
                IconButton(onClick = {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, fullText())
                    context.startActivity(Intent.createChooser(i, "مشاركة التفريغ"))
                }) { Icon(Icons.Filled.Share, contentDescription = "مشاركة") }
                IconButton(onClick = { exportFile() }) { Icon(Icons.Filled.Save, contentDescription = "تصدير ملفًا نصيًا") }
            }
            Text(
                "${ArabicText.arabicDigits(writes.size)} تفريغًا من ${ArabicText.arabicDigits(segs.size)} مقطعًا — اضغط عنوان المقطع لتشغيله من موضعه، ومطوّلًا لنسخه",
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            if (writes.isEmpty()) {
                Text("لا يوجد تفريغ نصي لهذا الشريط في بيانات البرنامج.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(segs, key = { _, s -> s.id }) { i, s ->
                    val w = writes[s.id]
                    val isCur = i == currentIndex
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isCur) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(
                                onClick = { follow = true; openMedia(app, nav, ch, s.offsetStart) },
                                onLongClick = { clipboard.setText(AnnotatedString("[${ArabicText.formatTime(s.offsetStart)}] ${s.line}\n${w ?: ""}")) },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "تشغيل من ${ArabicText.formatTime(s.offsetStart)}", tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    highlighted(s.line, words),
                                    style = MaterialTheme.typography.titleSmall.copy(fontSize = (fontSize + 1).sp, lineHeight = (fontSize * 1.7f).sp),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    ArabicText.formatTime(s.offsetStart) + (if (s.hnum > 0) " — حديث ${ArabicText.arabicDigits(s.hnum)}" else "") + (if (s.ques) " — سؤال" else ""),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (w != null) {
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(highlighted(w, words), style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.8f).sp))
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
}
