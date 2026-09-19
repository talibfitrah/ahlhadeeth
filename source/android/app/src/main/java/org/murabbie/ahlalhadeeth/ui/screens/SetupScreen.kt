package org.murabbie.ahlalhadeeth.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.BuildConfig
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.DataState

/**
 * شاشة التجهيز الأول: في نسخة النشر يبدأ تنزيل قاعدة البيانات تلقائيًا بلا أي ضغطة،
 * ويُستأنف عند الانقطاع، ويعمل في خدمة خلفية. وتبقى الخيارات اليدوية تحت «خيارات أخرى».
 */
@Composable
fun SetupScreen(app: App, state: DataState) {
    val settings by app.settings.state.collectAsState()
    val countdown by app.dataManager.retryCountdown.collectAsState()
    val userPaused by app.dataManager.userPaused.collectAsState()
    val failures by app.dataManager.failures.collectAsState()
    var manifestUrl by remember { mutableStateOf(settings.manifestUrl) }
    var showAdvanced by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.dataManager.installFromUri(uri)
    }

    // البدء التلقائي عند أول ظهور الشاشة (وإن انقطع سابقًا يُستأنف)
    LaunchedEffect(state is DataState.NotInstalled, userPaused) {
        if (BuildConfig.AUTO_DOWNLOAD && state is DataState.NotInstalled && !userPaused) app.dataManager.autoStartIfNeeded()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("أهل الحديث والأثر", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("الموسوعة الصوتية لدروس أهل العلم وفتاواهم", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        when (state) {
            is DataState.Checking -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("فحص البيانات…")
            }
            is DataState.NotInstalled, is DataState.Error -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        if (BuildConfig.AUTO_DOWNLOAD) {
                            Text("تجهيز التطبيق لأول مرة", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "يُنزَّل الآن تلقائيًا فهرس الموسوعة كاملًا (نحو ١٧٧ م.ب، ويشغل ٥٧٠ م.ب على الجهاز): ١٣٬٧١٦ شريطًا و٢٦٢ ألف مقطع مع التفريغات والتصانيف الفقهية. لا يلزمك فعل شيء؛ إن انقطع الاتصال يُستأنف من حيث توقف، ويمكنك ترك التطبيق وسيصلك إشعار عند الاكتمال. الصوت يُبثّ من الإنترنت عند الاستماع.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text("قاعدة البيانات غير مثبَّتة", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("يحتاج التطبيق إلى تنزيل قاعدة البيانات مرة واحدة (نحو ١٧٧ م.ب مضغوطة، ٥٧٠ م.ب بعد فك الضغط).", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (state is DataState.Error) {
                            Spacer(Modifier.height(8.dp))
                            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            if (BuildConfig.AUTO_DOWNLOAD && !userPaused && countdown > 0) {
                                Text("إعادة المحاولة تلقائيًا خلال ${ArabicText.arabicDigits(countdown)} ثانية" + (if (failures > 1) " (المحاولة ${ArabicText.arabicDigits(failures + 1)})" else ""), style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        if (BuildConfig.AUTO_DOWNLOAD) {
                            if (userPaused) {
                                Text("التنزيل متوقف مؤقتًا.", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { app.dataManager.resumeAuto() }, Modifier.fillMaxWidth()) { Text("متابعة التنزيل") }
                            } else if (state is DataState.Error) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { app.dataManager.resumeAuto() }) { Text("إعادة المحاولة الآن") }
                                    OutlinedButton(onClick = { app.dataManager.pauseAuto() }) { Text("إيقاف مؤقت") }
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.padding(end = 8.dp).height(20.dp), strokeWidth = 2.dp)
                                    Text("جارٍ البدء…")
                                }
                            }
                        } else {
                            Button(onClick = { app.settings.update { it.copy(manifestUrl = manifestUrl.trim()) }; app.dataManager.startDownload(manifestUrl.trim()) }, Modifier.fillMaxWidth()) {
                                Text("تنزيل البيانات")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "إخفاء الخيارات الأخرى" else "خيارات أخرى") }
                        if (showAdvanced) {
                            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
                                Text("تثبيت من ملف قاعدة بيانات على الجهاز (.db أو .db.gz)")
                            }
                            OutlinedTextField(
                                value = manifestUrl,
                                onValueChange = { manifestUrl = it },
                                label = { Text("رابط خادم البيانات (manifest.json)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { app.settings.update { it.copy(manifestUrl = manifestUrl.trim()) }; app.dataManager.resumeAuto() }) { Text("حفظ الرابط والتنزيل") }
                                TextButton(onClick = { manifestUrl = BuildConfig.DEFAULT_MANIFEST_URL; app.settings.update { it.copy(manifestUrl = BuildConfig.DEFAULT_MANIFEST_URL) } }) { Text("الرابط الافتراضي") }
                            }
                            val partial = app.dataManager.partialBytes()
                            if (partial > 0) {
                                Text("أجزاء منزَّلة سابقًا: ${ArabicText.formatSize(partial)} (يُستأنف منها).", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { app.dataManager.clearPartialDownloads() }) { Text("حذف الأجزاء المؤقتة والبدء من جديد") }
                            }
                        }
                    }
                }
            }
            is DataState.Downloading -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("تجهيز التطبيق لأول مرة", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(state.message.ifEmpty { "جارٍ التنزيل…" }, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        if (state.total > 0) {
                            LinearProgressIndicator(progress = { (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text("${ArabicText.formatSize(state.bytes)} من ${ArabicText.formatSize(state.total)} (${ArabicText.arabicDigits((state.bytes * 100 / state.total).toInt())}٪)")
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (state.bytes > 0) Text(ArabicText.formatSize(state.bytes))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("يمكنك ترك التطبيق أو قفل الشاشة؛ يستمر التنزيل في الخلفية ويُستأنف من حيث توقف عند أي انقطاع.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { app.dataManager.pauseAuto() }) { Text("إيقاف مؤقت") }
                    }
                }
            }
            is DataState.Installing -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        if (state.progress > 0f) LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("لا تُغلق التطبيق في هذه الخطوة القصيرة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            is DataState.Ready -> {}
        }
    }
}
