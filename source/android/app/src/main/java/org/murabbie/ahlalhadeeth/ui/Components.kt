package org.murabbie.ahlalhadeeth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.navigation.NavHostController
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.Chapter
import org.murabbie.ahlalhadeeth.data.Segment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, nav: NavHostController? = null, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                if (!subtitle.isNullOrBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        navigationIcon = {
            if (nav != null) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** تظليل كلمات البحث داخل النص */
@Composable
fun highlighted(text: String, words: List<String>, color: Color = MaterialTheme.colorScheme.secondaryContainer): AnnotatedString {
    if (words.isEmpty()) return AnnotatedString(text)
    val matches = ArabicText.findMatches(text, words)
    if (matches.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (r in matches) {
            val end = (r.last + 1).coerceAtMost(text.length)
            if (r.first < end) addStyle(SpanStyle(background = color, fontWeight = FontWeight.Bold), r.first, end)
        }
    }
}

/** صف مقطع (سطر فهرس) داخل الشريط أو نتائج البحث */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SegmentRow(
    segment: Segment,
    chapter: Chapter? = null,
    words: List<String> = emptyList(),
    snippet: String = "",
    isCurrent: Boolean = false,
    isFavorite: Boolean = false,
    showChapter: Boolean = false,
    transcript: String? = null,
    transcriptFontSize: Float = 0f,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val bg = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Column(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (showChapter && chapter != null) {
            Text(
                "${chapter.sheekhName} — ${chapter.bookName} — ${chapter.displayTitle} (${chapter.fileName})",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.width(56.dp)) {
                Text(ArabicText.formatTime(segment.offsetStart), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ArabicText.arabicDigits(segment.seq), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Column(Modifier.weight(1f)) {
                Text(highlighted(segment.line, words), style = MaterialTheme.typography.bodyMedium)
                if (snippet.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        highlighted(snippet, words),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (segment.hnum > 0) Tag("ح ${ArabicText.arabicDigits(segment.hnum)}")
                    if (segment.ques) Icon(Icons.Filled.QuestionMark, contentDescription = "سؤال", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                    if (segment.hasWrite) Icon(Icons.Filled.Article, contentDescription = "تفريغ", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    if (isFavorite) Icon(Icons.Filled.Favorite, contentDescription = "مفضلة", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (!transcript.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        highlighted(transcript, words),
                        Modifier.padding(10.dp),
                        style = if (transcriptFontSize > 0f) MaterialTheme.typography.bodyMedium.copy(fontSize = transcriptFontSize.sp, lineHeight = (transcriptFontSize * 1.8f).sp) else MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
fun Tag(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
    }
}

/** شريط المشغّل المصغّر أسفل الشاشة */
@Composable
fun MiniPlayer(app: App, onOpen: () -> Unit) {
    val st by app.player.state.collectAsState()
    val ch = st.chapter ?: return
    Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 3.dp) {
        Column {
            if (st.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { app.player.togglePlayPause() }) {
                    if (st.isBuffering) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (st.isPlaying) "إيقاف مؤقت" else "تشغيل")
                }
                Column(Modifier.weight(1f)) {
                    Text("${ch.displayTitle} (${ch.fileName})", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${ch.sheekhName} — ${ch.bookName} — ${ArabicText.formatTime(st.positionMs)} / ${ArabicText.formatTime(st.durationMs)}",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (st.error != null) Text(st.error!!, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                IconButton(onClick = { app.player.stop() }) { Icon(Icons.Filled.Close, contentDescription = "إغلاق المشغّل") }
            }
        }
    }
}

/**
 * بطاقة نموذج داخل الشاشة (بديل نوافذ الحوار المنبثقة التي ظهرت سوداء على بعض الأجهزة):
 * عنوان وزر إغلاق ومحتوى.
 */
@Composable
fun InlineFormCard(title: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.material3.Card(
        modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "إغلاق") }
            }
            content()
        }
    }
}

/** بطاقة تأكيد داخل الشاشة (بديل نافذة التأكيد) */
@Composable
fun ConfirmCard(title: String, text: String? = null, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false, modifier: Modifier = Modifier) {
    androidx.compose.material3.Card(
        modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = (if (destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer).copy(alpha = 0.5f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (!text.isNullOrBlank()) Text(text, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(
                    onClick = onConfirm,
                    colors = if (destructive) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else androidx.compose.material3.ButtonDefaults.buttonColors(),
                ) { Text(confirmLabel) }
                androidx.compose.material3.OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
            }
        }
    }
}

/** شريط حالة نقل الدروس إلى الخادم أسفل التطبيق (فوق شريط التنقل) — يبقى ظاهرًا في كل الشاشات حتى ينتهي ويُغلقه المستخدم */
@Composable
fun TransferBanner(app: App, onOpen: () -> Unit) {
    val st by app.transfer.state.collectAsState()
    if (!st.visible) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp) {
        Column {
            LinearProgressIndicator(progress = { st.doneCount.toFloat() / st.items.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.padding(horizontal = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text((if (st.running) "نقل إلى الخادم" else "انتهى النقل") + (if (st.bookName.isNotBlank()) ": ${st.bookName}" else ""), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(org.murabbie.ahlalhadeeth.ui.screens.transferSummary(st), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (st.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { app.transfer.dismiss() }) { Icon(Icons.Filled.Close, contentDescription = "إخفاء") }
            }
        }
    }
}

/** شريط حالة التفريغ التلقائي أسفل التطبيق (فوق شريط التنقل): الدرس الجاري ونسبته المئوية، ويبقى حتى ينتهي ويُغلقه المستخدم */
@Composable
fun AutoIndexBanner(app: App, onOpen: () -> Unit) {
    val st by app.autoIndex.state.collectAsState()
    if (!st.visible) return
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, tonalElevation = 3.dp) {
        Column {
            LinearProgressIndicator(progress = { st.overall.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.padding(horizontal = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text((if (st.running) "تفريغ تلقائي" else if (st.quotaStop) "توقف التفريغ (الحصة)" else "انتهى التفريغ") + (if (st.bookName.isNotBlank()) ": ${st.bookName}" else ""), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(org.murabbie.ahlalhadeeth.data.autoIndexSummary(st), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (st.running) Text(ArabicText.arabicDigits((st.progress * 100).toInt()) + "٪", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
                else IconButton(onClick = { app.autoIndex.dismiss() }) { Icon(Icons.Filled.Close, contentDescription = "إخفاء") }
            }
        }
    }
}
