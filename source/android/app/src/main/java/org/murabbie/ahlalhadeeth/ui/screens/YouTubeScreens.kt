package org.murabbie.ahlalhadeeth.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.Chapter
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.Segment
import org.murabbie.ahlalhadeeth.data.Sheekh
import org.murabbie.ahlalhadeeth.data.SheekhBook
import org.murabbie.ahlalhadeeth.data.YouTube
import org.murabbie.ahlalhadeeth.data.YouTubeMedia
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.Routes
import org.murabbie.ahlalhadeeth.ui.SegmentRow

/** جسر بين مشغّل IFrame (JS) وKotlin */
class YtBridge {
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val playing = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val error = MutableStateFlow(0)
    val playlist = MutableStateFlow<List<String>>(emptyList())

    @JavascriptInterface fun onTime(cur: Double, dur: Double, state: Int) {
        positionMs.value = (cur * 1000).toLong(); durationMs.value = (dur * 1000).toLong(); playing.value = state == 1
    }
    @JavascriptInterface fun onReady() { ready.value = true }
    @JavascriptInterface fun onState(state: Int) { playing.value = state == 1 }
    @JavascriptInterface fun onError(code: Int) { error.value = code }
    @JavascriptInterface fun onPlaylist(json: String) {
        runCatching { val a = JSONArray(json); playlist.value = (0 until a.length()).map { a.getString(it) } }
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun createYouTubeWebView(context: android.content.Context, bridge: YtBridge): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    webChromeClient = WebChromeClient()
    webViewClient = WebViewClient()
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    setBackgroundColor(android.graphics.Color.BLACK)
    addJavascriptInterface(bridge, "Android")
}

/** مشغّل يوتيوب (المشغّل الرسمي المضمَّن) مع فهرس المواضع المتزامن */
@Composable
fun YouTubeScreen(app: App, repo: Repository, nav: NavHostController, code: Int, startMs: Long) {
    val context = LocalContext.current
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var writes by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var showTranscript by remember { mutableStateOf(app.settings.value.showTranscriptInline) }
    val bridge = remember { YtBridge() }
    val webView = remember { createYouTubeWebView(context, bridge) }
    val posMs by bridge.positionMs.collectAsState()
    val durMs by bridge.durationMs.collectAsState()
    val playing by bridge.playing.collectAsState()
    val ready by bridge.ready.collectAsState()
    val error by bridge.error.collectAsState()
    val contentVersion by app.userDb.content.version.collectAsState()
    val admin by app.sharedSync.state.collectAsState()
    val listState = rememberLazyListState()
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(code, contentVersion) {
        chapter = repo.chapter(code)
        segments = repo.segments(code)
        writes = repo.writesOfChapter(code)
    }
    LaunchedEffect(chapter?.youtubeId) {
        val id = chapter?.youtubeId ?: return@LaunchedEffect
        if (!loaded) {
            loaded = true
            webView.loadDataWithBaseURL("https://www.youtube.com", YouTube.playerHtml(id, null, startMs / 1000, autoplay = true), "text/html", "utf-8", null)
        }
    }
    DisposableEffect(Unit) {
        webView.keepScreenOn = true
        onDispose {
            runCatching { webView.evaluateJavascript("pauseVideo()", null) }
            webView.keepScreenOn = false
            webView.destroy()
        }
    }
    // حفظ موضع التوقف في سجل الاستماع
    LaunchedEffect(posMs / 5000) {
        val ch = chapter ?: return@LaunchedEffect
        if (posMs > 0) runCatching { app.userDb.savePosition(ch.code, posMs, durMs); app.settings.update { it.copy(lastPlayedCode = ch.code, lastPlayedPosition = posMs) } }
    }

    val currentIndex = remember(posMs, segments) {
        var idx = -1
        for (i in segments.indices) if (segments[i].offsetStart <= posMs) idx = i else break
        idx
    }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(currentIndex) { if (follow && currentIndex >= 0 && !listState.isScrollInProgress) listState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0)) }

    val ch = chapter
    Scaffold(
        topBar = {
            AppTopBar(ch?.displayTitle ?: "يوتيوب", nav, subtitle = ch?.let { "${it.sheekhName} — ${it.bookName}" }, actions = {
                if (ch != null && admin.isAdmin) IconButton(onClick = { nav.navigate(Routes.autoIndex(ch.code)) }) { Icon(Icons.Filled.AutoAwesome, contentDescription = "فهرسة وتفريغ تلقائي") }
                if (ch != null) IconButton(onClick = { nav.navigate(Routes.tape(ch.code)) }) { Icon(Icons.Filled.ListAlt, contentDescription = "شاشة الدرس") }
                if (writes.isNotEmpty()) IconButton(onClick = { showTranscript = !showTranscript }) { Icon(if (showTranscript) Icons.Filled.Article else Icons.Outlined.Article, contentDescription = "التفريغ") }
            })
        },
        floatingActionButton = {
            if (admin.isAdmin && ch != null) ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.editSegment(ch.code, 0, posMs)) }) { Text("+ موضع ${ArabicText.formatTime(posMs)}") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // clipToBounds: حاوية AndroidView لا تقصّ ما يرسمه WebView خارج حدوده، فيغطي لونه الأسود الشاشة كلها
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clipToBounds().background(Color.Black))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${ArabicText.formatTime(posMs)} / ${ArabicText.formatTime(durMs)}", style = MaterialTheme.typography.labelMedium)
                if (!ready) Text("جارٍ تحميل مشغّل يوتيوب…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != 0) Text(when (error) { 100 -> "الفيديو غير موجود أو خاص"; 101, 150 -> "صاحب الفيديو لا يسمح بتشغيله خارج يوتيوب"; else -> "خطأ في يوتيوب ($error)" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.weight(1f))
                FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("متابعة") })
                listOf(1f, 1.5f, 2f).forEach { r -> FilterChip(selected = false, onClick = { webView.evaluateJavascript("setRate($r)", null) }, label = { Text("×" + ArabicText.arabicDigits(if (r == 1f) "1" else r.toString())) }) }
            }
            val cur = segments.getOrNull(currentIndex)
            if (cur != null) Text(cur.line, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2)
            if (showTranscript && writes.isNotEmpty()) {
                val w = cur?.let { writes[it.id] }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (w == null) Text("لا تفريغ لهذا الموضع.", style = MaterialTheme.typography.bodySmall) else SelectionContainer { Text(w, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 17.sp, lineHeight = 30.sp)) }
                    }
                }
            }
            HorizontalDivider()
            if (segments.isEmpty()) Text(if (admin.isAdmin) "لا مواضع بعد — اضغط أيقونة ✦ (فهرسة وتفريغ تلقائي) في الأعلى ليستخرج التطبيق المواضيع بأزمنتها وتفريغها من الفيديو، أو شغّل الفيديو واضغط «+ موضع» عند كل موضوع." else "لا فهرس لهذا الدرس بعد.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(segments, key = { _, s -> s.id }) { i, s ->
                    SegmentRow(
                        segment = s, isCurrent = i == currentIndex,
                        onClick = { follow = true; webView.evaluateJavascript("seekTo(${s.offsetStart / 1000.0})", null) },
                        onLongClick = { if (admin.isAdmin) nav.navigate(Routes.editSegment(code, s.id)) else if (s.hasWrite) nav.navigate(Routes.transcript(s.id)) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

/** استيراد فيديو أو قائمة تشغيل من يوتيوب إلى سلسلة (للمشرفين)؛ sheekhId/bookId = 0 يعني اختيار الهدف داخل الشاشة */
@Composable
fun YouTubeImportScreen(app: App, repo: Repository, nav: NavHostController, sheekhId: Int, bookId: Int, initialUrl: String = "") {
    val context = LocalContext.current
    val uc = app.userDb.content
    var url by remember { mutableStateOf(initialUrl) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<Triple<String, String, Boolean>>>(emptyList()) } // id, title, selected
    var playlistTitle by remember { mutableStateOf("") }
    var channelName by remember { mutableStateOf("") }
    val bridge = remember { YtBridge() }
    // المشغّل المخفي يُنشأ عند الحاجة فقط (بديل لقراءة القائمة)، ويُعرض مقصوصًا في سطر بارتفاع نقطة واحدة؛
    // كان يُنشأ دائمًا فيرسم خلفيته السوداء فوق الشاشة كلها لأن حاوية AndroidView لا تقصّ ما يرسمه خارج حدوده
    var fallbackView by remember { mutableStateOf<WebView?>(null) }
    val playlistIds by bridge.playlist.collectAsState()
    val scope = rememberCoroutineScope()
    val contentVersion by uc.version.collectAsState()
    // الهدف: شيخ وسلسلة (موجودان أو جديدان)
    val chooseTarget = sheekhId == 0 || bookId == 0
    var sheekhs by remember { mutableStateOf<List<Sheekh>>(emptyList()) }
    var books by remember { mutableStateOf<List<SheekhBook>>(emptyList()) }
    var targetSheekh by remember { mutableStateOf(sheekhId) } // معرّف موجب (كما في Repository) أو 0 = جديد
    var targetBook by remember { mutableStateOf(bookId) }
    var newSheekhName by remember { mutableStateOf("") }
    var newBookName by remember { mutableStateOf("") }
    var bookName by remember { mutableStateOf("") }
    var webFallbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(contentVersion) {
        sheekhs = uc.sheekhs()
        if (!chooseTarget) bookName = repo.book(bookId)?.name ?: ""
        if (targetSheekh == 0 && sheekhs.size == 1 && chooseTarget) targetSheekh = sheekhs[0].id
    }
    LaunchedEffect(targetSheekh, contentVersion) {
        books = if (targetSheekh != 0) repo.booksOfSheekh(targetSheekh) else emptyList()
        if (targetBook != 0 && books.none { it.book.id == targetBook }) targetBook = 0
    }
    DisposableEffect(Unit) { onDispose { fallbackView?.destroy() } }

    fun applyVideos(videos: List<Pair<String, String>>) {
        items = videos.map { Triple(it.first, it.second, true) }
        status = "وُجد ${ArabicText.arabicDigits(videos.size)} فيديو"
        busy = false
        fallbackView?.let { runCatching { it.loadUrl("about:blank") } }
    }

    // البديل: المشغّل المخفي (إن تعذر youtubei): عند وصول القائمة تُجلب العناوين واحدًا واحدًا
    LaunchedEffect(playlistIds) {
        if (playlistIds.isEmpty() || items.isNotEmpty()) return@LaunchedEffect
        webFallbackJob?.cancel()
        busy = true
        val out = ArrayList<Pair<String, String>>()
        for ((i, id) in playlistIds.withIndex()) {
            status = "جلب العناوين ${ArabicText.arabicDigits(i + 1)} / ${ArabicText.arabicDigits(playlistIds.size)}"
            out.add(id to (YouTube.fetchTitle(id) ?: "فيديو ${ArabicText.arabicDigits(i + 1)}"))
        }
        applyVideos(out)
    }

    fun start() {
        val vid = YouTube.extractId(url)
        val pl = YouTube.extractPlaylistId(url)
        items = emptyList(); bridge.playlist.value = emptyList(); playlistTitle = ""; channelName = ""
        if (pl != null) {
            busy = true; status = "قراءة قائمة التشغيل من يوتيوب…"
            scope.launch {
                val res = runCatching { YouTube.fetchPlaylist(pl) { n -> status = "قُرئ ${ArabicText.arabicDigits(n)} فيديو…" } }
                res.onSuccess { info ->
                    playlistTitle = info.title; channelName = info.author
                    if (newBookName.isBlank()) newBookName = info.title
                    if (newSheekhName.isBlank()) newSheekhName = info.author
                    applyVideos(info.videos.map { it.id to it.title })
                }.onFailure { e ->
                    // بديل: المشغّل المخفي (حتى ٢٠٠ فيديو)
                    status = "تعذرت القراءة المباشرة (${e.message})؛ محاولة عبر المشغّل…"
                    val wv = fallbackView ?: createYouTubeWebView(context, bridge).also { it.setBackgroundColor(android.graphics.Color.TRANSPARENT); fallbackView = it }
                    wv.loadDataWithBaseURL("https://www.youtube.com", YouTube.playerHtml(null, pl, 0, autoplay = false), "text/html", "utf-8", null)
                    webFallbackJob = scope.launch {
                        kotlinx.coroutines.delay(20_000)
                        if (items.isEmpty()) { busy = false; status = "تعذرت قراءة قائمة التشغيل؛ تأكد أنها عامة وأن الرابط صحيح" }
                    }
                }
            }
        } else if (vid != null) {
            busy = true; status = "جلب عنوان الفيديو…"
            scope.launch {
                val info = YouTube.fetchInfo(YouTube.watchUrl(vid))
                channelName = info?.second ?: ""
                if (newSheekhName.isBlank() && channelName.isNotBlank()) newSheekhName = channelName
                applyVideos(listOf(vid to (info?.first ?: "درس من يوتيوب")))
            }
        } else {
            status = "الرابط ليس رابط فيديو أو قائمة تشغيل من يوتيوب"
        }
    }
    LaunchedEffect(Unit) { if (initialUrl.isNotBlank() && items.isEmpty()) start() }

    // نسخة Google Play: لا نقل لوسائط يوتيوب داخل التطبيق (الدروس تُشغَّل بمشغّل يوتيوب المضمَّن)
    val canTransfer = org.murabbie.ahlalhadeeth.BuildConfig.DISTRIBUTION != "play"
    var autoIndexAfter by remember { mutableStateOf(false) }
    var transferAfter by remember { mutableStateOf(canTransfer) }
    // نوع المقاطع: مسموعة فقط (صوت) أو مرئية كما على يوتيوب (عادية ٣٦٠p أو عالية حتى ١٠٨٠p) — يُحفظ آخر اختيار
    var quality by remember { mutableStateOf(app.settings.value.lastImportQuality) }
    val canAdd = !busy && items.any { it.third } && (!chooseTarget || ((targetSheekh != 0 || newSheekhName.isNotBlank()) && (targetBook != 0 || newBookName.isNotBlank())))

    Scaffold(topBar = { AppTopBar("إضافة من يوتيوب", nav, subtitle = if (chooseTarget) "فيديو أو قائمة تشغيل كاملة" else bookName) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // كل الشاشة قائمة واحدة قابلة للتمرير حتى يُزاح حقل الكتابة فوق لوحة المفاتيح ولا تحجبه
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "url") { OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("رابط فيديو أو قائمة تشغيل") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item(key = "read") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = !busy && url.isNotBlank(), onClick = { start() }) { Text("قراءة") }
                        if (busy) CircularProgressIndicator(Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                        Text(status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    }
                }
                fallbackView?.let { wv ->
                    // المشغّل المخفي (بديل لقراءة قائمة التشغيل) — مقصوص في سطر بارتفاع نقطة واحدة
                    item(key = "fallback") { Box(Modifier.fillMaxWidth().height(1.dp).clipToBounds()) { AndroidView(factory = { wv }, modifier = Modifier.fillMaxWidth().height(1.dp)) } }
                }
                if (items.isNotEmpty()) {
                    if (playlistTitle.isNotBlank()) item(key = "pl") { Text("القائمة: $playlistTitle" + (if (channelName.isNotBlank()) " — القناة: $channelName" else ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (chooseTarget) {
                        item(key = "sheekh") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("الشيخ:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(selected = targetSheekh == 0, onClick = { targetSheekh = 0; targetBook = 0 }, label = { Text("+ شيخ جديد") })
                                    sheekhs.forEach { s -> FilterChip(selected = targetSheekh == s.id, onClick = { targetSheekh = s.id; targetBook = 0 }, label = { Text(s.name) }) }
                                }
                                if (targetSheekh == 0) OutlinedTextField(value = newSheekhName, onValueChange = { newSheekhName = it }, label = { Text("اسم الشيخ الجديد") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                        item(key = "book") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("السلسلة:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(selected = targetBook == 0, onClick = { targetBook = 0 }, label = { Text("+ سلسلة جديدة") })
                                    books.forEach { b -> FilterChip(selected = targetBook == b.book.id, onClick = { targetBook = b.book.id }, label = { Text(b.book.name) }) }
                                }
                                if (targetBook == 0) OutlinedTextField(value = newBookName, onValueChange = { newBookName = it }, label = { Text("اسم السلسلة الجديدة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                    }
                    if (canTransfer) item(key = "kind") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("نوع المقاطع:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (q in listOf(YouTubeMedia.QUALITY_AUDIO, YouTubeMedia.QUALITY_VIDEO_SD, YouTubeMedia.QUALITY_VIDEO_HD)) {
                                    FilterChip(selected = quality == q, onClick = { quality = q; app.settings.update { it.copy(lastImportQuality = q) } }, label = { Text(YouTubeMedia.qualityLabel(q)) })
                                }
                            }
                            Text(when (quality) {
                                YouTubeMedia.QUALITY_VIDEO_SD -> "فيديو بصوته كما على يوتيوب بدقة ٣٦٠p (نحو ٢٠٠–٣٠٠ م.ب للساعة)."
                                YouTubeMedia.QUALITY_VIDEO_HD -> "فيديو بصوته بأعلى دقة متاحة حتى ١٠٨٠p (٧٢٠p غالبًا؛ نحو ٥٠٠ م.ب–١ ج.ب للساعة) — يُدمج الصوت مع الصورة على الهاتف بلا إعادة ترميز."
                                else -> "الصوت فقط (m4a ١٢٨k، نحو ٥٥ م.ب للساعة) — الأخف نقلًا وتشغيلًا."
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item(key = "after") {
                        Column {
                            if (canTransfer) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = transferAfter, onCheckedChange = { transferAfter = it; if (it) autoIndexAfter = false })
                                Text("بعد الإضافة: نقل الدروس إلى خادم البيانات (تُشغَّل من الخادم لا من يوتيوب)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = autoIndexAfter, onCheckedChange = { autoIndexAfter = it; if (it) transferAfter = false })
                                Text("بعد الإضافة: فهرسة وتفريغ تلقائي فورًا (يمكن لاحقًا من شاشة السلسلة)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    item(key = "hdr") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("الدروس (${ArabicText.arabicDigits(items.count { it.third })} من ${ArabicText.arabicDigits(items.size)}):", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { val all = items.all { it.third }; items = items.map { it.copy(third = !all) } }) { Text(if (items.all { it.third }) "إلغاء الكل" else "تحديد الكل") }
                        }
                    }
                    itemsIndexed(items, key = { i, it -> "v$i:" + it.first }) { i, it ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = it.third, onCheckedChange = { on -> items = items.mapIndexed { j, x -> if (j == i) x.copy(third = on) else x } })
                            Text("${ArabicText.arabicDigits(i + 1)}. ${it.second}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    item(key = "help") { Text("الصق رابط فيديو مفرد أو رابط قائمة تشغيل كاملة (تُقرأ كل فيديوهاتها بعناوينها) ثم اضغط «قراءة»، واختر الشيخ والسلسلة (أو أنشئهما) وما يُضاف من الدروس. بعد الإضافة افتح كل درس لفهرسة مواضعه بالأزمنة، ثم انشر التغييرات للجميع.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (items.isNotEmpty()) {
                Button(enabled = canAdd, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            var sid = targetSheekh
                            if (sid == 0) sid = -uc.addSheekh(newSheekhName.trim())
                            var bid = targetBook
                            if (bid == 0) bid = -uc.addBook(-sid, newBookName.trim())
                            var n = 0
                            for (it in items.filter { it.third }) { uc.addChapter(-sid, -bid, it.second, YouTube.watchUrl(it.first), isVideo = quality != YouTubeMedia.QUALITY_AUDIO); n++ }
                            status = "أُضيف ${ArabicText.arabicDigits(n)} درسًا"; busy = false
                            nav.popBackStack()
                            if (transferAfter && canTransfer) nav.navigate(Routes.transfer(sid, bid, auto = true, quality = quality))
                            else if (autoIndexAfter) nav.navigate(Routes.autoBatch(sid, bid, auto = true))
                            else if (chooseTarget) nav.navigate(Routes.chapters(sid, bid))
                        } catch (e: Exception) { status = "تعذرت الإضافة: ${e.message}"; busy = false }
                    }
                }) { Text("إضافة ${ArabicText.arabicDigits(items.count { it.third })} درسًا" + (if (chooseTarget) " ثم فتح السلسلة" else "")) }
            }
        }
    }
}
