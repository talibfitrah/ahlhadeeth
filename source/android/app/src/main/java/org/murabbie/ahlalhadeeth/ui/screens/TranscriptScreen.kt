package org.murabbie.ahlalhadeeth.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** عرض التفريغ النصي لمقطع (نافذة «التفريغ» في نسخة ويندوز) */
@Composable
fun TranscriptScreen(app: App, repo: Repository, nav: NavHostController, contentId: Long, query: String) {
    var segment by remember { mutableStateOf<Segment?>(null) }
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    var write by remember { mutableStateOf<String?>(null) }
    var siblings by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var currentId by remember { mutableStateOf(contentId) }
    var isFav by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var showNote by remember { mutableStateOf(false) }
    var fontSize by remember { mutableStateOf(19f) }
    val favVersion by app.userDb.favoritesVersion.collectAsState()
    val syncState by app.sharedSync.state.collectAsState()
    val isAdmin = syncState.isAdmin
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val words = remember(query) { ArabicText.queryWords(query) }

    LaunchedEffect(currentId) {
        val s = repo.segment(currentId)
        segment = s
        if (s != null) {
            if (chapter?.code != s.code) {
                chapter = repo.chapter(s.code)
                siblings = repo.segments(s.code).filter { it.hasWrite }
            }
            write = repo.write(s.id)
            note = app.userDb.note(s.id)
        }
    }
    LaunchedEffect(favVersion, currentId) { isFav = app.userDb.isFavorite(currentId) }

    val s = segment
    val ch = chapter
    Scaffold(topBar = {
        AppTopBar(
            title = ch?.let { "${it.displayTitle} (${it.fileName})" } ?: "التفريغ",
            nav = nav,
            subtitle = ch?.let { "${it.sheekhName} — ${it.bookName}" },
            actions = {
                if (ch != null) IconButton(onClick = { nav.navigate(org.murabbie.ahlalhadeeth.ui.Routes.tapeText(ch.code, s?.seq ?: 0, query)) }) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.MenuBook, contentDescription = "التفريغ الكامل للشريط") }
                IconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(12f) }) { Icon(Icons.Filled.TextDecrease, contentDescription = "تصغير الخط") }
                IconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(40f) }) { Icon(Icons.Filled.TextIncrease, contentDescription = "تكبير الخط") }
            },
        )
    }) { padding ->
        if (s == null || ch == null) {
            Loading(Modifier.padding(padding)); return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = { openMedia(app, nav, ch, s.offsetStart) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "تشغيل من ${ArabicText.formatTime(s.offsetStart)}") }
                Text(ArabicText.formatTime(s.offsetStart), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { scope.launch { if (isFav) app.userDb.removeFavorite(s.id) else app.userDb.addFavorite(s) } }) {
                    Icon(if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "المفضلة", tint = if (isFav) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(shareText(ch, s, write))) }) { Icon(Icons.Filled.ContentCopy, contentDescription = "نسخ") }
                IconButton(onClick = {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(ch, s, write))
                    context.startActivity(Intent.createChooser(i, "مشاركة"))
                }) { Icon(Icons.Filled.Share, contentDescription = "مشاركة") }
            }
            HorizontalDivider()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(highlighted(s.line, words), style = MaterialTheme.typography.titleMedium.copy(fontSize = (fontSize + 1).sp, lineHeight = (fontSize * 1.8f).sp), color = MaterialTheme.colorScheme.primary)
                if (s.hnum > 0) Text("حديث رقم ${ArabicText.arabicDigits(s.hnum)}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(12.dp))
                val w = write
                if (s.isUser && isAdmin) {
                    var draft by remember(s.id) { mutableStateOf(w ?: "") }
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(), minLines = 8,
                        label = { Text("تفريغ هذا الموضع (محتوى مضاف — قابل للتحرير)") },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.8f).sp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch { app.userDb.content.updateWrite(-s.id, draft); write = draft.ifBlank { null } } }) { Text("حفظ التفريغ") }
                    }
                } else if (w == null) Text("لا يوجد تفريغ نصي لهذا المقطع.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else SelectionContainer {
                    Text(highlighted(w, words), style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.8f).sp))
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { showNote = !showNote }) { Text(if (note.isBlank()) "إضافة ملاحظة" else "ملاحظتي") }
                if (showNote || note.isNotBlank()) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it; scope.launch { app.userDb.saveNote(s.id, it) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("ملاحظة خاصة (تُحفظ تلقائيًا)") },
                        minLines = 2,
                    )
                }
                Spacer(Modifier.height(80.dp))
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                val idx = siblings.indexOfFirst { it.id == currentId }
                OutlinedButton(onClick = { if (idx > 0) currentId = siblings[idx - 1].id }, enabled = idx > 0) { Text("المقطع السابق") }
                Text(if (idx >= 0) "${ArabicText.arabicDigits(idx + 1)} / ${ArabicText.arabicDigits(siblings.size)}" else "", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { if (idx in 0 until siblings.size - 1) currentId = siblings[idx + 1].id }, enabled = idx in 0 until siblings.size - 1) { Text("المقطع التالي") }
            }
        }
    }
}
