package org.murabbie.ahlalhadeeth.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.BuildConfig
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.AudioServer
import org.murabbie.ahlalhadeeth.data.Manifest
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.ui.AppTopBar

@Composable
fun SettingsScreen(app: App, repo: Repository, nav: NavHostController) {
    val settings by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var manifestUrl by remember { mutableStateOf(settings.manifestUrl) }
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }
    var manifest by remember { mutableStateOf<Manifest?>(null) }
    var confirmRedownload by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addServer by remember { mutableStateOf(false) }
    var newServerTitle by remember { mutableStateOf("") }
    var newServerUrl by remember { mutableStateOf("") }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.settings.update { it.copy(localAudioTreeUri = uri.toString()) }
        }
    }

    Scaffold(topBar = { AppTopBar("الإعدادات", nav) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // ---------- البيانات ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("قاعدة البيانات", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("الإصدار المثبَّت: ${app.dataManager.installedVersion.ifBlank { "غير معروف" }} — الحجم: ${ArabicText.formatSize(app.dataManager.dbFile.length())}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = manifestUrl, onValueChange = { manifestUrl = it }, singleLine = true,
                        label = { Text("رابط ملف الوصف على NAS (manifest.json)") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { manifestUrl = BuildConfig.DEFAULT_MANIFEST_URL; app.settings.update { it.copy(manifestUrl = BuildConfig.DEFAULT_MANIFEST_URL) } }) { Text("الافتراضي") }
                        Button(enabled = !checking, onClick = {
                            app.settings.update { it.copy(manifestUrl = manifestUrl.trim()) }
                            checking = true; checkResult = null
                            scope.launch {
                                try {
                                    val m = app.dataManager.fetchManifest(manifestUrl.trim())
                                    manifest = m
                                    val sb = StringBuilder()
                                    val dv = m.data?.version ?: ""
                                    sb.append("إصدار البيانات على NAS: ").append(dv.ifBlank { "غير محدد" })
                                    sb.append(if (dv.isNotBlank() && dv != app.dataManager.installedVersion) " (يوجد تحديث)" else " (مطابق للمثبَّت)")
                                    m.app?.let { a ->
                                        sb.append("\nإصدار التطبيق على NAS: ").append(a.versionName)
                                        if (a.versionCode > BuildConfig.VERSION_CODE) sb.append(" (أحدث من نسختك ${BuildConfig.VERSION_NAME})")
                                    }
                                    checkResult = sb.toString()
                                } catch (e: Exception) {
                                    checkResult = "تعذر الوصول إلى NAS: ${e.message}"
                                } finally {
                                    checking = false
                                }
                            }
                        }) { Text(if (checking) "جارٍ الفحص…" else "فحص التحديثات") }
                    }
                    checkResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    val rel = manifest?.app
                    if (rel != null && rel.versionCode > BuildConfig.VERSION_CODE) {
                        val url = rel.apkArm64.ifBlank { rel.apkUniversal }
                        if (url.isNotBlank()) OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("تنزيل نسخة التطبيق الجديدة ${rel.versionName}") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmRedownload = true }) { Text("إعادة تنزيل البيانات") }
                        TextButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = null); Text("حذف البيانات") }
                    }
                }
            }

            // ---------- مصدر الصوت ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("مصدر الصوت", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("ترتيب البحث عن الملف: الملفات المنزَّلة، ثم المجلد المحلي، ثم الخادم المختار؛ وإن تعذر الملف على الخادم المختار جُلب تلقائيًا من الخوادم الأخرى.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    settings.audioServers.forEachIndexed { i, sv ->
                        Row(Modifier.fillMaxWidth().clickable { app.settings.update { it.copy(audioServerIndex = i, audioServerChosen = true) } }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = settings.audioServerIndex == i, onClick = { app.settings.update { it.copy(audioServerIndex = i, audioServerChosen = true) } })
                            Column(Modifier.weight(1f)) {
                                Text(sv.title, style = MaterialTheme.typography.bodyMedium)
                                Text(if (sv.url.contains("{path}")) "خادم البيانات (مشاركة مجلد) — " + Uri.parse(sv.url).host.orEmpty() else sv.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            if (i > 0) IconButton(onClick = {
                                app.settings.update { s ->
                                    val list = s.audioServers.toMutableList(); list.removeAt(i)
                                    s.copy(audioServers = list, audioServerIndex = s.audioServerIndex.coerceIn(0, list.size - 1))
                                }
                            }) { Icon(Icons.Filled.Delete, contentDescription = "حذف الخادم") }
                        }
                    }
                    TextButton(onClick = { addServer = true }) { Text("إضافة خادم صوت (رابط أساس)") }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("مجلد صوت على الجهاز (بنية المجلدات كما في أقراص البرنامج: مثل alalbani/alnoor/001.mp3)", style = MaterialTheme.typography.bodySmall)
                    Text(if (settings.localAudioTreeUri.isBlank()) "لم يُختر مجلد" else Uri.decode(settings.localAudioTreeUri.substringAfterLast("/")), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { treePicker.launch(null) }) { Text("اختيار مجلد") }
                        if (settings.localAudioTreeUri.isNotBlank()) TextButton(onClick = { app.settings.update { it.copy(localAudioTreeUri = "") } }) { Text("إلغاء") }
                    }
                    OutlinedTextField(
                        value = settings.audioExt, onValueChange = { v -> app.settings.update { it.copy(audioExt = v.trim()) } }, singleLine = true,
                        label = { Text("امتداد ملفات الصوت") }, modifier = Modifier.width(160.dp),
                    )
                }
            }

            // ---------- التنزيل ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("تنزيل الأشرطة", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    SwitchRow("حفظ التنزيلات في الذاكرة الخارجية (Android/data)", settings.downloadToExternal) { v -> app.settings.update { it.copy(downloadToExternal = v) } }
                    Text("المجلد الحالي: ${app.audioDownloads.audioSource.downloadsDir().absolutePath}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SwitchRow("التنزيل عبر Wi-Fi فقط", settings.wifiOnlyDownloads) { v -> app.settings.update { it.copy(wifiOnlyDownloads = v) } }
                }
            }

            // ---------- الصيانة: الذاكرة المؤقتة وسجل الأعطال ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("الصيانة", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    var cacheSize by remember { mutableStateOf(-1L) }
                    var crash by remember { mutableStateOf<String?>(null) }
                    var maintMsg by remember { mutableStateOf("") }
                    LaunchedEffect(maintMsg) {
                        cacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { org.murabbie.ahlalhadeeth.data.CacheCleaner.cacheSize(context) }
                        crash = org.murabbie.ahlalhadeeth.data.CrashLog.read(context)
                    }
                    Text("الذاكرة المؤقتة (ملفات النقل والتفريغ المؤقتة ومخلفات مشغّل يوتيوب): " + (if (cacheSize < 0) "…" else ArabicText.formatSize(cacheSize)), style = MaterialTheme.typography.bodySmall)
                    SwitchRow("تنظيف الذاكرة المؤقتة تلقائيًا (عند فتح التطبيق وبعد كل نقل)", settings.autoCleanCache) { v -> app.settings.update { it.copy(autoCleanCache = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val freed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { org.murabbie.ahlalhadeeth.data.CacheCleaner.clearAll(context, app.transfer.keepFiles()) }
                                maintMsg = "حُرِّر ${ArabicText.formatSize(freed)} (${ArabicText.arabicDigits(System.currentTimeMillis() % 1000)})"
                            }
                        }) { Text("مسح الذاكرة المؤقتة الآن") }
                    }
                    if (maintMsg.isNotBlank()) Text(maintMsg.substringBefore(" ("), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    val c = crash
                    if (c == null) Text("سجل الأعطال: لا أعطال مسجَّلة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        Text("سجل الأعطال (آخر عطل أولًا) — انسخه وأرسله للمطوّر:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Text(c.take(1500), style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 18, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", c))
                                android.widget.Toast.makeText(context, "نُسخ السجل", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text("نسخ السجل") }
                            OutlinedButton(onClick = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "سجل أعطال أهل الحديث والأثر").putExtra(Intent.EXTRA_TEXT, c), "إرسال السجل"))
                            }) { Text("إرسال") }
                            TextButton(onClick = { org.murabbie.ahlalhadeeth.data.CrashLog.clear(context); maintMsg = "مُسح السجل (${ArabicText.arabicDigits(System.currentTimeMillis() % 1000)})" }) { Text("مسح السجل") }
                        }
                    }
                }
            }

            // ---------- التشغيل ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("التشغيل", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    SwitchRow("الانتقال التلقائي إلى الشريط التالي في الكتاب", settings.autoPlayNext) { v -> app.settings.update { it.copy(autoPlayNext = v) } }
                    SwitchRow("إبقاء الشاشة مضاءة", settings.keepScreenOn) { v -> app.settings.update { it.copy(keepScreenOn = v) } }
                    SwitchRow("عرض التفريغ تلقائيًا مع فهرس الشريط وفي المشغّل", settings.showTranscriptInline) { v -> app.settings.update { it.copy(showTranscriptInline = v) } }
                }
            }

            // ---------- المظهر ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("المظهر والخط", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = settings.themeMode == 0, onClick = { app.settings.update { it.copy(themeMode = 0) } }, label = { Text("حسب النظام") })
                        FilterChip(selected = settings.themeMode == 1, onClick = { app.settings.update { it.copy(themeMode = 1) } }, label = { Text("فاتح") })
                        FilterChip(selected = settings.themeMode == 2, onClick = { app.settings.update { it.copy(themeMode = 2) } }, label = { Text("داكن") })
                    }
                    Text("حجم الخط: ${ArabicText.arabicDigits((settings.fontScale * 100).toInt())}٪", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = settings.fontScale, onValueChange = { v -> app.settings.update { it.copy(fontScale = v) } }, valueRange = 0.8f..1.6f, steps = 7)
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    if (confirmRedownload) {
        AlertDialog(
            onDismissRequest = { confirmRedownload = false },
            title = { Text("إعادة تنزيل قاعدة البيانات") },
            text = { Text("سيُعاد تنزيل قاعدة البيانات (نحو ١٧٧ م.ب) من NAS وتُستبدل الحالية. المفضلة والتنزيلات لا تتأثر.") },
            confirmButton = { TextButton(onClick = { confirmRedownload = false; app.settings.update { it.copy(manifestUrl = manifestUrl.trim()) }; app.dataManager.startDownload(manifestUrl.trim()) }) { Text("تنزيل") } },
            dismissButton = { TextButton(onClick = { confirmRedownload = false }) { Text("إلغاء") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف قاعدة البيانات؟") },
            text = { Text("سيعود التطبيق إلى شاشة التثبيت. المفضلة والتنزيلات تبقى.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; app.dataManager.deleteData() }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } },
        )
    }
    if (addServer) {
        AlertDialog(
            onDismissRequest = { addServer = false },
            title = { Text("إضافة خادم صوت") },
            text = {
                Column {
                    OutlinedTextField(value = newServerTitle, onValueChange = { newServerTitle = it }, label = { Text("الاسم") }, singleLine = true)
                    OutlinedTextField(value = newServerUrl, onValueChange = { newServerUrl = it }, label = { Text("الرابط الأساس (ينتهي بـ /)") }, singleLine = true, placeholder = { Text("https://nas.example.org/sound/") })
                    Text("يُلحق بالرابط مسار الملف مثل alalbani/alnoor/001.mp3", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = newServerUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
                    if (u.startsWith("http")) {
                        app.settings.update { s -> s.copy(audioServers = s.audioServers + AudioServer(newServerTitle.ifBlank { u }, u), audioServerIndex = s.audioServers.size, audioServerChosen = true) }
                    }
                    addServer = false; newServerTitle = ""; newServerUrl = ""
                }) { Text("إضافة") }
            },
            dismissButton = { TextButton(onClick = { addServer = false }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
