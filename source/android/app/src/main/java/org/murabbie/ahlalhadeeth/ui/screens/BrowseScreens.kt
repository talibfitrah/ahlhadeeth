package org.murabbie.ahlalhadeeth.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.Chapter
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.Sheekh
import org.murabbie.ahlalhadeeth.data.SheekhBook
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.ConfirmCard
import org.murabbie.ahlalhadeeth.ui.InlineFormCard
import org.murabbie.ahlalhadeeth.ui.EmptyState
import org.murabbie.ahlalhadeeth.ui.Loading
import org.murabbie.ahlalhadeeth.ui.Routes
import org.murabbie.ahlalhadeeth.ui.openTape
import org.murabbie.ahlalhadeeth.ui.openChapter
import org.murabbie.ahlalhadeeth.ui.openMedia

/** الشاشة الرئيسية: المشايخ */
@Composable
fun HomeScreen(app: App, repo: Repository, nav: NavHostController) {
    var sheekhs by remember { mutableStateOf<List<Sheekh>?>(null) }
    var query by remember { mutableStateOf("") }
    var chapterHits by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    val settings by app.settings.state.collectAsState()
    var lastChapter by remember { mutableStateOf<Chapter?>(null) }
    val update by app.dataManager.updateAvailable.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val contentVersion by app.userDb.content.version.collectAsState()
    val syncState by app.sharedSync.state.collectAsState()
    val isAdmin = syncState.isAdmin
    LaunchedEffect(contentVersion, settings.lastPlayedCode) {
        sheekhs = repo.sheekhs()
        if (settings.lastPlayedCode != 0) lastChapter = repo.chapter(settings.lastPlayedCode)
    }
    LaunchedEffect(query) {
        chapterHits = if (query.trim().length >= 2) repo.searchChapters(query.trim(), 100) else emptyList()
    }

    Scaffold(topBar = {
        AppTopBar("أهل الحديث والأثر", subtitle = "المشايخ والسلاسل العلمية", actions = {
            IconButton(onClick = { nav.navigate(Routes.HISTORY) }) { Icon(Icons.Filled.History, contentDescription = "سجل الاستماع") }
        })
    }) { padding ->
        val list = sheekhs
        if (list == null) {
            Loading(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    placeholder = { Text("بحث في عناوين الأشرطة وأسماء الملفات") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                )
            }
            if (chapterHits.isNotEmpty()) {
                items(chapterHits, key = { "c" + it.code }) { ch ->
                    ListItem(
                        headlineContent = { Text("${ch.displayTitle} (${ch.fileName})", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${ch.sheekhName} — ${ch.bookName}", style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.clickable { nav.openChapter(ch) },
                    )
                    HorizontalDivider()
                }
            } else {
                val upd = update
                if (upd != null && query.isBlank()) {
                    item {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("يتوفر إصدار جديد من التطبيق: ${upd.versionName}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                if (upd.notes.isNotBlank()) Text(upd.notes, style = MaterialTheme.typography.bodySmall)
                                Row {
                                    androidx.compose.material3.TextButton(onClick = {
                                        val url = upd.downloadPage
                                        if (url.isNotBlank()) runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                                    }) { Text("تنزيل التحديث") }
                                    androidx.compose.material3.TextButton(onClick = { app.dataManager.dismissUpdate() }) { Text("لاحقًا") }
                                }
                            }
                        }
                    }
                }
                val last = lastChapter
                if (last != null && query.isBlank()) {
                    item {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { if (last.isYouTube) nav.navigate(Routes.yt(last.code, settings.lastPlayedPosition)) else nav.openTape(last.code) }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("متابعة الاستماع", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text("${last.displayTitle} (${last.fileName})", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text("${last.sheekhName} — ${last.bookName} — ${ArabicText.formatTime(settings.lastPlayedPosition)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                items(list.filter { it.id > 0 }, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name, style = MaterialTheme.typography.titleMedium) },
                        modifier = Modifier.clickable { nav.navigate(Routes.books(s.id)) },
                        leadingContent = { Text(ArabicText.arabicDigits(s.ord), color = MaterialTheme.colorScheme.outline) },
                    )
                    HorizontalDivider()
                }
                val userSheekhs = list.filter { it.id < 0 }
                if (isAdmin || userSheekhs.isNotEmpty()) item(key = "user-header") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isAdmin) "محتوى مضاف (مشرف)" else "دروس مشتركة", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { nav.navigate(Routes.MANAGE) }) { Text(if (isAdmin) (if (userSheekhs.isEmpty()) "إضافة دروس لعلماء آخرين" else "إدارة ونشر") else "المزيد") }
                    }
                }
                items(userSheekhs, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name, style = MaterialTheme.typography.titleMedium) },
                        modifier = Modifier.clickable { nav.navigate(Routes.books(s.id)) },
                        leadingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                    )
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

/** كتب/سلاسل شيخ معيّن مرتبة بحسب النوع */
@Composable
fun BooksScreen(app: App, repo: Repository, nav: NavHostController, sheekhId: Int) {
    var sheekh by remember { mutableStateOf<Sheekh?>(null) }
    var books by remember { mutableStateOf<List<SheekhBook>?>(null) }
    val isUser = sheekhId < 0
    val contentVersion by app.userDb.content.version.collectAsState()
    val syncState by app.sharedSync.state.collectAsState()
    val canEdit = isUser && syncState.isAdmin
    var addBook by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf("") }
    var editBook by remember { mutableStateOf<SheekhBook?>(null) }
    var confirmDeleteBook by remember { mutableStateOf<SheekhBook?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(sheekhId, contentVersion) {
        sheekh = repo.sheekh(sheekhId)
        books = repo.booksOfSheekh(sheekhId)
    }
    Scaffold(
        topBar = { AppTopBar(sheekh?.name ?: "", nav, subtitle = if (canEdit) "سلاسل مضافة (مشرف)" else if (isUser) "سلاسل مشتركة" else "السلاسل العلمية") },
        floatingActionButton = { if (canEdit) androidx.compose.material3.ExtendedFloatingActionButton(onClick = { newName = ""; newType = ""; addBook = true }) { Text("+ سلسلة") } },
    ) { padding ->
        val list = books
        if (list == null) {
            Loading(Modifier.padding(padding)); return@Scaffold
        }
        val editing = editBook
        val formOpen = addBook || editing != null || confirmDeleteBook != null
        if (list.isEmpty() && !formOpen) {
            EmptyState(if (canEdit) "لا سلاسل بعد — اضغط «+ سلسلة» لإضافة أول سلسلة" else if (isUser) "لا سلاسل منشورة بعد" else "لا توجد أشرطة لهذا الشيخ", Modifier.padding(padding))
            return@Scaffold
        }
        val grouped = list.groupBy { it.book.typeName }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (addBook || editing != null) item(key = "form") {
                InlineFormCard(if (editing == null) "إضافة سلسلة / كتاب" else "تعديل السلسلة", onDismiss = { addBook = false; editBook = null }, modifier = Modifier.padding(12.dp)) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("اسم السلسلة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newType, onValueChange = { newType = it }, label = { Text("النوع (عقيدة، حديث، فقه…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Button(enabled = newName.isNotBlank(), onClick = {
                            val n = newName.trim(); if (n.isEmpty()) return@Button
                            scope.launch {
                                if (editing == null) { val id = app.userDb.content.addBook(-sheekhId, n, newType); nav.navigate(Routes.chapters(sheekhId, -id)) }
                                else app.userDb.content.updateBook(-editing.book.id, n, newType)
                            }
                            addBook = false; editBook = null
                        }) { Text(if (editing == null) "إضافة ثم فتح السلسلة" else "حفظ") }
                        androidx.compose.material3.OutlinedButton(onClick = { addBook = false; editBook = null }) { Text("إلغاء") }
                    }
                }
            }
            confirmDeleteBook?.let { sb ->
                item(key = "confirm") {
                    ConfirmCard("حذف «${sb.book.name}» وكل دروسها؟", "يُحذف من عند الجميع بعد النشر.", "حذف", onConfirm = { confirmDeleteBook = null; scope.launch { app.userDb.content.deleteBook(-sb.book.id) } }, onDismiss = { confirmDeleteBook = null }, destructive = true, modifier = Modifier.padding(12.dp))
                }
            }
            grouped.forEach { (type, items) ->
                item(key = "t$type") {
                    Text(
                        type.ifBlank { "متنوعات" },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(items, key = { it.book.id }) { sb ->
                    ListItem(
                        headlineContent = { Text(sb.book.name) },
                        supportingContent = {
                            Text("${ArabicText.arabicDigits(sb.chapterCount)} ${if (isUser) "درس" else "شريط"} — ${ArabicText.arabicDigits(sb.segmentCount)} مقطع", style = MaterialTheme.typography.labelMedium)
                        },
                        trailingContent = {
                            if (canEdit) Row {
                                IconButton(onClick = { newName = sb.book.name; newType = sb.book.typeName; editBook = sb }) { Icon(Icons.Filled.Edit, contentDescription = "تعديل") }
                                IconButton(onClick = { confirmDeleteBook = sb }) { Icon(Icons.Filled.Delete, contentDescription = "حذف") }
                            }
                        },
                        modifier = Modifier.clickable { nav.navigate(Routes.chapters(sheekhId, sb.book.id)) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** أشرطة كتاب معيّن لشيخ معيّن */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ChaptersScreen(app: App, repo: Repository, nav: NavHostController, sheekhId: Int, bookId: Int) {
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var filter by remember { mutableStateOf("") }
    var confirmDownloadAll by remember { mutableStateOf(false) }
    val downloadsVersion by app.userDb.downloadsVersion.collectAsState()
    var downloaded by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val playerState by app.player.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val listState = rememberLazyListState()

    val isUser = sheekhId < 0
    val contentVersion by app.userDb.content.version.collectAsState()
    val syncState by app.sharedSync.state.collectAsState()
    val canEdit = isUser && syncState.isAdmin
    var userBookName by remember { mutableStateOf("") }
    var confirmDeleteChapter by remember { mutableStateOf<Chapter?>(null) }
    // وضع التحديد (للمشرف): اختيار عدة دروس لتفريغها دفعة واحدة بلا دخول إلى كل درس
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val autoState by app.autoIndex.state.collectAsState()
    LaunchedEffect(sheekhId, bookId, contentVersion) {
        chapters = repo.chapters(sheekhId, bookId)
        if (isUser) userBookName = repo.book(bookId)?.name ?: ""
    }
    LaunchedEffect(downloadsVersion) { downloaded = app.userDb.downloadedCodes() }

    val all = chapters
    val title = all?.firstOrNull()?.bookName ?: userBookName
    val subtitle = all?.firstOrNull()?.sheekhName ?: ""
    Scaffold(
        topBar = {
            if (selecting) AppTopBar("${ArabicText.arabicDigits(selected.size)} محدَّد", nav, subtitle = title, actions = {
                TextButton(onClick = { val shownCodes = (all ?: emptyList()).map { it.code }; selected = if (selected.containsAll(shownCodes)) emptySet() else shownCodes.toSet() }) { Text(if (all != null && selected.containsAll(all.map { it.code })) "إلغاء الكل" else "تحديد الكل") }
                TextButton(onClick = { selected = (all ?: emptyList()).filter { it.segCount == 0 }.map { it.code }.toSet() }) { Text("غير المفرَّغة") }
                IconButton(onClick = { selecting = false; selected = emptySet() }) { Icon(Icons.Filled.Close, contentDescription = "إنهاء التحديد") }
            }) else AppTopBar(title, nav, subtitle = subtitle, actions = {
                if (canEdit && all?.any { it.needsTransfer } == true) IconButton(onClick = { nav.navigate(Routes.transfer(sheekhId, bookId)) }) { Icon(Icons.Filled.CloudUpload, contentDescription = "نقل الدروس إلى الخادم") }
                if (canEdit && !all.isNullOrEmpty()) IconButton(onClick = { selecting = true }) { Icon(Icons.Filled.Checklist, contentDescription = "تحديد دروس لتفريغها") }
                if (canEdit) IconButton(onClick = { nav.navigate(Routes.autoBatch(sheekhId, bookId)) }) { Icon(Icons.Filled.AutoAwesome, contentDescription = "فهرسة وتفريغ تلقائي للسلسلة") }
                if (!isUser || (all?.any { it.mediaUri.startsWith("http", true) && !it.isYouTube } == true)) IconButton(onClick = { confirmDownloadAll = true }) { Icon(Icons.Filled.Download, contentDescription = "تنزيل كل الأشرطة") }
            })
        },
        bottomBar = {
            if (selecting) Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val chosen = (all ?: emptyList()).filter { it.code in selected }
                    val withIndex = chosen.count { it.segCount > 0 }
                    Text(if (chosen.isEmpty()) "اضغط على الدروس لتحديدها (أو ضغطة مطوّلة)" else "${ArabicText.arabicDigits(chosen.size)} درس" + (if (withIndex > 0) " — منها ${ArabicText.arabicDigits(withIndex)} له فهرس سيُستبدل" else ""), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = chosen.isNotEmpty(), onClick = {
                            app.autoIndex.start(chosen, replace = true, bookName = title)
                            selecting = false; selected = emptySet()
                            nav.navigate(Routes.autoBatch(sheekhId, bookId)) { launchSingleTop = true }
                        }) { Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (autoState.running) "إضافة إلى التفريغ الجاري" else "تفريغ المحدَّد") }
                        OutlinedButton(onClick = { selecting = false; selected = emptySet() }) { Text("إلغاء") }
                    }
                }
            }
        },
        floatingActionButton = {
            if (canEdit) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.SmallFloatingActionButton(onClick = { nav.navigate(Routes.ytImport(sheekhId, bookId)) }) { Text("يوتيوب", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium) }
                androidx.compose.material3.ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.editTape(sheekhId, bookId)) }) { Text("+ درس") }
            }
        },
    ) { padding ->
        if (all == null) {
            Loading(Modifier.padding(padding)); return@Scaffold
        }
        if (all.isEmpty() && isUser) {
            EmptyState(if (canEdit) "لا دروس بعد — اضغط «+ درس» لإضافة ملف أو رابط أو فيديو يوتيوب، أو «يوتيوب» لاستيراد قائمة تشغيل كاملة" else "لا دروس منشورة بعد", Modifier.padding(padding)); return@Scaffold
        }
        val shown = if (filter.isBlank()) all else all.filter { it.title.contains(filter) || it.fileName.contains(filter) || it.cdNumber.contains(filter) }
        Column(Modifier.fillMaxSize().padding(padding)) {
            confirmDeleteChapter?.let { ch ->
                ConfirmCard("حذف الدرس «${ch.displayTitle}» بمواضعه وتفريغاته؟", "يُحذف من عند الجميع بعد النشر.", "حذف", onConfirm = { confirmDeleteChapter = null; scope.launch { app.userDb.content.deleteChapter(-ch.code) } }, onDismiss = { confirmDeleteChapter = null }, destructive = true, modifier = Modifier.padding(12.dp))
            }
            if (confirmDownloadAll) {
                val total = all.sumOf { it.fileSize }
                ConfirmCard("تنزيل كل أشرطة الكتاب", "سيُنزَّل ${ArabicText.arabicDigits(all.size)} شريطًا (نحو ${ArabicText.formatSize(total)}) إلى مجلد التطبيق للاستماع بلا إنترنت.", "تنزيل", onConfirm = { confirmDownloadAll = false; app.audioDownloads.enqueue(all.filter { !it.isYouTube }); nav.navigate(Routes.DOWNLOADS) }, onDismiss = { confirmDownloadAll = false }, modifier = Modifier.padding(12.dp))
            }
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("تصفية الأشرطة (${ArabicText.arabicDigits(all.size)} شريط)") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(shown, key = { it.code }) { ch ->
                    val isCurrent = playerState.chapter?.code == ch.code
                    ListItem(
                        headlineContent = {
                            Text("${ch.displayTitle} (${ch.fileName})", maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        },
                        supportingContent = {
                            val parts = ArrayList<String>()
                            if (ch.isVideo) parts.add("فيديو")
                            if (ch.segCount > 0) parts.add("${ArabicText.arabicDigits(ch.segCount)} مقطع")
                            if (ch.writeCount > 0) parts.add("${ArabicText.arabicDigits(ch.writeCount)} تفريغ")
                            if (ch.fileSize > 0) parts.add(ArabicText.formatSize(ch.fileSize))
                            if (ch.cdNumber.isNotBlank()) parts.add(ch.cdNumber)
                            if (ch.isYouTube) parts.add("يوتيوب") else if (ch.isOnServer) parts.add("من الخادم") else if (ch.isUser && ch.mediaUri.startsWith("http", true)) parts.add("رابط") else if (ch.isUser) parts.add("ملف على الجهاز")
                            if (ch.isUser && ch.dirty && canEdit) parts.add("غير منشور")
                            Text(parts.joinToString(" — "), style = MaterialTheme.typography.labelMedium)
                        },
                        leadingContent = if (selecting) ({ Checkbox(checked = ch.code in selected, onCheckedChange = { on -> selected = if (on) selected + ch.code else selected - ch.code }) }) else null,
                        trailingContent = {
                            val autoItem = autoState.items.firstOrNull { it.code == ch.code && !it.done }
                            if (autoItem != null && autoState.running) {
                                // الدرس في طابور التفريغ: نسبته بدل الأزرار
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (autoState.items.indexOf(autoItem) == autoState.current) Text(ArabicText.arabicDigits((autoItem.progress * 100).toInt()) + "٪", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                    else Text("في الطابور", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else if (!selecting) Row(verticalAlignment = Alignment.CenterVertically) {
                                if (ch.code in downloaded) Icon(Icons.Filled.DownloadDone, contentDescription = "منزَّل", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                if (canEdit) IconButton(onClick = { nav.navigate(Routes.editTape(sheekhId, bookId, ch.code)) }) { Icon(Icons.Filled.Edit, contentDescription = "تعديل") }
                                if (canEdit) IconButton(onClick = { confirmDeleteChapter = ch }) { Icon(Icons.Filled.Delete, contentDescription = "حذف") }
                                IconButton(onClick = { openMedia(app, nav, ch, 0, all) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "تشغيل") }
                            }
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = { if (selecting) selected = if (ch.code in selected) selected - ch.code else selected + ch.code else nav.openChapter(ch) },
                            onLongClick = { if (canEdit) { selecting = true; selected = selected + ch.code } },
                        ),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
