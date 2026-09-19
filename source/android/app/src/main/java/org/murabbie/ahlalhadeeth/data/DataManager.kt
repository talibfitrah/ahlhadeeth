package org.murabbie.ahlalhadeeth.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections
import java.util.zip.GZIPInputStream
import org.murabbie.ahlalhadeeth.BuildConfig

/** مفاتيح البناء الخاصة بالتنزيل التلقائي */
object BuildConfigAuto {
    const val AUTO_DOWNLOAD: Boolean = BuildConfig.AUTO_DOWNLOAD
}

/** وصف حزمة البيانات كما في manifest.json على NAS */
data class DataPack(
    val id: String,
    val version: String,
    val file: String,
    val sizeGz: Long,
    val sizeDb: Long,
    val sha256Gz: String,
    val sha256Db: String,
    val parts: List<String>,
    val partSizes: List<Long>,
    val description: String,
    val altParts: List<String> = emptyList(),
)

data class AppRelease(
    val versionCode: Int, val versionName: String, val apkArm64: String, val apkArm32: String, val apkUniversal: String, val notes: String,
    val pageArm64: String = "", val pageArm32: String = "", val pageUniversal: String = "",
) {
    /** أفضل رابط يفتحه المستخدم في المتصفح لتنزيل التحديث */
    val downloadPage: String get() = pageUniversal.ifBlank { pageArm64.ifBlank { apkUniversal.ifBlank { apkArm64 } } }
}

/** حزمة محتوى إضافية معلنة في manifest (تُستورد إلى المحتوى المضاف) */
data class PackInfo(val id: String, val name: String, val url: String, val version: Int, val size: Long, val description: String)

data class Manifest(val version: Int, val data: DataPack?, val app: AppRelease?, val audioServers: List<AudioServer>, val audioExt: String, val packs: List<PackInfo> = emptyList(), val defaultAudioServer: String = "")

sealed class DataState {
    object Checking : DataState()
    object NotInstalled : DataState()
    data class Downloading(val part: Int, val parts: Int, val bytes: Long, val total: Long, val message: String = "") : DataState()
    data class Installing(val message: String, val progress: Float) : DataState()
    data class Ready(val repo: Repository, val info: DbInfo) : DataState()
    data class Error(val message: String, val retryable: Boolean = true) : DataState()
}

class DataManager(private val context: Context, private val settings: Settings) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DataState>(DataState.Checking)
    val state: StateFlow<DataState> = _state
    private val _manifest = MutableStateFlow<Manifest?>(null)
    val manifest: StateFlow<Manifest?> = _manifest
    private var job: Job? = null
    private var currentRepo: Repository? = null

    /** عدّاد الثواني حتى إعادة المحاولة التلقائية (٠ = لا انتظار) */
    private val _retryCountdown = MutableStateFlow(0)
    val retryCountdown: StateFlow<Int> = _retryCountdown
    /** عدد مرات الفشل المتتالية في التنزيل التلقائي */
    val failures = MutableStateFlow(0)
    /** تحديث متاح للتطبيق (من manifest) */
    private val _updateAvailable = MutableStateFlow<AppRelease?>(null)
    val updateAvailable: StateFlow<AppRelease?> = _updateAvailable
    private val prefs = context.getSharedPreferences("data", Context.MODE_PRIVATE)

    val dataDir: File get() = File(context.filesDir, "data").apply { mkdirs() }
    val dbFile: File get() = File(dataDir, "ahl_alhadeeth.db")
    private val partsDir: File get() = File(context.filesDir, "parts").apply { mkdirs() }
    private val versionFile: File get() = File(dataDir, "version.txt")

    val repository: Repository? get() = (_state.value as? DataState.Ready)?.repo
    val installedVersion: String get() = if (versionFile.exists()) versionFile.readText().trim() else ""

    init {
        scope.launch { openIfPresent() }
    }

    private suspend fun openIfPresent() {
        val f = dbFile
        if (f.exists() && f.length() > 1024 * 1024) {
            try {
                val db = Db(f.absolutePath, readOnly = true)
                val repo = Repository(db, f)
                repo.user = runCatching { org.murabbie.ahlalhadeeth.App.instance.userDb.content }.getOrNull()
                val info = repo.info()
                if (info.meta["schema_version"].isNullOrEmpty()) throw IllegalStateException("قاعدة بيانات غير صالحة")
                currentRepo = repo
                _state.value = DataState.Ready(repo, info)
                return
            } catch (e: Exception) {
                // قاعدة تالفة أو ناقصة: تُحذف لتُنزَّل من جديد
                runCatching { f.delete(); versionFile.delete() }
                _state.value = DataState.NotInstalled
                return
            }
        }
        _state.value = DataState.NotInstalled
    }

    /** يُستدعى من الواجهة عند الإقلاع: يبدأ التنزيل التلقائي إن لم تكن البيانات مثبَّتة */
    fun autoStartIfNeeded() {
        if (!BuildConfigAuto.AUTO_DOWNLOAD) return
        if (userPaused.value) return
        val st = _state.value
        if (st is DataState.NotInstalled || (st is DataState.Error && st.retryable)) {
            DataDownloadService.start(context)
        }
    }

    fun setRetryCountdown(seconds: Int) { _retryCountdown.value = seconds }

    /** أوقف المستخدم التنزيل التلقائي بنفسه (لا تُعاد المحاولة حتى يطلب) */
    val userPaused = MutableStateFlow(false)

    /** إيقاف مؤقت من المستخدم: يلغي التنزيل الجاري ويوقف إعادة المحاولة */
    fun pauseAuto() {
        userPaused.value = true
        cancel()
    }

    /** استئناف بعد الإيقاف */
    fun resumeAuto() {
        userPaused.value = false
        failures.value = 0
        _retryCountdown.value = 0
        if (_state.value is DataState.Error) _state.value = DataState.NotInstalled
        DataDownloadService.start(context)
    }

    /** فحص تحديثات التطبيق مرة كل ٢٤ ساعة (يُستدعى بعد جاهزية البيانات) */
    fun checkAppUpdateDaily() {
        // نسخة Google Play تُحدَّث من المتجر وحده (سياسة Play تمنع التحديث الذاتي من خارج المتجر)
        if (org.murabbie.ahlalhadeeth.BuildConfig.DISTRIBUTION == "play") return
        val last = prefs.getLong("lastUpdateCheck", 0L)
        if (System.currentTimeMillis() - last < 24L * 3600 * 1000) return
        scope.launch {
            runCatching {
                val m = fetchManifest()
                prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
                val rel = m.app
                _updateAvailable.value = if (rel != null && rel.versionCode > org.murabbie.ahlalhadeeth.BuildConfig.VERSION_CODE) rel else null
            }
        }
    }

    fun dismissUpdate() { _updateAvailable.value = null }

    fun parseManifest(text: String): Manifest {
        val o = JSONObject(text)
        val d = o.optJSONObject("data")
        val pack = d?.let {
            val parts = ArrayList<String>()
            val alts = ArrayList<String>()
            val sizes = ArrayList<Long>()
            val arr = it.optJSONArray("parts")
            if (arr != null) for (i in 0 until arr.length()) {
                val p = arr.get(i)
                if (p is JSONObject) {
                    parts.add(p.optString("url"))
                    alts.add(p.optString("alt_url", ""))
                    sizes.add(p.optLong("size", -1))
                } else {
                    parts.add(p.toString())
                    alts.add("")
                    sizes.add(-1)
                }
            }
            DataPack(
                id = it.optString("id", "ahl_alhadeeth"),
                version = it.optString("version", ""),
                file = it.optString("file", "ahl_alhadeeth.db.gz"),
                sizeGz = it.optLong("size_gz", -1),
                sizeDb = it.optLong("size_db", -1),
                sha256Gz = it.optString("sha256_gz", ""),
                sha256Db = it.optString("sha256_db", ""),
                parts = parts,
                partSizes = sizes,
                description = it.optString("description", ""),
                altParts = alts,
            )
        }
        val a = o.optJSONObject("app")
        val release = a?.let {
            val apk = it.optJSONObject("apk")
            val page = it.optJSONObject("apk_page")
            AppRelease(
                it.optInt("version_code", 0), it.optString("version_name", ""),
                apk?.optString("arm64", "") ?: "", apk?.optString("arm32", "") ?: "", apk?.optString("universal", "") ?: "",
                it.optString("notes", ""),
                page?.optString("arm64", "") ?: "", page?.optString("arm32", "") ?: "", page?.optString("universal", "") ?: "",
            )
        }
        val servers = ArrayList<AudioServer>()
        val au = o.optJSONObject("audio")
        var ext = ".mp3"
        var defaultServer = ""
        if (au != null) {
            ext = au.optString("ext", ".mp3")
            defaultServer = au.optString("default_server", "")
            // servers: قواعد عادية؛ servers_v2 (منذ ١٫٧٫٠): قد تكون قوالب فيها {path} (خادم البيانات عبر مشاركة مجلد DSM) ولا تقرؤها النسخ الأقدم
            for (key in listOf("servers", "servers_v2")) {
                val arr = au.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val url = s.optString("url").trim()
                    if (url.isNotBlank()) servers.add(AudioServer(s.optString("title"), url))
                }
            }
        }
        val packs = ArrayList<PackInfo>()
        o.optJSONArray("packs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val po = arr.getJSONObject(i)
                packs.add(PackInfo(po.optString("id"), po.optString("name"), po.optString("url"), po.optInt("version", 1), po.optLong("size", 0), po.optString("description", "")))
            }
        }
        return Manifest(o.optInt("version", 1), pack, release, servers, ext, packs, defaultServer)
    }

    suspend fun fetchManifest(url: String = settings.value.manifestUrl): Manifest {
        val text = NasHttp.fetchText(url)
        val m = parseManifest(text)
        _manifest.value = m
        settings.mergeAudioServers(m.audioServers, m.defaultAudioServer)
        runCatching { org.murabbie.ahlalhadeeth.App.instance.sharedSync.applyManifest(JSONObject(text)) }
        return m
    }

    fun freeBytes(dir: File): Long = try {
        val st = StatFs(dir.absolutePath)
        st.availableBytes
    } catch (e: Exception) {
        -1L
    }

    /** بدء التنزيل والتثبيت من NAS */
    private var downloadGen = 0

    fun startDownload(manifestUrl: String = settings.value.manifestUrl) {
        job?.cancel()
        val gen = ++downloadGen
        // تُضبط الحالة فورًا (قبل انطلاق الكوروتين) حتى يراها من ينتظر حالة نهائية
        _state.value = DataState.Downloading(0, 0, 0, 0, "الاتصال بخادم البيانات…")
        job = scope.launch {
            try {
                val m = fetchManifest(manifestUrl)
                val pack = m.data ?: throw IllegalStateException("ملف الوصف لا يحوي بيانات")
                if (pack.parts.isEmpty()) throw IllegalStateException("لا توجد أجزاء للتنزيل")
                val need = (if (pack.sizeGz > 0) pack.sizeGz else 200L * 1024 * 1024) + (if (pack.sizeDb > 0) pack.sizeDb else 700L * 1024 * 1024)
                val free = freeBytes(context.filesDir)
                if (free in 0 until need) {
                    throw NoSpaceException("المساحة المتاحة (${ArabicText.formatSize(free)}) لا تكفي؛ يلزم نحو ${ArabicText.formatSize(need)}. حرِّر مساحة ثم اضغط «إعادة المحاولة».")
                }
                val partFiles = ArrayList<File>()
                var downloadedBefore = 0L
                val totalGz = if (pack.sizeGz > 0) pack.sizeGz else -1L
                for ((i, url) in pack.parts.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val pf = File(partsDir, pack.file + ".%03d".format(i + 1))
                    partFiles.add(pf)
                    val expected = pack.partSizes.getOrElse(i) { -1L }
                    val base = downloadedBefore
                    val onProgress: suspend (Long, Long) -> Unit = { have, _ ->
                        _state.value = DataState.Downloading(i + 1, pack.parts.size, base + have, totalGz, "تنزيل الجزء ${ArabicText.arabicDigits(i + 1)} من ${ArabicText.arabicDigits(pack.parts.size)}")
                    }
                    try {
                        NasHttp.download(url, pf, expected, attempts = 8, onProgress = onProgress)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val alt = pack.altParts.getOrElse(i) { "" }
                        if (alt.isBlank()) throw e
                        _state.value = DataState.Downloading(i + 1, pack.parts.size, base + pf.length(), totalGz, "تعذر الرابط الأول؛ المحاولة عبر الرابط البديل…")
                        NasHttp.download(alt, pf, expected, attempts = 6, onProgress = onProgress)
                    }
                    downloadedBefore += pf.length()
                }
                install(partFiles, pack)
                partFiles.forEach { it.delete() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (gen == downloadGen) _state.value = DataState.NotInstalled
            } catch (e: NoSpaceException) {
                if (gen == downloadGen) _state.value = DataState.Error(e.message ?: "المساحة لا تكفي", retryable = false)
            } catch (e: Exception) {
                if (gen == downloadGen) _state.value = DataState.Error(friendlyError(e))
            }
        }
    }

    class NoSpaceException(msg: String) : Exception(msg)

    private fun friendlyError(e: Exception): String {
        val m = e.message ?: ""
        return when {
            e is java.net.UnknownHostException || m.contains("Unable to resolve host") -> "لا يوجد اتصال بالإنترنت"
            e is java.net.SocketTimeoutException || m.contains("timeout", true) -> "انتهت مهلة الاتصال بخادم البيانات"
            e is java.net.ConnectException || m.contains("Failed to connect", true) -> "تعذر الاتصال بخادم البيانات"
            m.isBlank() -> "خطأ غير معروف"
            else -> m
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun closeCurrent() {
        currentRepo?.close()
        currentRepo = null
    }

    /** فك الضغط والتحقق ثم فتح القاعدة */
    private suspend fun install(partFiles: List<File>, pack: DataPack?) {
        _state.value = DataState.Installing("التحقق من سلامة الملفات…", 0f)
        val totalGz = partFiles.sumOf { it.length() }
        if (pack != null && pack.sizeGz > 0 && totalGz != pack.sizeGz) {
            partFiles.forEach { it.delete() }
            throw IllegalStateException("حجم الأجزاء ($totalGz) لا يطابق الحجم المتوقع (${pack.sizeGz})؛ سيُعاد التنزيل")
        }
        if (pack != null && pack.sha256Gz.isNotEmpty()) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            var done = 0L
            for (pf in partFiles) {
                pf.inputStream().use { input ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        done += n
                        _state.value = DataState.Installing("التحقق من سلامة الملفات…", done.toFloat() / totalGz)
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(pack.sha256Gz, true)) {
                partFiles.forEach { it.delete() }
                throw IllegalStateException("فشل التحقق من سلامة الملف (sha256 لا يطابق)؛ سيُعاد التنزيل")
            }
        }
        val tmp = File(dataDir, "ahl_alhadeeth.db.tmp")
        tmp.delete()
        _state.value = DataState.Installing("فك الضغط…", 0f)
        val streams = Collections.enumeration(partFiles.map { it.inputStream() as InputStream })
        val expectedDb = pack?.sizeDb ?: -1L
        GZIPInputStream(SequenceInputStream(streams), 256 * 1024).use { gz ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1024 * 1024)
                var done = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = gz.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (expectedDb > 0) _state.value = DataState.Installing("فك الضغط… ${ArabicText.formatSize(done)}", (done.toFloat() / expectedDb).coerceAtMost(1f))
                }
            }
        }
        if (expectedDb > 0 && tmp.length() != expectedDb) throw IllegalStateException("حجم القاعدة بعد فك الضغط لا يطابق المتوقع")
        // فحص سريع للقاعدة
        val test = Db(tmp.absolutePath, readOnly = true)
        try {
            val meta = Repository(test, tmp).meta()
            if (meta["schema_version"].isNullOrEmpty()) throw IllegalStateException("القاعدة المنزَّلة غير صالحة")
        } finally {
            test.close()
        }
        closeCurrent()
        dbFile.delete()
        if (!tmp.renameTo(dbFile)) throw IllegalStateException("تعذر حفظ قاعدة البيانات")
        versionFile.writeText(pack?.version ?: "local")
        openIfPresent()
    }

    /** تثبيت من ملف محلي اختاره المستخدم (.db أو .db.gz) */
    fun installFromUri(uri: Uri) {
        job?.cancel()
        job = scope.launch {
            try {
                _state.value = DataState.Installing("نسخ الملف المختار…", 0f)
                val resolver = context.contentResolver
                val name = runCatching {
                    resolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment ?: "file"
                val local = File(partsDir, "import.bin")
                local.delete()
                resolver.openInputStream(uri)!!.use { input -> FileOutputStream(local).use { out -> input.copyTo(out, 1024 * 1024) } }
                val isGz = local.inputStream().use { val b = ByteArray(2); it.read(b); b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte() }
                if (isGz) {
                    install(listOf(local), null)
                } else {
                    val tmp = File(dataDir, "ahl_alhadeeth.db.tmp")
                    tmp.delete()
                    if (!local.renameTo(tmp)) local.copyTo(tmp, true)
                    val test = Db(tmp.absolutePath, readOnly = true)
                    try {
                        val meta = Repository(test, tmp).meta()
                        if (meta["schema_version"].isNullOrEmpty()) throw IllegalStateException("الملف ليس قاعدة بيانات التطبيق")
                    } finally {
                        test.close()
                    }
                    closeCurrent()
                    dbFile.delete()
                    tmp.renameTo(dbFile)
                    versionFile.writeText("local:$name")
                    openIfPresent()
                }
                local.delete()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = DataState.NotInstalled
            } catch (e: Exception) {
                _state.value = DataState.Error(e.message ?: "خطأ غير معروف")
            }
        }
    }

    /** حذف قاعدة البيانات */
    fun deleteData() {
        job?.cancel()
        scope.launch {
            closeCurrent()
            dbFile.delete()
            versionFile.delete()
            partsDir.listFiles()?.forEach { it.delete() }
            _state.value = DataState.NotInstalled
        }
    }

    fun clearPartialDownloads() {
        partsDir.listFiles()?.forEach { it.delete() }
    }

    fun partialBytes(): Long = partsDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun retryOpen() {
        scope.launch { openIfPresent() }
    }
}
