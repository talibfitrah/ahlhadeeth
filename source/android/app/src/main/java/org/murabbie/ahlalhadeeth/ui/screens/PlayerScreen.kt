package org.murabbie.ahlalhadeeth.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.Segment
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.Routes
import org.murabbie.ahlalhadeeth.ui.SegmentRow

/** شاشة المشغّل الكاملة (نافذة التشغيل في نسخة ويندوز) */
@Composable
fun PlayerScreen(app: App, repo: Repository, nav: NavHostController) {
    val st by app.player.state.collectAsState()
    val ch = st.chapter
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var writes by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var showTranscript by remember { mutableStateOf(app.settings.value.showTranscriptInline) }
    var loadedCode by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val favVersion by app.userDb.favoritesVersion.collectAsState()
    var favIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(ch?.code) {
        val c = ch?.code ?: 0
        if (c != loadedCode) {
            segments = if (c > 0) repo.segments(c) else emptyList()
            writes = if (c > 0) repo.writesOfChapter(c) else emptyMap()
            loadedCode = c
        }
    }
    LaunchedEffect(favVersion) { favIds = app.userDb.favoriteIds() }

    val currentIndex = remember(st.positionMs, segments) {
        var idx = -1
        for (i in segments.indices) if (segments[i].offsetStart <= st.positionMs) idx = i else break
        idx
    }
    var followPlayback by remember { mutableStateOf(true) }
    LaunchedEffect(currentIndex) {
        if (followPlayback && currentIndex >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0))
        }
    }

    Scaffold(topBar = {
        AppTopBar(ch?.let { "${it.displayTitle} (${it.fileName})" } ?: "المشغّل", nav, subtitle = ch?.let { "${it.sheekhName} — ${it.bookName}" }, actions = {
            if (ch != null) IconButton(onClick = { nav.navigate(Routes.tape(ch.code)) }) { Icon(Icons.Filled.ListAlt, contentDescription = "فهرس الشريط") }
        })
    }) { padding ->
        if (ch == null) {
            Text("لا يوجد شريط قيد التشغيل.", Modifier.padding(padding).padding(24.dp), textAlign = TextAlign.Center)
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ch.isVideo) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val playerView = remember(ctx) { androidx.media3.ui.PlayerView(ctx).apply { useController = false; setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } }
                androidx.compose.runtime.DisposableEffect(Unit) {
                    playerView.player = app.player.playerOrNull()
                    onDispose { playerView.player = null }
                }
                LaunchedEffect(st.chapter?.code, st.isPlaying) { if (playerView.player == null) playerView.player = app.player.playerOrNull() }
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { playerView },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(androidx.compose.ui.graphics.Color.Black),
                )
            }
            // المقطع الحالي
            val cur = segments.getOrNull(currentIndex)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(cur?.line ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                if (st.error != null) Text(st.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(if (st.isLocalSource) "التشغيل من الجهاز" else "البث من الخادم", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // شريط الزمن
            val dur = st.durationMs.coerceAtLeast(1)
            val pos = dragging ?: (st.positionMs.toFloat() / dur).coerceIn(0f, 1f)
            Slider(
                value = pos,
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { app.player.seekTo((it * dur).toLong()) }; dragging = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(ArabicText.formatTime((pos * dur).toLong()), style = MaterialTheme.typography.labelMedium)
                Text(ArabicText.formatTime(st.durationMs), style = MaterialTheme.typography.labelMedium)
            }
            // الأزرار
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.player.previous() }, enabled = st.hasPrevious) { Icon(Icons.Filled.SkipPrevious, contentDescription = "الشريط السابق") }
                IconButton(onClick = { app.player.seekBack() }) { Icon(Icons.Filled.Replay10, contentDescription = "رجوع ١٠ ثوانٍ") }
                FilledIconButton(onClick = { app.player.togglePlayPause() }, modifier = Modifier.size(64.dp)) {
                    if (st.isBuffering) CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                    else Icon(if (st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (st.isPlaying) "إيقاف مؤقت" else "تشغيل", Modifier.size(36.dp))
                }
                IconButton(onClick = { app.player.seekForward() }) { Icon(Icons.Filled.Forward30, contentDescription = "تقديم ٣٠ ثانية") }
                IconButton(onClick = { app.player.next() }, enabled = st.hasNext) { Icon(Icons.Filled.SkipNext, contentDescription = "الشريط التالي") }
            }
            // السرعة والمفضلة والتفريغ
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                    FilterChip(selected = kotlin.math.abs(st.speed - sp) < 0.01f, onClick = { app.player.setSpeed(sp) }, label = { Text("×" + ArabicText.arabicDigits(if (sp == sp.toLong().toFloat()) sp.toLong().toString() else sp.toString())) })
                }
                Spacer(Modifier.weight(1f))
                if (cur != null) {
                    val isFav = cur.id in favIds
                    IconButton(onClick = { scope.launch { if (isFav) app.userDb.removeFavorite(cur.id) else app.userDb.addFavorite(cur) } }) {
                        Icon(if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "المفضلة", tint = if (isFav) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (writes.isNotEmpty()) {
                    IconButton(onClick = { showTranscript = !showTranscript }) {
                        Icon(if (showTranscript) Icons.Filled.Article else Icons.Outlined.Article, contentDescription = if (showTranscript) "إخفاء التفريغ" else "عرض تفريغ المقطع الجاري", tint = if (showTranscript) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { nav.navigate(Routes.tapeText(ch.code, cur?.seq ?: 0)) }) { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "التفريغ الكامل للشريط") }
                }
            }
            HorizontalDivider()
            // لوحة التفريغ للمقطع الجاري
            if (showTranscript && writes.isNotEmpty()) {
                val w = cur?.let { writes[it.id] }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (w == null) Text(if (cur == null) "ابدأ التشغيل ليظهر تفريغ المقطع الجاري." else "لا تفريغ لهذا المقطع.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else SelectionContainer { Text(w, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 17.sp, lineHeight = 30.sp)) }
                    }
                }
                HorizontalDivider()
            }
            // فهرس الشريط
            if (segments.isEmpty()) {
                Text("لا يوجد فهرس لهذا الشريط.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(segments, key = { _, s -> s.id }) { i, s ->
                    SegmentRow(
                        segment = s, isCurrent = i == currentIndex, isFavorite = s.id in favIds,
                        onClick = { followPlayback = true; app.player.seekTo(s.offsetStart); if (!st.isPlaying) app.player.togglePlayPause() },
                        onLongClick = { if (s.hasWrite) nav.navigate(Routes.transcript(s.id)) },
                    )
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}
