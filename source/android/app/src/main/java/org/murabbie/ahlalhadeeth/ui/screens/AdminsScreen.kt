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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.murabbie.ahlalhadeeth.App
import org.murabbie.ahlalhadeeth.data.AdminCrypto
import org.murabbie.ahlalhadeeth.data.AdminRegistry
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.ui.AppTopBar
import org.murabbie.ahlalhadeeth.ui.ConfirmCard
import org.murabbie.ahlalhadeeth.ui.InlineFormCard
import org.murabbie.ahlalhadeeth.ui.Loading

/** إدارة المشرفين (للمشرف العام): تسجيل المشرفين وأرقامهم السرية وإيقافهم وحذفهم، وحساب الخادم */
@Composable
fun AdminsScreen(app: App, repo: Repository, nav: NavHostController) {
    val sync by app.sharedSync.state.collectAsState()
    var list by remember { mutableStateOf<List<AdminRegistry.Entry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addUser by remember { mutableStateOf("") }
    var userEdited by remember { mutableStateOf(false) }
    var addPin by remember { mutableStateOf(AdminCrypto.randomPin(8)) }
    var reveal by remember { mutableStateOf<Triple<String, String, String>?>(null) } // name, user, pin
    var confirmRemove by remember { mutableStateOf<AdminRegistry.Entry?>(null) }
    var confirmReset by remember { mutableStateOf<AdminRegistry.Entry?>(null) }
    var showServer by remember { mutableStateOf(false) }
    var serverUser by remember { mutableStateOf("") }
    var serverPass by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val formOpen = showAdd || reveal != null || confirmReset != null || confirmRemove != null || showServer
    LaunchedEffect(formOpen) { if (formOpen) scroll.animateScrollTo(0) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    fun reload() {
        scope.launch { runCatching { list = app.sharedSync.admins(); error = null }.onFailure { error = it.message } }
    }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(sync.isSuper) { if (!sync.isSuper) nav.popBackStack() }

    fun shareText(name: String, user: String, pin: String) =
        "تطبيق أهل الحديث والأثر — بيانات دخول المشرف\nالاسم: $name\nاسم المستخدم: $user\nالرقم السري: ${ArabicText.arabicDigits(pin)}\nالدخول من: المزيد ← المحتوى المضاف ← تسجيل دخول مشرف"

    Scaffold(
        topBar = { AppTopBar("المشرفون", nav, subtitle = "المشرف العام: ${sync.adminName}") },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { addName = ""; addUser = ""; userEdited = false; addPin = AdminCrypto.randomPin(8); showAdd = true }) { Text("+ مشرف") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- نماذج داخل الشاشة ----
            if (showAdd) InlineFormCard("تسجيل مشرف", onDismiss = { showAdd = false }) {
                OutlinedTextField(value = addName, onValueChange = { addName = it; if (!userEdited) addUser = it.filter { c -> !c.isWhitespace() } }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = addUser, onValueChange = { addUser = it.filter { c -> !c.isWhitespace() }; userEdited = true }, label = { Text("اسم المستخدم (بلا فراغات)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = addPin, onValueChange = { addPin = it.filter { c -> c.isDigit() } }, label = { Text("الرقم السري (أرقام فقط)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = { TextButton(onClick = { addPin = AdminCrypto.randomPin(8) }) { Text("توليد") } })
                Text("سيظهر الرقم السري بعد الحفظ لتبلّغه للمشرف.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Button(enabled = !sync.busy && addName.isNotBlank() && addUser.isNotBlank() && AdminCrypto.normalizePin(addPin).length >= 4, onClick = {
                        val n = addName.trim(); val u = addUser.trim(); val p = addPin
                        showAdd = false
                        scope.launch { runCatching { app.sharedSync.addAdmin(n, u, p) }.onSuccess { pin -> reveal = Triple(n, u, pin); reload() }.onFailure { error = it.message } }
                    }) { Text("حفظ") }
                    OutlinedButton(onClick = { showAdd = false }) { Text("إلغاء") }
                }
            }
            reveal?.let { (n, u, p) ->
                InlineFormCard("بيانات دخول المشرف «$n»", onDismiss = { reveal = null }) {
                    Text("أبلغه بهذه البيانات؛ لن يظهر الرقم السري مرة أخرى (يمكن توليد رقم جديد في أي وقت من زر المفتاح).", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Column {
                            Text("اسم المستخدم: $u", style = MaterialTheme.typography.titleMedium)
                            Text("الرقم السري: ${ArabicText.arabicDigits(p)}", style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(shareText(n, u, p))) }) { Text("نسخ") }
                        OutlinedButton(onClick = {
                            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(n, u, p))
                            context.startActivity(Intent.createChooser(i, "إرسال بيانات الدخول"))
                        }) { Text("إرسال") }
                        TextButton(onClick = { reveal = null }) { Text("تم") }
                    }
                }
            }
            confirmReset?.let { e ->
                ConfirmCard("رقم سري جديد لـ «${e.name}»؟", "يُلغى رقمه الحالي فورًا ويُولَّد رقم جديد تبلّغه به.", "توليد", onConfirm = {
                    confirmReset = null
                    scope.launch { runCatching { app.sharedSync.resetPin(e.user) }.onSuccess { pin -> reveal = Triple(e.name, e.user, pin); reload() }.onFailure { error = it.message } }
                }, onDismiss = { confirmReset = null })
            }
            confirmRemove?.let { e ->
                ConfirmCard("حذف المشرف «${e.name}»؟", "لن يستطيع الدخول أو النشر بعد الآن. ما نشره سابقًا يبقى في المحتوى المشترك.", "حذف", onConfirm = {
                    confirmRemove = null
                    scope.launch { runCatching { app.sharedSync.removeAdmin(e.user) }.onFailure { error = it.message }; reload() }
                }, onDismiss = { confirmRemove = null }, destructive = true)
            }
            if (showServer) InlineFormCard("مفاتيح الخادم والذكاء الاصطناعي", onDismiss = { showServer = false }) {
                Text("حساب خادم البيانات (NAS) الذي يرفع به التطبيق ملفات النشر، ومفتاح Gemini (اختياري) للتفريغ والفهرسة التلقائيين. بعد الحفظ يحتاج كل المشرفين إلى أرقام سرية جديدة (زر المفتاح أمام كل مشرف).", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = serverUser, onValueChange = { serverUser = it }, label = { Text("اسم مستخدم الخادم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = serverPass, onValueChange = { serverPass = it }, label = { Text("كلمة سر الخادم") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(value = geminiKey, onValueChange = { geminiKey = it.trim() }, label = { Text("مفتاح Gemini API (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("يُحصل على مفتاح Gemini مجانًا من Google AI Studio (aistudio.google.com ← Get API key). بدونه تعمل فهرسة دروس يوتيوب باستنباط آلي مبسط، ولا يعمل تفريغ الملفات الصوتية.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Button(enabled = !sync.busy && serverUser.isNotBlank() && serverPass.isNotBlank(), onClick = {
                        val u = serverUser; val p = serverPass; val g = geminiKey; showServer = false
                        scope.launch { runCatching { app.sharedSync.changeServerAccount(u, p, g) }.onSuccess { error = null }.onFailure { error = it.message }; reload() }
                    }) { Text("حفظ المفاتيح") }
                    OutlinedButton(onClick = { showServer = false }) { Text("إلغاء") }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("نظام المشرفين", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("أنت تسجّل المشرفين هنا وتعطي كل واحد اسم مستخدم ورقمًا سريًا يدخل به من جهازه، فيستطيع إضافة السلاسل والدروس والروابط ونشرها للجميع. الأرقام السرية لا تُخزَّن بنصها في أي مكان؛ فمن نسي رقمه أعطه رقمًا جديدًا من هنا.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !sync.busy, onClick = { reload() }) { Text("تحديث القائمة") }
                        if (sync.busy) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            val l = list
            if (l == null && error == null) Loading(Modifier.height(80.dp))
            else if (l != null && l.isEmpty()) Text("لا مشرفون بعد — اضغط «+ مشرف» لتسجيل أول مشرف.", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            l?.forEach { e ->
                ListItem(
                    headlineContent = { Text(e.name + (if (!e.active) " (موقوف)" else "") + (if (e.needsPin) " — يحتاج رقمًا سريًا جديدًا" else ""), color = if (e.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("اسم المستخدم: ${e.user}" + (if (e.created > 0) " — سُجّل ${formatDateTime(e.created)}" else ""), style = MaterialTheme.typography.labelMedium) },
                    leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
                    trailingContent = {
                        Row {
                            IconButton(enabled = !sync.busy, onClick = { confirmReset = e }) { Icon(Icons.Filled.Key, contentDescription = "رقم سري جديد") }
                            IconButton(enabled = !sync.busy, onClick = { scope.launch { runCatching { app.sharedSync.setAdminActive(e.user, !e.active) }.onFailure { error = it.message }; reload() } }) {
                                Icon(if (e.active) Icons.Filled.Block else Icons.Filled.CheckCircle, contentDescription = if (e.active) "إيقاف" else "تفعيل")
                            }
                            IconButton(enabled = !sync.busy, onClick = { confirmRemove = e }) { Icon(Icons.Filled.Delete, contentDescription = "حذف") }
                        }
                    },
                )
                HorizontalDivider()
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("مفاتيح الخادم والذكاء الاصطناعي", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("حساب خادم البيانات مضبوط مسبقًا. مفتاح Gemini: " + (if (sync.hasGemini) "مضبوط ✓ — التفريغ والفهرسة التلقائيان بالذكاء الاصطناعي مفعّلان." else "غير مضبوط — فهرسة يوتيوب تعمل باستنباط آلي مبسط، وتفريغ الملفات الصوتية معطّل.") + " عند تغيير أي مفتاح يحتاج كل المشرفين إلى أرقام سرية جديدة.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !sync.busy, onClick = { val (u, p, g) = app.sharedSync.credentialParts(); serverUser = u; serverPass = p; geminiKey = g; showServer = true }) { Text(if (sync.hasGemini) "تغيير المفاتيح" else "ضبط مفتاح Gemini / المفاتيح") }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }

}
