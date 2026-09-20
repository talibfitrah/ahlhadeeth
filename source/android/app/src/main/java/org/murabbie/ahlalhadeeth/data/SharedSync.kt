package org.murabbie.ahlalhadeeth.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.murabbie.ahlalhadeeth.BuildConfig
import java.security.MessageDigest

/**
 * المزامنة المشتركة ونظام المشرفين:
 * - shared-content.json على الخادم (رابط مشاركة دائم) يجلبه كل تطبيق ويدمجه.
 * - admins.json يحوي المشرف العام والمشرفين بأرقامهم السرية (بصمات) ومفتاح الخادم مغلَّفًا لكل واحد.
 * - المشرف يدخل باسمه ورقمه السري داخل التطبيق (لا علاقة بحسابات NAS)، فيفك مفتاح الخادم وينشر به.
 */
class SharedSync(context: Context, private val content: UserContent) {

    private val prefs = context.getSharedPreferences("shared_sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    data class State(
        val role: String = "",            // "" | admin | super
        val adminUser: String = "",
        val adminName: String = "",
        val lastPull: Long = 0,
        val lastPublish: Long = 0,
        val busy: Boolean = false,
        val message: String = "",
        val sharedUrl: String = BuildConfig.DEFAULT_SHARED_URL,
        val adminsUrl: String = BuildConfig.DEFAULT_ADMINS_URL,
        val apiBase: String = BuildConfig.DEFAULT_NAS_API,
        val sharedPath: String = BuildConfig.DEFAULT_SHARED_PATH,
        val sharedName: String = "shared-content.json",
        val adminsName: String = "admins.json",
        val hasGemini: Boolean = false,
    ) {
        val isAdmin: Boolean get() = role.isNotEmpty()
        val isSuper: Boolean get() = role == AdminRegistry.ROLE_SUPER
    }

    private val _state = MutableStateFlow(
        State(
            role = prefs.getString("role", "") ?: "",
            adminUser = prefs.getString("adminUser", "") ?: "",
            adminName = prefs.getString("adminName", "") ?: "",
            lastPull = prefs.getLong("lastPull", 0),
            lastPublish = prefs.getLong("lastPublish", 0),
            sharedUrl = prefs.getString("sharedUrl", BuildConfig.DEFAULT_SHARED_URL) ?: BuildConfig.DEFAULT_SHARED_URL,
            adminsUrl = prefs.getString("adminsUrl", BuildConfig.DEFAULT_ADMINS_URL) ?: BuildConfig.DEFAULT_ADMINS_URL,
            apiBase = prefs.getString("apiBase", BuildConfig.DEFAULT_NAS_API) ?: BuildConfig.DEFAULT_NAS_API,
            sharedPath = prefs.getString("sharedPath", BuildConfig.DEFAULT_SHARED_PATH) ?: BuildConfig.DEFAULT_SHARED_PATH,
            hasGemini = ((prefs.getString("credential", "") ?: "").split('\n').getOrNull(2)?.isNotBlank() == true),
        )
    )
    val state: StateFlow<State> = _state
    val isAdmin: Boolean get() = _state.value.isAdmin
    val isSuper: Boolean get() = _state.value.isSuper

    // جلسة الخادم ومفتاح الخادم (مفكوك) — في تخزين التطبيق الخاص فقط
    private var sid: String = prefs.getString("adminSid", "") ?: ""
    private var serverRemoved: List<String> = runCatching {
        val arr = JSONArray(prefs.getString("serverRemoved", "[]") ?: "[]")
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun adminKey(): ByteArray? = (prefs.getString("adminKey", "") ?: "").ifBlank { null }?.let { runCatching { AdminCrypto.unb64(it) }.getOrNull() }
    /** مفتاح الخادم المفكوك: السطر الأول اسم المستخدم، الثاني كلمة السر، الثالث (اختياري) مفتاح Gemini */
    private fun credentialLines(): List<String> = (prefs.getString("credential", "") ?: "").split('\n')
    private fun credential(): Pair<String, String>? {
        val l = credentialLines()
        return if (l.size >= 2 && l[0].isNotBlank()) l[0] to l[1] else null
    }
    /** مفتاح Gemini (للتفريغ والفهرسة التلقائيين) إن ضبطه المشرف العام */
    fun geminiKey(): String? = credentialLines().getOrNull(2)?.filter { it in '!'..'~' }?.ifBlank { null } // يُرسل ترويسة HTTP: علامة اتجاه ملصوقة معه تُسقط الطلب
    val hasGemini: Boolean get() = geminiKey() != null
    /** (اسم مستخدم الخادم، كلمة سره، مفتاح Gemini) — للمشرف العام لتعبئة نموذج المفاتيح */
    fun credentialParts(): Triple<String, String, String> { val l = credentialLines(); return Triple(l.getOrElse(0) { "" }, l.getOrElse(1) { "" }, l.getOrElse(2) { "" }) }
    private fun buildCredential(user: String, pass: String, gemini: String) = user.trim() + "\n" + pass + (if (gemini.isNotBlank()) "\n" + gemini.trim() else "")

    /** تحديث الإعدادات من manifest (إن وُجدت فيه) */
    fun applyManifest(o: JSONObject?) {
        val sh = o?.optJSONObject("shared") ?: return
        // apiBase يستقبل اسم مستخدم الخادم وكلمة سره، وadmins_url مصدر المفاتيح المغلَّفة: لا تُقبل من manifest إلا https وعلى مضيف الخادم المضمَّن نفسه
        val host = BuildConfig.DEFAULT_NAS_API.toHttpUrl().host
        fun trusted(u: String) = if (u.startsWith("https://") && u.toHttpUrlOrNull()?.host == host) u else ""
        val url = trusted(sh.optString("url", ""))
        val adminsUrl = trusted(sh.optString("admins_url", ""))
        val api = trusted(sh.optString("api", ""))
        val path = sh.optString("path", "")
        prefs.edit().apply {
            if (url.isNotBlank()) putString("sharedUrl", url)
            if (adminsUrl.isNotBlank()) putString("adminsUrl", adminsUrl)
            if (api.isNotBlank()) putString("apiBase", api)
            if (path.isNotBlank()) putString("sharedPath", path)
        }.apply()
        _state.value = _state.value.copy(
            sharedUrl = url.ifBlank { _state.value.sharedUrl },
            adminsUrl = adminsUrl.ifBlank { _state.value.adminsUrl },
            apiBase = api.ifBlank { _state.value.apiBase },
            sharedPath = path.ifBlank { _state.value.sharedPath },
        )
    }

    class NasError(val code: Int, msg: String) : Exception(msg)

    // ---------- سجل المشرفين ----------

    /** جلب admins.json من رابطه الدائم */
    suspend fun fetchAdmins(): AdminRegistry {
        val url = _state.value.adminsUrl
        if (url.isBlank()) throw IllegalStateException("رابط ملف المشرفين غير مضبوط")
        return AdminRegistry.parse(NasHttp.fetchText(url))
    }

    /** دخول مشرف باسمه ورقمه السري (نظام التطبيق المستقل) */
    suspend fun login(user: String, pin: String) {
        _state.value = _state.value.copy(busy = true, message = "")
        try {
            val reg = fetchAdmins()
            val auth = withContext(Dispatchers.Default) { reg.authenticate(user, pin) }
            sid = ""
            prefs.edit()
                .putString("role", auth.role).putString("adminUser", auth.user).putString("adminName", auth.name)
                .putString("adminKey", AdminCrypto.b64(auth.key)).putString("credential", auth.credential).remove("adminSid")
                .apply()
            _state.value = _state.value.copy(role = auth.role, adminUser = auth.user, adminName = auth.name, busy = false, hasGemini = auth.credential.split('\n').getOrNull(2)?.isNotBlank() == true, message = if (auth.role == AdminRegistry.ROLE_SUPER) "تم الدخول بصفة المشرف العام" else "تم الدخول بصفة مشرف")
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, message = e.message ?: "تعذر الدخول")
            throw e
        }
    }

    fun logout(message: String = "") {
        sid = ""
        prefs.edit().remove("role").remove("adminUser").remove("adminName").remove("adminKey").remove("credential").remove("adminSid").apply()
        _state.value = _state.value.copy(role = "", adminUser = "", adminName = "", hasGemini = false, message = message)
    }

    /** التحقق أن المشرف ما زال مسجَّلًا وفعّالًا (قبل أي نشر أو تعديل) */
    suspend fun verifyAccess(): AdminRegistry {
        val key = adminKey() ?: throw NasError(400, "لم يُسجَّل دخول مشرف")
        val reg = fetchAdmins()
        if (!reg.verify(_state.value.adminUser, key)) {
            logout("أُلغيت صلاحيتك أو تغيّر رقمك السري؛ سجّل الدخول من جديد")
            throw NasError(402, "أُلغيت صلاحيتك أو تغيّر رقمك السري")
        }
        return reg
    }

    private suspend fun saveAdmins(reg: AdminRegistry) {
        upload(reg.toText().toByteArray(Charsets.UTF_8), _state.value.adminsName)
    }

    private fun requireSuper() { if (!isSuper) throw NasError(403, "هذه العملية للمشرف العام فقط") }

    suspend fun admins(): List<AdminRegistry.Entry> { requireSuper(); return verifyAccess().entries() }

    /** إضافة مشرف برقم سري (يُولَّد إن كان فارغًا)؛ يعيد الرقم السري ليُبلَّغ به */
    suspend fun addAdmin(name: String, user: String, pin: String = ""): String = busyOp {
        requireSuper()
        val reg = verifyAccess()
        val p = pin.ifBlank { AdminCrypto.randomPin() }
        val cred = prefs.getString("credential", "") ?: ""
        withContext(Dispatchers.Default) { reg.addAdmin(name, user, p, cred, _state.value.adminUser) }
        saveAdmins(reg)
        p
    }

    /** رقم سري جديد لمشرف (أو للمشرف العام نفسه) */
    suspend fun resetPin(user: String, pin: String = ""): String = busyOp {
        requireSuper()
        val reg = verifyAccess()
        val p = pin.ifBlank { AdminCrypto.randomPin() }
        val cred = prefs.getString("credential", "") ?: ""
        withContext(Dispatchers.Default) { reg.setPin(user, p, cred) }
        saveAdmins(reg)
        if (user == _state.value.adminUser) updateOwnKey(reg, p)
        p
    }

    suspend fun setAdminActive(user: String, active: Boolean) = busyOp {
        requireSuper()
        val reg = verifyAccess()
        reg.setActive(user, active)
        saveAdmins(reg)
    }

    suspend fun removeAdmin(user: String) = busyOp {
        requireSuper()
        val reg = verifyAccess()
        reg.remove(user)
        saveAdmins(reg)
    }

    /** المشرف (أو المشرف العام) يغيّر رقمه السري */
    suspend fun changeOwnPin(newPin: String) = busyOp {
        val reg = verifyAccess()
        val cred = prefs.getString("credential", "") ?: ""
        withContext(Dispatchers.Default) { reg.setPin(_state.value.adminUser, newPin, cred) }
        saveAdmins(reg)
        updateOwnKey(reg, newPin)
    }

    private suspend fun updateOwnKey(reg: AdminRegistry, pin: String) {
        val obj = reg.find(_state.value.adminUser) ?: return
        val key = withContext(Dispatchers.Default) { AdminCrypto.deriveKey(pin, AdminCrypto.unb64(obj.getString("salt")), reg.iterations) }
        prefs.edit().putString("adminKey", AdminCrypto.b64(key)).apply()
    }

    /**
     * تغيير حساب الخادم (المشرف العام): يُتحقق من الحساب الجديد ثم يُغلَّف للمشرف العام،
     * وتُمحى أغلفة المشرفين فيحتاجون أرقامًا سرية جديدة.
     */
    suspend fun changeServerAccount(user: String, pass: String, gemini: String = "") = busyOp {
        requireSuper()
        val key = adminKey() ?: throw NasError(400, "لم يُسجَّل دخول مشرف")
        val reg = verifyAccess()
        val newSid = apiLogin(user.trim(), pass)
        if (!canListFolder(newSid)) throw NasError(402, "الحساب لا يصل إلى مجلد المحتوى ${_state.value.sharedPath}")
        val cred = buildCredential(user, pass, gemini)
        withContext(Dispatchers.Default) { reg.changeCredential(key, cred) }
        prefs.edit().putString("credential", cred).putString("adminSid", newSid).apply()
        sid = newSid
        saveAdmins(reg)
        _state.value = _state.value.copy(hasGemini = gemini.isNotBlank())
    }

    private suspend fun <T> busyOp(block: suspend () -> T): T {
        _state.value = _state.value.copy(busy = true, message = "")
        try {
            return block()
        } catch (e: Exception) {
            _state.value = _state.value.copy(message = e.message ?: "تعذرت العملية")
            throw e
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    // ---------- واجهة الخادم (DSM) بمفتاح الخادم المفكوك ----------

    private fun authErrorMessage(code: Int): String = when (code) {
        400 -> "مفتاح الخادم غير صحيح (اسم المستخدم أو كلمة السر)"
        401 -> "حساب الخادم معطَّل"
        402 -> "حساب الخادم لا يملك صلاحية"
        403, 404 -> "حساب الخادم يتطلب تحققًا بخطوتين؛ عطّله له"
        else -> "خطأ من الخادم ($code)"
    }

    private suspend fun apiLogin(user: String, pass: String): String = withContext(Dispatchers.IO) {
        val url = _state.value.apiBase.toHttpUrl().newBuilder().addPathSegment("auth.cgi")
            .addQueryParameter("api", "SYNO.API.Auth").addQueryParameter("version", "3").addQueryParameter("method", "login")
            .addQueryParameter("account", user).addQueryParameter("passwd", pass)
            .addQueryParameter("session", "FileStation").addQueryParameter("format", "sid").build()
        NasHttp.client.newCall(Request.Builder().url(url).header("User-Agent", "AhlAlhadeeth-Android/1.0").build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val o = runCatching { JSONObject(body) }.getOrNull() ?: throw NasError(-1, "استجابة غير مفهومة من الخادم")
            if (!o.optBoolean("success")) {
                val code = o.optJSONObject("error")?.optInt("code") ?: -1
                throw NasError(code, authErrorMessage(code))
            }
            o.getJSONObject("data").getString("sid")
        }
    }

    private suspend fun canListFolder(s: String): Boolean = withContext(Dispatchers.IO) {
        val listUrl = _state.value.apiBase.toHttpUrl().newBuilder().addPathSegment("entry.cgi")
            .addQueryParameter("api", "SYNO.FileStation.List").addQueryParameter("version", "2").addQueryParameter("method", "list")
            .addQueryParameter("folder_path", _state.value.sharedPath).addQueryParameter("_sid", s).build()
        NasHttp.client.newCall(Request.Builder().url(listUrl).build()).execute().use { r -> runCatching { JSONObject(r.body?.string() ?: "").optBoolean("success") }.getOrDefault(false) }
    }

    private suspend fun ensureSid(): String {
        if (sid.isNotBlank()) return sid
        val (u, p) = credential() ?: throw NasError(400, "لم يُسجَّل دخول مشرف")
        sid = apiLogin(u, p)
        prefs.edit().putString("adminSid", sid).apply()
        return sid
    }

    /** رسائل أخطاء عمليات الملفات (SYNO.FileStation.*) */
    private fun fsErrorMessage(code: Int): String = when (code) {
        119, 105, 106, 107 -> "انتهت جلسة الخادم؛ أعد المحاولة"
        400 -> "معامل غير صالح في عملية الرفع"
        401 -> "خطأ غير معروف في عملية الرفع على الخادم"
        402 -> "الخادم مشغول؛ حاول بعد قليل"
        403 -> "حساب الخادم لا يملك صلاحية هذه العملية"
        407 -> "لا صلاحية للكتابة في مجلد المحتوى المشترك"
        408 -> "مجلد المحتوى غير موجود على الخادم"
        1800 -> "تعذر الرفع: مشكلة في طول المحتوى"
        1805 -> "تعذر استبدال الملف الموجود"
        else -> "خطأ من الخادم ($code)"
    }

    private suspend fun upload(bytes: ByteArray, name: String) {
        suspend fun attempt(s: String): Int = withContext(Dispatchers.IO) {
            val url = _state.value.apiBase.toHttpUrl().newBuilder().addPathSegment("entry.cgi")
                .addQueryParameter("api", "SYNO.FileStation.Upload").addQueryParameter("version", "2").addQueryParameter("method", "upload")
                .addQueryParameter("_sid", s).build()
            // جسم multipart يدوي: DSM يرفض أجزاء OkHttp ذات Content-Length (خطأ 401)
            val (boundary, raw) = NasHttp.multipart(
                listOf("path" to _state.value.sharedPath, "create_parents" to "true", "overwrite" to "true"),
                "file", name, bytes,
            )
            val body = raw.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())
            NasHttp.client.newCall(Request.Builder().url(url).post(body).header("User-Agent", "AhlAlhadeeth-Android/1.0").build()).execute().use { resp ->
                val o = runCatching { JSONObject(resp.body?.string() ?: "") }.getOrNull() ?: return@withContext -1
                if (o.optBoolean("success")) 0 else (o.optJSONObject("error")?.optInt("code") ?: -1)
            }
        }
        var code = attempt(ensureSid())
        if (code == 119 || code == 105 || code == 106 || code == 107) { // جلسة منتهية: إعادة الدخول
            sid = ""
            code = attempt(ensureSid())
        }
        if (code != 0) throw NasError(code, fsErrorMessage(code))
    }

    // ---------- رفع الوسائط وإنشاء روابط المشاركة (نقل الدروس إلى الخادم) ----------

    /** رفع ملف وسائط إلى مجلد فرعي داخل مجلد المحتوى (يُنشأ إن لم يوجد)؛ يعيد المسار الكامل على الخادم */
    suspend fun uploadMedia(file: java.io.File, subDir: String, name: String, mime: String, onProgress: (Long) -> Unit = {}): String {
        val dir = _state.value.sharedPath.trimEnd('/') + "/" + subDir.trim('/')
        suspend fun attempt(s: String): Int = withContext(Dispatchers.IO) {
            val url = _state.value.apiBase.toHttpUrl().newBuilder().addPathSegment("entry.cgi")
                .addQueryParameter("api", "SYNO.FileStation.Upload").addQueryParameter("version", "2").addQueryParameter("method", "upload")
                .addQueryParameter("_sid", s).build()
            val (_, body) = NasHttp.multipartFile(listOf("path" to dir, "create_parents" to "true", "overwrite" to "true"), "file", name, file, mime, onProgress)
            NasHttp.client.newBuilder().writeTimeout(30, java.util.concurrent.TimeUnit.MINUTES).readTimeout(10, java.util.concurrent.TimeUnit.MINUTES).build()
                .newCall(Request.Builder().url(url).post(body).header("User-Agent", "AhlAlhadeeth-Android/1.0").build()).execute().use { resp ->
                    val o = runCatching { JSONObject(resp.body?.string() ?: "") }.getOrNull() ?: return@withContext -1
                    if (o.optBoolean("success")) 0 else (o.optJSONObject("error")?.optInt("code") ?: -1)
                }
        }
        var code = attempt(ensureSid())
        if (code == 119 || code == 105 || code == 106 || code == 107) { sid = ""; code = attempt(ensureSid()) }
        if (code != 0) throw NasError(code, fsErrorMessage(code))
        return "$dir/$name"
    }

    /** إنشاء رابط مشاركة دائم لملف على الخادم؛ يعيد رابط التنزيل المباشر (fsdownload) */
    suspend fun createShareLink(path: String): String {
        suspend fun attempt(s: String): Pair<Int, String> = withContext(Dispatchers.IO) {
            val url = _state.value.apiBase.toHttpUrl().newBuilder().addPathSegment("entry.cgi")
                .addQueryParameter("api", "SYNO.FileStation.Sharing").addQueryParameter("version", "3").addQueryParameter("method", "create")
                .addQueryParameter("path", JSONArray().put(path).toString()).addQueryParameter("_sid", s).build()
            NasHttp.client.newCall(Request.Builder().url(url).header("User-Agent", "AhlAlhadeeth-Android/1.0").build()).execute().use { resp ->
                val o = runCatching { JSONObject(resp.body?.string() ?: "") }.getOrNull() ?: return@withContext -1 to ""
                if (!o.optBoolean("success")) return@withContext (o.optJSONObject("error")?.optInt("code") ?: -1) to ""
                val link = o.getJSONObject("data").getJSONArray("links").getJSONObject(0)
                val id = link.optString("id")
                val shareUrl = link.optString("url")
                val base = shareUrl.substringBefore("/sharing/").ifBlank { _state.value.apiBase.substringBefore("/webapi") }
                0 to "$base/fsdownload/$id/" + java.net.URLEncoder.encode(path.substringAfterLast('/'), "UTF-8").replace("+", "%20")
            }
        }
        var (code, link) = attempt(ensureSid())
        if (code == 119 || code == 105 || code == 106 || code == 107) { sid = ""; val r = attempt(ensureSid()); code = r.first; link = r.second }
        if (code != 0) throw NasError(code, fsErrorMessage(code))
        return link
    }

    // ---------- الجلب (كل المستخدمين) ----------

    sealed class PullResult {
        object Skipped : PullResult()
        object Unchanged : PullResult()
        data class Imported(val result: UserContent.ImportResult) : PullResult()
        data class Failed(val message: String) : PullResult()
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** جلب المحتوى المشترك ودمجه؛ force يتجاهل فاصل الست ساعات */
    suspend fun pull(force: Boolean = false): PullResult = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - _state.value.lastPull < 6L * 3600 * 1000) return PullResult.Skipped
        val url = _state.value.sharedUrl
        if (url.isBlank()) return PullResult.Skipped
        _state.value = _state.value.copy(busy = true)
        try {
            val text = NasHttp.fetchText(url)
            val hash = sha256(text)
            // خطأ DSM يصل JSON صالحًا بحالة 200: نتحقق من الصيغة قبل حفظ شواهد الحذف حتى لا تُمحى بردٍّ ليس حزمة
            val o = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("format") == "ahl-alhadeeth-pack" } ?: throw IllegalStateException("ملف المحتوى المشترك غير صالح")
            serverRemoved = o.optJSONArray("removed")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }?.filter { it.isNotBlank() } ?: emptyList()
            prefs.edit().putString("serverRemoved", JSONArray(serverRemoved).toString()).apply()
            val unchanged = hash == (prefs.getString("lastHash", "") ?: "")
            val res = if (unchanged && !force) null else content.importPack(text, origin = "shared")
            prefs.edit().putLong("lastPull", now).putString("lastHash", hash).apply()
            _state.value = _state.value.copy(lastPull = now, busy = false, message = if (res == null) "المحتوى المشترك محدَّث" else "جُلب المحتوى المشترك: ${ArabicText.arabicDigits(res.chapters)} درس جديد")
            return if (res == null) PullResult.Unchanged else PullResult.Imported(res)
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, message = "تعذر جلب المحتوى المشترك: ${e.message}")
            return PullResult.Failed(e.message ?: "")
        }
    }

    // ---------- النشر (المشرفون) ----------

    private class StaleShared : Exception("نشر مشرف آخر قبل لحظات؛ أعد النشر ليُدمج عمله")

    /** دمج آخر نسخة من الخادم مع تغييرات المشرف ثم رفعها؛ إن نشر مشرف آخر بين الجلب والرفع أُعيد الجلب والدمج (مرة واحدة) */
    suspend fun publish(): String {
        return try { publishOnce() } catch (e: StaleShared) { publishOnce() }
    }

    private suspend fun publishOnce(): String {
        if (!isAdmin) throw NasError(400, "لم يُسجَّل دخول مشرف")
        _state.value = _state.value.copy(busy = true, message = "")
        try {
            verifyAccess()
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, message = e.message ?: "تعذر التحقق من الصلاحية")
            throw e
        }
        // ١) أحدث نسخة من الخادم (تحترم الحذف المحلي والتعديلات المعلّقة)
        val pulled = pull(force = true)
        if (pulled is PullResult.Failed) throw IllegalStateException("تعذر جلب النسخة الأخيرة قبل النشر: ${pulled.message}")
        mutex.withLock {
            _state.value = _state.value.copy(busy = true)
            try {
                val (dirty, removedLocal) = content.pendingCounts()
                val removedSent = content.markPublishing()
                val removed = (serverRemoved + removedSent).distinct()
                val json = content.exportPack(
                    null, "المحتوى المشترك — أهل الحديث والأثر", removed,
                    extra = mapOf("id" to "shared-content", "updated" to System.currentTimeMillis(), "published_by" to _state.value.adminUser, "pack_version" to (System.currentTimeMillis() / 1000).toInt())
                )
                // لا خادم وسيط يمنع تزامن مشرفَين؛ نضيّق النافذة: إن تغيّر الملف منذ الجلب قبل لحظات لا نرفع فوقه بل نعيد الجلب والدمج
                if (sha256(NasHttp.fetchText(_state.value.sharedUrl)) != (prefs.getString("lastHash", "") ?: "")) throw StaleShared()
                upload(json.toByteArray(Charsets.UTF_8), _state.value.sharedName)
                // تعديل جرى أثناء الرفع (فهرسة/تفريغ آلي) لم يدخل الملف المرفوع: يبقى درسه وحده معلَّقًا ليغلب محليًا ويُنشر لاحقًا
                content.markPublished(removedSent)
                content.markAllShared()
                serverRemoved = removed
                val now = System.currentTimeMillis()
                prefs.edit().putLong("lastPublish", now).putLong("lastPull", now).putString("lastHash", sha256(json)).putString("serverRemoved", JSONArray(removed).toString()).apply()
                val msg = "نُشر للجميع: ${ArabicText.arabicDigits(dirty)} درس معدَّل" + (if (removedLocal > 0) " و${ArabicText.arabicDigits(removedLocal)} حذف" else "")
                _state.value = _state.value.copy(busy = false, lastPublish = now, lastPull = now, message = msg)
                return msg
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, message = "تعذر النشر: ${e.message}")
                throw e
            }
        }
    }
}
