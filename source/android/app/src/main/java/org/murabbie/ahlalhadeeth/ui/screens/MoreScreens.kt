package org.murabbie.ahlalhadeeth.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.BuildConfig
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.BookStats
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.SheekhStats
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.Loading
import org.murabbie.ahlalhadeeth.ui.Routes

@Composable
fun MoreScreen(app: App, repo: Repository, nav: NavHostController) {
    Scaffold(topBar = { AppTopBar("المزيد") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(headlineContent = { Text("المحتوى المضاف") }, supportingContent = { Text("الدروس المشتركة التي ينشرها المشرفون، ودخول المشرفين لإضافة سلاسل ودروس (صوت، فيديو، يوتيوب) ونشرها للجميع") }, leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) }, modifier = Modifier.clickable { nav.navigate(Routes.MANAGE) })
            HorizontalDivider()
            ListItem(headlineContent = { Text("التنزيلات") }, supportingContent = { Text("تنزيل الأشرطة للاستماع بلا إنترنت") }, leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) }, modifier = Modifier.clickable { nav.navigate(Routes.DOWNLOADS) })
            HorizontalDivider()
            ListItem(headlineContent = { Text("سجل الاستماع") }, leadingContent = { Icon(Icons.Filled.History, contentDescription = null) }, modifier = Modifier.clickable { nav.navigate(Routes.HISTORY) })
            HorizontalDivider()
            ListItem(headlineContent = { Text("الإحصائيات") }, supportingContent = { Text("أعداد الأشرطة والمقاطع والتفريغات") }, leadingContent = { Icon(Icons.Filled.BarChart, contentDescription = null) }, modifier = Modifier.clickable { nav.navigate(Routes.STATS) })
            HorizontalDivider()
            ListItem(headlineContent = { Text("الإعدادات") }, supportingContent = { Text("البيانات، مصدر الصوت، التنزيل، الخط، المظهر") }, leadingContent = { Icon(Icons.Filled.Settings, contentDescription = null) }, modifier = Modifier.clickable { nav.navigate(Routes.SETTINGS) })
            HorizontalDivider()
            ListItem(headlineContent = { Text("عن البرنامج") }, leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) }, modifier = Modifier.clickable { nav.navigate(Routes.ABOUT) })
            HorizontalDivider()
        }
    }
}

/** الإحصائيات (صفحة الإحصائيات في الموقع) */
@Composable
fun StatsScreen(app: App, repo: Repository, nav: NavHostController) {
    var sheekhStats by remember { mutableStateOf<List<SheekhStats>?>(null) }
    var selected by remember { mutableStateOf(0) }
    var bookStats by remember { mutableStateOf<List<BookStats>>(emptyList()) }
    var meta by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var userStats by remember { mutableStateOf(Triple(0, 0, 0)) }
    LaunchedEffect(Unit) { sheekhStats = repo.sheekhStats(); meta = repo.meta(); userStats = app.userDb.content.stats() }
    LaunchedEffect(selected) { bookStats = repo.bookStats(selected) }

    Scaffold(topBar = { AppTopBar("الإحصائيات", nav) }) { padding ->
        val ss = sheekhStats
        if (ss == null) {
            Loading(Modifier.padding(padding)); return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("الإجمالي", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        StatLine("المشايخ", ss.size.toLong())
                        StatLine("الكتب والسلاسل", meta["count_book"]?.toLongOrNull() ?: 0)
                        StatLine("الأشرطة", ss.sumOf { it.chapters.toLong() })
                        StatLine("المقاطع المفهرسة", ss.sumOf { it.segments.toLong() })
                        StatLine("التفريغات النصية", ss.sumOf { it.writes.toLong() })
                        StatLine("التصانيف الفقهية", meta["count_category"]?.toLongOrNull() ?: 0)
                        StatLine("روابط المقاطع بالتصانيف", meta["count_content_cat"]?.toLongOrNull() ?: 0)
                        Text("حجم الصوت الكلي: ${ArabicText.formatSize(ss.sumOf { it.bytes })}", style = MaterialTheme.typography.bodyMedium)
                        if (userStats.second > 0) Text("المحتوى المضاف: ${ArabicText.arabicDigits(userStats.first)} شيخ، ${ArabicText.arabicDigits(userStats.second)} درس، ${ArabicText.arabicDigits(userStats.third)} مقطع", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Text("مصدر البيانات: ${meta["source"] ?: ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(ss, key = { it.sheekh.id }) { s ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { selected = if (selected == s.sheekh.id) 0 else s.sheekh.id }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.sheekh.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "${ArabicText.arabicDigits(s.books)} كتاب — ${ArabicText.arabicDigits(s.chapters)} شريط — ${ArabicText.arabicDigits(s.segments)} مقطع — ${ArabicText.arabicDigits(s.writes)} تفريغ — ${ArabicText.formatSize(s.bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (selected == s.sheekh.id) {
                            Spacer(Modifier.height(6.dp))
                            bookStats.forEach { b ->
                                Text(
                                    "• ${b.book.name}: ${ArabicText.arabicDigits(b.chapters)} شريط، ${ArabicText.arabicDigits(b.segments)} مقطع" + (if (b.writes > 0) "، ${ArabicText.arabicDigits(b.writes)} تفريغ" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun StatLine(label: String, value: Long) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(ArabicText.arabicDigits(value), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun AboutScreen(app: App, repo: Repository, nav: NavHostController) {
    var meta by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) { meta = repo.meta() }
    Scaffold(topBar = { AppTopBar("عن البرنامج", nav) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("أهل الحديث والأثر", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text("نسخة الأندرويد ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "نقلٌ كامل لوظائف برنامج «أهل الحديث والأثر» لنظام ويندوز (الإصدار ${meta["source_version"] ?: "4.14.0"}) الصادر عن موقع alathar.net: تصفح أشرطة المشايخ بحسب السلاسل العلمية، وفهارس المقاطع مع القفز إلى موضعها في الشريط، والتفريغات النصية، والبحث بالكلمات، والتصانيف الفقهية، والمفضلة، وتنزيل الأشرطة.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text("البيانات: ${meta["source"] ?: ""} — بُنيت في ${meta["built_at"] ?: ""}", style = MaterialTheme.typography.bodySmall)
            Text("الإصدار المثبَّت من البيانات: ${app.dataManager.installedVersion}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Text("المشايخ الذين تضمهم الموسوعة: الألباني، وابن باز، والعثيمين، والفوزان، وعبد المحسن العباد، وصالح آل الشيخ، ومحمد أمان الجامي، ومشهور حسن آل سلمان، ومحمد المختار الشنقيطي، ومنصور الخالدي.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text("الحقوق للقائمين على موقع أهل الحديث والأثر (ahl_alhadeeth@hotmail.com). الصوت يُبثّ من خادم الموقع http://www.alathar.net/files/sound/ ما لم يُغيَّر المصدر من الإعدادات.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (BuildConfig.PRIVACY_URL.isNotBlank()) {
                val context = androidx.compose.ui.platform.LocalContext.current
                androidx.compose.material3.TextButton(onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(BuildConfig.PRIVACY_URL))) } }) { Text("سياسة الخصوصية") }
            }
            Spacer(Modifier.height(12.dp))
            Text("الاستخدام على الجهاز:", style = MaterialTheme.typography.titleSmall)
            Text("• اضغط على أي مقطع لتشغيل الشريط من موضعه.\n• اضغط مطوّلًا على المقطع لعرض التفريغ أو إضافته للمفضلة أو نسخه.\n• من الإعدادات يمكنك تغيير مصدر الصوت (خادم أو مجلد على الجهاز) وتنزيل البيانات من جديد.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
