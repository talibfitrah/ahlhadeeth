package org.murabbie.ahlalhadeeth.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import org.murabbie.ahlalhadeeth.data.Chapter
import org.murabbie.ahlalhadeeth.data.HistoryEntry
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.SegmentWithChapter
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.openMedia
import org.murabbie.ahlalhadeeth.ui.EmptyState
import org.murabbie.ahlalhadeeth.ui.Loading
import org.murabbie.ahlalhadeeth.ui.Routes
import org.murabbie.ahlalhadeeth.ui.SegmentRow

/** المفضلة (نافذة «المفضلة» في نسخة ويندوز) مرتبة بالشيخ ثم الكتاب */
@Composable
fun FavoritesScreen(app: App, repo: Repository, nav: NavHostController) {
    val version by app.userDb.favoritesVersion.collectAsState()
    var items by remember { mutableStateOf<List<SegmentWithChapter>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val playerState by app.player.state.collectAsState()

    LaunchedEffect(version) {
        val favs = app.userDb.favorites()
        val segs = repo.segmentsByIds(favs.map { it.contentId })
        val ordered = favs.mapNotNull { segs[it.contentId] }
        items = repo.attachChapters(ordered)
    }

    Scaffold(topBar = {
        AppTopBar("المفضلة", subtitle = items?.let { "${ArabicText.arabicDigits(it.size)} مقطع" }, actions = {
            if (!items.isNullOrEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.DeleteSweep, contentDescription = "حذف الكل") }
        })
    }) { padding ->
        val list = items
        if (list == null) {
            Loading(Modifier.padding(padding)); return@Scaffold
        }
        if (list.isEmpty()) {
            EmptyState("لا توجد مقاطع في المفضلة.\nاضغط مطوّلًا على أي مقطع لإضافته.", Modifier.padding(padding)); return@Scaffold
        }
        val grouped = list.groupBy { it.chapter.sheekhName to it.chapter.bookName }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            grouped.forEach { (key, group) ->
                item(key = "h${key.first}${key.second}") {
                    Text("${key.first} — ${key.second}", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                items(group, key = { it.segment.id }) { r ->
                    SegmentRow(
                        segment = r.segment, chapter = r.chapter, showChapter = true, isFavorite = true,
                        isCurrent = playerState.chapter?.code == r.segment.code && playerState.positionMs >= r.segment.offsetStart && playerState.positionMs < r.segment.offsetStart + 60_000,
                        onClick = { nav.navigate(Routes.tape(r.segment.code, r.segment.seq)) },
                        onLongClick = { scope.launch { app.userDb.removeFavorite(r.segment.id) } },
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("حذف كل المفضلة؟") },
            confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { app.userDb.clearFavorites() } }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } },
        )
    }
}

/** سجل الاستماع */
@Composable
fun HistoryScreen(app: App, repo: Repository, nav: NavHostController) {
    var entries by remember { mutableStateOf<List<Pair<HistoryEntry, Chapter>>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refresh) {
        val h = app.userDb.history(200)
        val chapters = repo.chaptersByCodes(h.map { it.code })
        entries = h.mapNotNull { e -> chapters[e.code]?.let { e to it } }
    }
    Scaffold(topBar = {
        AppTopBar("سجل الاستماع", nav, actions = {
            IconButton(onClick = { scope.launch { app.userDb.clearHistory(); refresh++ } }) { Icon(Icons.Filled.Delete, contentDescription = "مسح السجل") }
        })
    }) { padding ->
        val list = entries
        if (list == null) {
            Loading(Modifier.padding(padding)); return@Scaffold
        }
        if (list.isEmpty()) {
            EmptyState("لم تستمع إلى شيء بعد.", Modifier.padding(padding)); return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(list, key = { it.first.code }) { (e, ch) ->
                ListItem(
                    headlineContent = { Text("${ch.displayTitle} (${ch.fileName})", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text("${ch.sheekhName} — ${ch.bookName} — توقفت عند ${ArabicText.formatTime(e.positionMs)}" + (if (e.durationMs > 0) " من ${ArabicText.formatTime(e.durationMs)}" else ""), style = MaterialTheme.typography.labelMedium)
                    },
                    trailingContent = { IconButton(onClick = { openMedia(app, nav, ch, e.positionMs) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "متابعة") } },
                    modifier = Modifier.clickable { nav.navigate(Routes.tape(ch.code)) },
                )
                HorizontalDivider()
            }
        }
    }
}
