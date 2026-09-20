package org.murabbie.ahlalhadeeth.ui.screens

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.Category
import org.murabbie.ahlalhadeeth.data.Chapter
import org.murabbie.ahlalhadeeth.data.NasHttp
import org.murabbie.ahlalhadeeth.data.PackInfo
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.Sheekh
import org.murabbie.ahlalhadeeth.data.UserContent
import org.murabbie.ahlalhadeeth.data.YouTube
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.ConfirmCard
import org.murabbie.ahlalhadeeth.ui.InlineFormCard
import org.murabbie.ahlalhadeeth.ui.Routes
import java.io.File

/** شاشة إدارة المحتوى المضاف: المشايخ المضافون، الاستيراد والتصدير، الحزم المتاحة */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserContentScreen(app: App, repo: Repository, nav: NavHostController) {
    val uc = app.userDb.content
    val version by uc.version.collectAsState()
    var sheekhs by remember { mutableStateOf<List<Sheekh>>(emptyList()) }
    var stats by remember { mutableStateOf(Triple(0, 0, 0)) }
    var addName by remember { mutableStateOf<String?>(null) }
    var editSheekh by remember { mutableStateOf<Sheekh?>(null) }
    var editName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Sheekh?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    var showUrlImport by remember { mutableStateOf(false) }
    var packs by remember { mutableStateOf<List<PackInfo>>(emptyList()) }
    var installed by remember { mutableStateOf<List<Triple<String, String, Int>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sync by app.sharedSync.state.collectAsState()
    val isAdmin = sync.isAdmin
    var pending by remember { mutableStateOf(0 to 0) }
    var loginUser by remember { mutableStateOf("") }
    var loginPass by remember { mutableStateOf("") }
    var showLogin by remember { mutableStateOf(false) }
    var showPass by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmPublish by remember { mutableStateOf(false) }
    var showChangePin by remember { mutableStateOf(false) }
    var ytUrl by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var newPin2 by remember { mutableStateOf("") }

    LaunchedEffect(version) {
        sheekhs = uc.sheekhs()
        stats = uc.stats()
        installed = uc.installedPacks()
        pending = uc.pendingCounts()
    }
    LaunchedEffect(Unit) {
        packs = app.dataManager.manifest.value?.packs ?: runCatching { app.dataManager.fetchManifest().packs }.getOrDefault(emptyList())
    }

    fun importText(text: String) {
        busy = true
        scope.launch {
            try {
                val r = uc.importPack(text)
                message = "تم الاستيراد: ${ArabicText.arabicDigits(r.sheekhs)} شيخ، ${ArabicText.arabicDigits(r.books)} سلسلة، ${ArabicText.arabicDigits(r.chapters)} درس، ${ArabicText.arabicDigits(r.segments)} مقطع" + (if (r.packName.isNotBlank()) " — ${r.packName}" else "")
            } catch (e: Exception) {
                message = "تعذر الاستيراد: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val text = context.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    importText(text)
                } catch (e: Exception) {
                    message = "تعذر قراءة الملف: ${e.message}"; busy = false
                }
            }
        }
    }

    fun export(usheekh: Int?) {
        scope.launch {
            runCatching {
                val json = uc.exportPack(usheekh, if (usheekh == null) "المحتوى المضاف" else (sheekhs.firstOrNull { -it.id == usheekh }?.name ?: "حزمة"))
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val f = File(dir, (if (usheekh == null) "ahl-alhadeeth-pack" else "pack-$usheekh") + ".json")
                f.writeText(json)
                val u = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                val i = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(i, "تصدير الحزمة"))
            }.onFailure { message = "تعذر التصدير: ${it.message}" }
        }
    }

    val scroll = rememberScrollState()
    val formOpen = addName != null || editSheekh != null || confirmDelete != null || confirmPublish || confirmLogout || showChangePin
    LaunchedEffect(formOpen) { if (formOpen) scroll.animateScrollTo(0) }
    Scaffold(
        topBar = { AppTopBar("المحتوى المضاف", nav, subtitle = if (sync.isSuper) "المشرف العام: ${sync.adminName}" else if (isAdmin) "مشرف: ${sync.adminName}" else "دروس صوتية ومرئية لعلماء آخرين بفهارس وتفريغات") },
        floatingActionButton = { if (isAdmin) FloatingActionButton(onClick = { addName = "" }) { Icon(Icons.Filled.Add, contentDescription = "إضافة شيخ") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // حالة نقل الدروس إلى الخادم (إن وُجد نقل جارٍ أو منتهٍ)
            TransferStatusCard(app, nav)
            // ---- نماذج داخل الشاشة (بلا نوافذ منبثقة) ----
            if (addName != null) InlineFormCard("إضافة شيخ / عالم", onDismiss = { addName = null }) {
                OutlinedTextField(value = addName ?: "", onValueChange = { addName = it }, label = { Text("اسم الشيخ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !addName.isNullOrBlank(), onClick = { val n = addName?.trim() ?: ""; addName = null; if (n.isNotEmpty()) scope.launch { val id = uc.addSheekh(n); nav.navigate(Routes.books(-id)) } }) { Text("إضافة ثم فتح السلاسل") }
                    OutlinedButton(onClick = { addName = null }) { Text("إلغاء") }
                }
            }
            editSheekh?.let { s ->
                InlineFormCard("تعديل اسم «${s.name}»", onDismiss = { editSheekh = null }) {
                    OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = editName.isNotBlank(), onClick = { val n = editName.trim(); editSheekh = null; if (n.isNotEmpty()) scope.launch { uc.updateSheekh(-s.id, n) } }) { Text("حفظ") }
                        OutlinedButton(onClick = { editSheekh = null }) { Text("إلغاء") }
                    }
                }
            }
            confirmDelete?.let { s ->
                ConfirmCard("حذف «${s.name}» وكل سلاسله ودروسه؟", "لا يمكن التراجع. صدّر حزمة أولًا إن أردت الاحتفاظ بنسخة. يُحذف من عند الجميع بعد النشر.", "حذف", onConfirm = { confirmDelete = null; scope.launch { uc.deleteSheekh(-s.id) } }, onDismiss = { confirmDelete = null }, destructive = true)
            }
            if (confirmPublish) ConfirmCard(
                "نشر التغييرات للجميع؟",
                "سيُرفع المحتوى المشترك المحدَّث إلى الخادم، فيصل إلى كل مستخدمي التطبيق حول العالم عند فتحه." + (if (pending.second > 0) "\nيشمل ذلك حذف ${ArabicText.arabicDigits(pending.second)} درس من عند الجميع." else ""),
                "نشر", onConfirm = { confirmPublish = false; scope.launch { runCatching { app.sharedSync.publish() }.onFailure { message = it.message } } }, onDismiss = { confirmPublish = false },
            )
            if (confirmLogout) ConfirmCard(
                "تسجيل خروج المشرف؟",
                if (pending.first > 0 || pending.second > 0) "توجد تغييرات لم تُنشر بعد؛ تبقى على هذا الجهاز ولا تصل إلى الآخرين حتى تدخل وتنشرها." else "يمكنك الدخول مجددًا في أي وقت.",
                "خروج", onConfirm = { confirmLogout = false; app.sharedSync.logout() }, onDismiss = { confirmLogout = false }, destructive = true,
            )
            if (showChangePin) InlineFormCard("تغيير رقمي السري", onDismiss = { showChangePin = false }) {
                OutlinedTextField(value = newPin, onValueChange = { newPin = it }, label = { Text("الرقم السري الجديد (١٢ خانة على الأقل، حروف وأرقام)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = newPin2, onValueChange = { newPin2 = it }, label = { Text("تأكيد الرقم السري") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
                if (newPin.isNotBlank() && newPin != newPin2) Text("الرقمان غير متطابقين", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = org.murabbie.ahlalhadeeth.data.AdminCrypto.normalizePin(newPin).length >= org.murabbie.ahlalhadeeth.data.AdminCrypto.MIN_PIN && newPin == newPin2 && !sync.busy, onClick = {
                        val p = newPin; showChangePin = false; newPin = ""; newPin2 = ""
                        scope.launch { runCatching { app.sharedSync.changeOwnPin(p) }.onSuccess { message = "تم تغيير الرقم السري" }.onFailure { message = "تعذر التغيير: ${it.message}" } }
                    }) { Text("حفظ") }
                    OutlinedButton(onClick = { val p = org.murabbie.ahlalhadeeth.data.AdminCrypto.randomPin(); newPin = p; newPin2 = p; message = "الرقم المولَّد: $p — احفظه قبل الضغط على حفظ" }) { Text("توليد رقم عشوائي") }
                    OutlinedButton(onClick = { showChangePin = false }) { Text("إلغاء") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
            // ---- المحتوى المشترك (لكل المستخدمين) ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("المحتوى المشترك", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("ما ينشره المشرفون من سلاسل ودروس (صوت، فيديو، يوتيوب) بفهارسها وتفريغاتها يصل تلقائيًا إلى كل مستخدمي التطبيق.", style = MaterialTheme.typography.bodySmall)
                    Text("المتاح الآن: ${ArabicText.arabicDigits(stats.first)} شيخ، ${ArabicText.arabicDigits(stats.second)} درس، ${ArabicText.arabicDigits(stats.third)} مقطع" + (if (sync.lastPull > 0) " — آخر تحديث ${formatDateTime(sync.lastPull)}" else ""), style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !sync.busy, onClick = { scope.launch { app.sharedSync.pull(force = true) } }) { Text("تحديث الآن") }
                        if (sync.busy) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    }
                    if (sync.message.isNotBlank()) Text(sync.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            // ---- المشرفون ----
            if (!isAdmin) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("دخول المشرفين", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("إضافة السلاسل والدروس (من ملف أو رابط أو يوتيوب) وفهرستها وتفريغها ونشرها للجميع متاحة للمشرفين فقط. يسجّل المشرفين المشرفُ العام داخل التطبيق ويعطي كل واحد اسم مستخدم ورقمًا سريًا.", style = MaterialTheme.typography.bodySmall)
                        if (!showLogin) {
                            OutlinedButton(onClick = { showLogin = true }) { Text("تسجيل دخول مشرف") }
                        } else {
                            OutlinedTextField(value = loginUser, onValueChange = { loginUser = it }, label = { Text("اسم المستخدم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(
                                value = loginPass, onValueChange = { loginPass = it }, label = { Text("الرقم السري") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "إخفاء" else "إظهار") } },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(enabled = !sync.busy && loginUser.isNotBlank() && loginPass.isNotBlank(), onClick = {
                                    scope.launch { runCatching { app.sharedSync.login(loginUser, loginPass) }.onSuccess { loginPass = ""; showLogin = false } }
                                }) { Text("دخول") }
                                TextButton(onClick = { showLogin = false }) { Text("إلغاء") }
                                if (sync.busy) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("النشر للجميع", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(
                            if (pending.first == 0 && pending.second == 0) "لا تغييرات بانتظار النشر."
                            else "بانتظار النشر: ${ArabicText.arabicDigits(pending.first)} درس مضاف أو معدَّل" + (if (pending.second > 0) " و${ArabicText.arabicDigits(pending.second)} حذف" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (sync.lastPublish > 0) Text("آخر نشر: ${formatDateTime(sync.lastPublish)}", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(enabled = !sync.busy && !busy, onClick = { confirmPublish = true }) { Text("نشر التغييرات للجميع") }
                            if (sync.isSuper) OutlinedButton(onClick = { nav.navigate(Routes.ADMINS) }) { Text("المشرفون") }
                            TextButton(onClick = { showChangePin = true }) { Text("رقمي السري") }
                            TextButton(onClick = { confirmLogout = true }) { Text("خروج") }
                        }
                        Text("عند النشر تُدمج آخر نسخة من الخادم مع ما أضفته أو عدّلته أو حذفته، ثم تُرفع فيراها كل المستخدمين عند فتح التطبيق.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("إضافة من يوتيوب", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("الصق رابط فيديو أو قائمة تشغيل كاملة؛ تُقرأ كل فيديوهاتها بعناوينها ثم تختار الشيخ والسلسلة (أو تنشئهما).", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = ytUrl, onValueChange = { ytUrl = it }, singleLine = true, label = { Text("رابط يوتيوب") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = YouTube.isYouTubeUrl(ytUrl), onClick = { nav.navigate(Routes.ytImport(0, 0, ytUrl.trim())) }) { Text("قراءة وإضافة") }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("كيف تضيف محتوى؟", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "١. أضف الشيخ بزر (+)، ثم افتحه وأضف سلسلة، ثم أضف داخلها درسًا: رابط فيديو يوتيوب، أو قائمة تشغيل كاملة من يوتيوب، أو ملف على الجهاز، أو رابط مباشر (mp3 / mp4 / HLS).\n" +
                            "٢. في شاشة الدرس أضف مواضيعه بالأزمنة (زر «+ موضع» أثناء التشغيل يلتقط الزمن الحالي) واكتب تفريغ كل موضع، أو الصق فهرسًا نصيًا جاهزًا.\n" +
                            "٣. اضغط «نشر التغييرات للجميع» ليصل ما أضفته إلى كل المستخدمين حول العالم.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (sheekhs.isEmpty()) Text(if (isAdmin) "لم يُضَف شيء بعد." else "لا محتوى مشترك بعد.", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            sheekhs.forEach { s ->
                ListItem(
                    headlineContent = { Text(s.name) },
                    leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
                    trailingContent = {
                        if (isAdmin) Row {
                            IconButton(onClick = { editSheekh = s; editName = s.name }) { Icon(Icons.Filled.Edit, contentDescription = "تعديل") }
                            IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Filled.Delete, contentDescription = "حذف") }
                        }
                    },
                    modifier = Modifier.combinedClickable(onClick = { nav.navigate(Routes.books(s.id)) }, onLongClick = { if (isAdmin) export(-s.id) }),
                )
                HorizontalDivider()
            }
            if (isAdmin) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("استيراد وتصدير الحزم", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("الحزمة ملف JSON بصيغة التطبيق (ahl-alhadeeth-pack) يحوي مشايخ وسلاسل ودروسًا بمواضيعها وتفريغاتها؛ يمكن إعداده على الحاسوب أو تصديره من هنا ونشره للآخرين.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("استيراد من ملف") }
                        OutlinedButton(enabled = !busy, onClick = { showUrlImport = !showUrlImport }) { Text("استيراد من رابط") }
                        OutlinedButton(enabled = sheekhs.isNotEmpty(), onClick = { export(null) }) { Text("تصدير الكل") }
                    }
                    if (showUrlImport) {
                        OutlinedTextField(value = importUrl, onValueChange = { importUrl = it }, singleLine = true, label = { Text("رابط ملف الحزمة (JSON)") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && importUrl.startsWith("http"), onClick = {
                            if (YouTube.isYouTubeUrl(importUrl)) { nav.navigate(Routes.ytImport(0, 0, importUrl.trim())); return@Button }
                            busy = true
                            scope.launch {
                                try {
                                    val text = NasHttp.fetchText(importUrl.trim())
                                    if (!text.trimStart().startsWith("{")) throw IllegalArgumentException("الرابط لا يشير إلى ملف حزمة JSON")
                                    importText(text)
                                } catch (e: NasHttp.HttpException) { message = if (e.code == 200) "الرابط يعطي صفحة ويب لا ملف حزمة JSON" else "تعذر التنزيل: ${e.message}"; busy = false }
                                catch (e: Exception) { message = "تعذر التنزيل: ${e.message}"; busy = false }
                            }
                        }) { Text("تنزيل واستيراد") }
                        Text("لإضافة دروس من يوتيوب استعمل بطاقة «إضافة من يوتيوب» أعلاه، لا هذا الحقل.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("جارٍ العمل…") }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (packs.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("حزم متاحة من خادم البيانات", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        packs.forEach { p ->
                            val inst = installed.firstOrNull { it.first == p.id }
                            ListItem(
                                headlineContent = { Text(p.name) },
                                supportingContent = { Text(p.description + (if (p.size > 0) " — ${ArabicText.formatSize(p.size)}" else "") + (if (inst != null) " — مثبَّتة (إصدار ${ArabicText.arabicDigits(inst.third)})" else ""), style = MaterialTheme.typography.labelMedium) },
                                trailingContent = {
                                    TextButton(enabled = !busy, onClick = {
                                        busy = true
                                        scope.launch { try { importText(NasHttp.fetchText(p.url)) } catch (e: Exception) { message = "تعذر التنزيل: ${e.message}"; busy = false } }
                                    }) { Text(if (inst == null) "تثبيت" else if (inst.third < p.version) "تحديث" else "إعادة التثبيت") }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }

}

/** تاريخ ووقت بأرقام عربية */
fun formatDateTime(ms: Long): String =
    ArabicText.arabicDigits(java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date(ms)))

/** إضافة/تعديل درس (صوتي أو مرئي) في سلسلة مضافة */
@Composable
fun EditTapeScreen(app: App, repo: Repository, nav: NavHostController, sheekhId: Int, bookId: Int, code: Int) {
    val uc = app.userDb.content
    var title by remember { mutableStateOf("") }
    var mediaUri by remember { mutableStateOf("") }
    var isVideo by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var pickedName by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(code == 0) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(code) {
        if (code < 0) {
            repo.chapter(code)?.let { ch -> title = ch.title; mediaUri = ch.mediaUri; isVideo = ch.isVideo; notes = ch.notes }
            loaded = true
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            mediaUri = uri.toString()
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                }
            }.getOrNull() ?: ""
            pickedName = name
            val mime = context.contentResolver.getType(uri) ?: ""
            isVideo = mime.startsWith("video") || Regex("\\.(mp4|m4v|webm|mkv|mov)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
            if (title.isBlank() && name.isNotBlank()) title = name.substringBeforeLast('.')
        }
    }

    Scaffold(topBar = { AppTopBar(if (code < 0) "تعديل الدرس" else "إضافة درس", nav) }) { padding ->
        if (!loaded) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("عنوان الدرس") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("مصدر الصوت أو الفيديو", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }) { Text("اختيار ملف من الجهاز") }
                if (code == 0) OutlinedButton(onClick = { nav.navigate(Routes.ytImport(sheekhId, bookId)) }) { Text("قائمة تشغيل من يوتيوب") }
            }
            if (pickedName.isNotBlank()) Text("الملف: $pickedName", style = MaterialTheme.typography.labelMedium)
            val ytId = YouTube.extractId(mediaUri)
            OutlinedTextField(
                value = mediaUri, onValueChange = { mediaUri = it; isVideo = isVideo || YouTube.extractId(it) != null || Regex("\\.(mp4|m4v|webm|mkv|mov)($|\\?)", RegexOption.IGNORE_CASE).containsMatchIn(it) },
                label = { Text("أو رابط: فيديو يوتيوب، أو ملف مباشر (mp3 / mp4 / m3u8)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            if (ytId != null) {
                Text("رابط يوتيوب ✓ — يُشغَّل بمشغّل يوتيوب المضمَّن مع فهرس المواضع.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (title.isBlank()) TextButton(onClick = { scope.launch { YouTube.fetchTitle(ytId)?.let { if (title.isBlank()) title = it } } }) { Text("جلب العنوان من يوتيوب") }
            } else {
                Text("يُقبل رابط فيديو يوتيوب (watch / youtu.be)، أو رابط ملف وسائط مباشر؛ صفحات الويب الأخرى لا تعمل.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = isVideo, onCheckedChange = { isVideo = it })
                Spacer(Modifier.width(8.dp))
                Text(if (isVideo) "درس مرئي (فيديو)" else "درس صوتي")
            }
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("ملاحظات (اختياري)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                if (title.isBlank()) { error = "اكتب عنوان الدرس"; return@Button }
                if (mediaUri.isBlank()) { error = "اختر ملفًا أو اكتب رابطًا"; return@Button }
                scope.launch {
                    if (code < 0) {
                        uc.updateChapter(-code, title, mediaUri, isVideo, notes)
                        nav.popBackStack()
                    } else {
                        val id = uc.addChapter(-sheekhId, -bookId, title, mediaUri, isVideo, notes)
                        nav.popBackStack()
                        if (YouTube.extractId(mediaUri) != null) nav.navigate(Routes.yt(-id)) else nav.navigate(Routes.tape(-id))
                    }
                }
            }) { Text(if (code < 0) "حفظ" else "إضافة الدرس ثم فهرسة مواضيعه") }
        }
    }
}

/** إضافة/تعديل موضع (مقطع) في درس مضاف: الزمن والعنوان والتفريغ والتصانيف */
@Composable
fun EditSegmentScreen(app: App, repo: Repository, nav: NavHostController, code: Int, segmentId: Long, startMs: Long = 0) {
    val uc = app.userDb.content
    val playerState by app.player.state.collectAsState()
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    var timeText by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    var write by remember { mutableStateOf("") }
    var ques by remember { mutableStateOf(false) }
    var hnum by remember { mutableStateOf("") }
    var cats by remember { mutableStateOf<List<Category>>(emptyList()) }
    var showCatPicker by remember { mutableStateOf(false) }
    var catQuery by remember { mutableStateOf("") }
    var catHits by remember { mutableStateOf<List<Category>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(code, segmentId) {
        chapter = repo.chapter(code)
        if (segmentId < 0) {
            repo.segment(segmentId)?.let { s ->
                timeText = ArabicText.formatTime(s.offsetStart); line = s.line; write = repo.write(s.id) ?: ""; ques = s.ques; hnum = if (s.hnum > 0) s.hnum.toString() else ""
                cats = repo.categoriesOfSegment(s.id)
            }
        } else {
            val playing = playerState.chapter?.code == code
            timeText = ArabicText.formatTime(if (startMs > 0) startMs else if (playing) playerState.positionMs else 0L)
        }
        loaded = true
    }
    LaunchedEffect(catQuery) { catHits = if (catQuery.trim().length >= 2) repo.searchCategories(catQuery.trim()).take(40) else emptyList() }

    Scaffold(topBar = {
        AppTopBar(if (segmentId < 0) "تعديل الموضع" else "إضافة موضع", nav, subtitle = chapter?.displayTitle, actions = {
            if (segmentId < 0) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "حذف") }
        })
    }) { padding ->
        if (!loaded) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = timeText, onValueChange = { timeText = it }, label = { Text("زمن البداية (س:دد:ثث)") }, singleLine = true, modifier = Modifier.width(180.dp))
                if (playerState.chapter?.code == code) OutlinedButton(onClick = { timeText = ArabicText.formatTime(playerState.positionMs) }) { Text("الزمن الحالي ${ArabicText.formatTime(playerState.positionMs)}") }
            }
            OutlinedTextField(value = line, onValueChange = { line = it }, label = { Text("عنوان الموضوع / السؤال") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = ques, onCheckedChange = { ques = it }); Text("سؤال")
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(value = hnum, onValueChange = { hnum = it.filter { c -> c.isDigit() || c in '٠'..'٩' } }, label = { Text("رقم الحديث") }, singleLine = true, modifier = Modifier.width(140.dp))
            }
            OutlinedTextField(value = write, onValueChange = { write = it }, label = { Text("التفريغ النصي لهذا الموضع (اختياري)") }, modifier = Modifier.fillMaxWidth(), minLines = 6)
            Text("التصانيف الفقهية", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cats.forEach { c -> FilterChip(selected = true, onClick = { cats = cats - c }, label = { Text(c.name + " ✕") }) }
                FilterChip(selected = showCatPicker, onClick = { showCatPicker = !showCatPicker }, label = { Text(if (showCatPicker) "إخفاء البحث" else "+ تصنيف") })
            }
            if (showCatPicker) InlineFormCard("اختيار تصنيف", onDismiss = { showCatPicker = false }) {
                OutlinedTextField(value = catQuery, onValueChange = { catQuery = it }, singleLine = true, label = { Text("ابحث باسم التصنيف") }, modifier = Modifier.fillMaxWidth())
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (catHits.isEmpty() && catQuery.trim().length >= 2) Text("لا نتائج", style = MaterialTheme.typography.bodySmall)
                    catHits.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { cats = if (cats.any { it.id == c.id }) cats.filter { it.id != c.id } else cats + c }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = cats.any { it.id == c.id }, onCheckedChange = { on -> cats = if (on) cats + c else cats.filter { it.id != c.id } })
                            Text(c.name)
                        }
                    }
                }
            }
            if (confirmDelete) ConfirmCard("حذف هذا الموضع؟", null, "حذف", onConfirm = { confirmDelete = false; scope.launch { uc.deleteSegment(-segmentId); nav.popBackStack() } }, onDismiss = { confirmDelete = false }, destructive = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                val t = UserContent.parseTime(timeText)
                if (t == null) { error = "الزمن غير مفهوم؛ اكتبه مثل ٠:١٢:٣٠"; return@Button }
                if (line.isBlank()) { error = "اكتب عنوان الموضوع"; return@Button }
                val hn = ArabicText.normalize(hnum).toIntOrNull() ?: 0
                scope.launch {
                    if (segmentId < 0) {
                        uc.updateSegment(-segmentId, line, t, write, ques, hn)
                        uc.setSegmentCategories(-segmentId, cats.map { it.id })
                    } else {
                        uc.addSegment(-code, line, t, write, ques, hn, cats.map { it.id })
                    }
                    nav.popBackStack()
                }
            }) { Text("حفظ") }
        }
    }
}

/** لصق فهرس نصي جاهز لدرس مضاف */
@Composable
fun PasteIndexScreen(app: App, repo: Repository, nav: NavHostController, code: Int) {
    val uc = app.userDb.content
    var text by remember { mutableStateOf("") }
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(code) { chapter = repo.chapter(code) }
    val parsed = remember(text) { if (text.isBlank()) emptyList() else UserContent.parseIndexText(text) }
    Scaffold(topBar = { AppTopBar("لصق فهرس نصي", nav, subtitle = chapter?.displayTitle) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "كل سطر يبدأ بزمن ثم عنوان الموضوع، وما بعده من أسطر إلى الزمن التالي هو تفريغ ذلك الموضع. مثال:\n٠:٠٠:٢٧ - مقدمة الدرس\n نص التفريغ…\n٠:١٢:٣٠ - ما حكم كذا؟\nيُعدّ العنوان المنتهي بعلامة استفهام سؤالًا، و«حديث ١٢» في أوله رقم حديث.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("الفهرس") })
            Text(if (parsed.isEmpty()) "لم يُتعرَّف على مواضع بعد" else "سيُضاف ${ArabicText.arabicDigits(parsed.size)} موضعًا (${ArabicText.arabicDigits(parsed.count { it.write != null })} منها بتفريغ)", style = MaterialTheme.typography.labelMedium)
            Button(enabled = parsed.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = { scope.launch { uc.addSegments(-code, parsed); nav.popBackStack() } }) { Text("إضافة المواضع") }
        }
    }
}
