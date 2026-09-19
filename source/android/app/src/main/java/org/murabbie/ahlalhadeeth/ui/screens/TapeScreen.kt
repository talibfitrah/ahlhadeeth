package org.murabbie.ahlalhadeeth.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.Category
import org.murabbie.ahlalhadeeth.data.Chapter
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.Segment
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.Loading
import org.murabbie.ahlalhadeeth.ui.Routes
import org.murabbie.ahlalhadeeth.ui.SegmentRow
import org.murabbie.ahlalhadeeth.ui.openMedia

/** شاشة الشريط: فهرس المقاطع مع القفز إلى الزمن، والمزامنة مع المشغّل */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapeScreen(app: App, repo: Repository, nav: NavHostController, code: Int, initialSeq: Int) {
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    var segments by remember { mutableStateOf<List<Segment>?>(null) }
    var bookChapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    val favVersion by app.userDb.favoritesVersion.collectAsState()
    var favIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val playerState by app.player.state.collectAsState()
    val downloadsVersion by app.userDb.downloadsVersion.collectAsState()
    var isDownloaded by remember { mutableStateOf(false) }
    var menuSegment by remember { mutableStateOf<Segment?>(null) }
    var menuCategories by remember { mutableStateOf<List<Category>>(emptyList()) }
    val settings by app.settings.state.collectAsState()
    var showTranscripts by remember { mutableStateOf(settings.showTranscriptInline) }
    var writes by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val contentVersion by app.userDb.content.version.collectAsState()
    val syncState by app.sharedSync.state.collectAsState()
    val canEdit = syncState.isAdmin && code < 0
    LaunchedEffect(code, contentVersion) {
        val ch = repo.chapter(code)
        chapter = ch
        segments = repo.segments(code)
        if (ch != null) bookChapters = repo.chapters(ch.sheekhId, ch.bookId)
        if (initialSeq > 0) {
            val idx = segments?.indexOfFirst { it.seq == initialSeq } ?: -1
            if (idx >= 0) listState.scrollToItem(idx)
        }
    }
    LaunchedEffect(favVersion) { favIds = app.userDb.favoriteIds() }
    LaunchedEffect(downloadsVersion, chapter) { chapter?.let { isDownloaded = app.audioDownloads.audioSource.isDownloaded(it) } }
    LaunchedEffect(menuSegment) { menuSegment?.let { menuCategories = repo.categoriesOfSegment(it.id) } }
    LaunchedEffect(code, showTranscripts, contentVersion) { writes = if (showTranscripts) repo.writesOfChapter(code) else emptyMap() }

    val ch = chapter
    val isCurrentTape = playerState.chapter?.code == code
    // المقطع الجاري بحسب موضع التشغيل
    val currentIndex: Int = remember(playerState.positionMs, isCurrentTape, segments) {
        val segs = segments ?: return@remember -1
        if (!isCurrentTape) return@remember -1
        var idx = -1
        for (i in segs.indices) {
            if (segs[i].offsetStart <= playerState.positionMs) idx = i else break
        }
        idx
    }

    Scaffold(floatingActionButton = {
        if (ch != null && canEdit) androidx.compose.material3.ExtendedFloatingActionButton(onClick = { if (ch.isYouTube && !isCurrentTape) nav.navigate(Routes.yt(code)) else nav.navigate(Routes.editSegment(code)) }) { Text("+ موضع") }
    }, topBar = {
        AppTopBar(
            title = ch?.let { if (it.isUser) it.displayTitle else "${it.displayTitle} (${it.fileName})" } ?: "",
            nav = nav,
            subtitle = ch?.let { "${it.sheekhName} — ${it.bookName}" },
            actions = {
                if (ch != null) {
                    if (canEdit) {
                        IconButton(onClick = { nav.navigate(Routes.autoIndex(code)) }) { Icon(Icons.Filled.AutoAwesome, contentDescription = "فهرسة وتفريغ تلقائي") }
                        IconButton(onClick = { nav.navigate(Routes.pasteIndex(code)) }) { Icon(Icons.Filled.ContentPaste, contentDescription = "لصق فهرس نصي") }
                        IconButton(onClick = { nav.navigate(Routes.editTape(ch.sheekhId, ch.bookId, ch.code)) }) { Icon(Icons.Filled.Edit, contentDescription = "تعديل الدرس") }
                    }
                    if (ch.writeCount > 0) {
                        IconButton(onClick = { showTranscripts = !showTranscripts }) {
                            Icon(if (showTranscripts) Icons.Filled.Article else Icons.Outlined.Article, contentDescription = if (showTranscripts) "إخفاء التفريغ" else "عرض التفريغ مع الفهرس", tint = if (showTranscripts) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { nav.navigate(Routes.tapeText(ch.code)) }) { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "التفريغ الكامل للشريط") }
                    }
                    IconButton(onClick = { openMedia(app, nav, ch, 0, bookChapters) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "تشغيل الشريط من أوله") }
                    if (isDownloaded) Icon(Icons.Filled.DownloadDone, contentDescription = "منزَّل", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
                    else if (!ch.isUser || (ch.mediaUri.startsWith("http", true) && !ch.isYouTube)) IconButton(onClick = { app.audioDownloads.enqueue(listOf(ch)) }) { Icon(Icons.Filled.Download, contentDescription = "تنزيل الشريط") }
                }
            },
        )
    }) { padding ->
        val segs = segments
        if (ch == null || segs == null) {
            Loading(Modifier.padding(padding)); return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                val parts = ArrayList<String>()
                parts.add("${ArabicText.arabicDigits(segs.size)} مقطع")
                if (ch.writeCount > 0) parts.add("${ArabicText.arabicDigits(ch.writeCount)} تفريغ")
                if (ch.fileSize > 0) parts.add(ArabicText.formatSize(ch.fileSize))
                if (ch.cdNumber.isNotBlank()) parts.add(ch.cdNumber)
                Text(parts.joinToString(" — "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            if (segs.isEmpty() && canEdit) {
                Text("لا مواضع بعد. اضغط أيقونة ✦ (فهرسة وتفريغ تلقائي) ليستخرج التطبيق المواضيع بأزمنتها وتفريغها، أو شغّل الدرس واضغط «+ موضع» عند كل موضوع، أو الصق فهرسًا نصيًا من الأيقونة أعلى.", Modifier.padding(16.dp))
            } else if (segs.isEmpty() && ch.isUser) {
                Text("لا فهرس لهذا الدرس بعد؛ يمكنك تشغيله من أوله.", Modifier.padding(16.dp))
            } else if (segs.isEmpty()) {
                Text("لا يوجد فهرس لهذا الشريط؛ يمكنك تشغيله من أوله.", Modifier.padding(16.dp))
            } else if (ch.writeCount == 0 && !ch.isUser) {
                Text("لا يوجد تفريغ نصي لهذا الشريط في بيانات البرنامج؛ الفهرس متاح والصوت يُشغَّل من كل مقطع.", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (showTranscripts) {
                Text("التفريغ معروض تحت كل مقطع (${ArabicText.arabicDigits(ch.writeCount)} من ${ArabicText.arabicDigits(segs.size)}) — اضغط أيقونة التفريغ لإخفائه", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(segs, key = { _, s -> s.id }) { i, s ->
                    SegmentRow(
                        segment = s,
                        isCurrent = i == currentIndex,
                        isFavorite = s.id in favIds,
                        transcript = if (showTranscripts) writes[s.id] else null,
                        onClick = { openMedia(app, nav, ch, s.offsetStart, bookChapters) },
                        onLongClick = { menuSegment = s },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    val ms = menuSegment
    if (ms != null && ch != null) {
        ModalBottomSheet(onDismissRequest = { menuSegment = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(ms.line, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("تشغيل من هذا المقطع (${ArabicText.formatTime(ms.offsetStart)})") },
                    leadingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    modifier = Modifier.clickableRow { openMedia(app, nav, ch, ms.offsetStart, bookChapters); menuSegment = null },
                )
                if (canEdit) ListItem(
                    headlineContent = { Text("تعديل الموضع (الزمن والعنوان والتفريغ والتصانيف)") },
                    leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    modifier = Modifier.clickableRow { nav.navigate(Routes.editSegment(code, ms.id)); menuSegment = null },
                )
                if (ms.hasWrite) ListItem(
                    headlineContent = { Text("عرض التفريغ النصي") },
                    leadingContent = { Icon(Icons.Filled.Article, contentDescription = null) },
                    modifier = Modifier.clickableRow { nav.navigate(Routes.transcript(ms.id)); menuSegment = null },
                )
                val isFav = ms.id in favIds
                ListItem(
                    headlineContent = { Text(if (isFav) "إزالة من المفضلة" else "إضافة إلى المفضلة") },
                    leadingContent = { Icon(if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = null) },
                    modifier = Modifier.clickableRow {
                        scope.launch { if (isFav) app.userDb.removeFavorite(ms.id) else app.userDb.addFavorite(ms) }
                        menuSegment = null
                    },
                )
                ListItem(
                    headlineContent = { Text("نسخ النص") },
                    leadingContent = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    modifier = Modifier.clickableRow {
                        scope.launch {
                            val w = if (ms.hasWrite) repo.write(ms.id) else null
                            clipboard.setText(AnnotatedString(shareText(ch, ms, w)))
                        }
                        menuSegment = null
                    },
                )
                ListItem(
                    headlineContent = { Text("مشاركة") },
                    leadingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
                    modifier = Modifier.clickableRow {
                        scope.launch {
                            val w = if (ms.hasWrite) repo.write(ms.id) else null
                            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(ch, ms, w))
                            context.startActivity(Intent.createChooser(i, "مشاركة"))
                        }
                        menuSegment = null
                    },
                )
                if (menuCategories.isNotEmpty()) {
                    HorizontalDivider()
                    Text("التصانيف الفقهية لهذا المقطع", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    menuCategories.forEach { c ->
                        ListItem(
                            headlineContent = { Text(c.name) },
                            leadingContent = { Icon(Icons.Outlined.AccountTree, contentDescription = null) },
                            modifier = Modifier.clickableRow { nav.navigate(Routes.category(c.id)); menuSegment = null },
                        )
                    }
                }
                TextButton(onClick = { menuSegment = null }, Modifier.padding(horizontal = 16.dp)) { Text("إغلاق") }
            }
        }
    }
}

fun shareText(ch: Chapter, s: Segment, write: String?): String {
    val sb = StringBuilder()
    sb.append(ch.sheekhName).append(" — ").append(ch.bookName).append(" — ").append(ch.displayTitle).append(" (").append(ch.fileName).append(")\n")
    sb.append("الموضع: ").append(ArabicText.formatTime(s.offsetStart)).append('\n')
    sb.append(s.line).append('\n')
    if (!write.isNullOrBlank()) sb.append('\n').append(write).append('\n')
    sb.append("\n(من برنامج أهل الحديث والأثر — alathar.net)")
    return sb.toString()
}

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
