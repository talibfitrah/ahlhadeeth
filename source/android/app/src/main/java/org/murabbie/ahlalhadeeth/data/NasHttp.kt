package org.murabbie.ahlalhadeeth.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * عميل HTTP للتنزيل من روابط مشاركة DSM (Synology) ومن أي رابط مباشر.
 * روابط المشاركة بصيغة https://host/fsdownload/<ID>/<name> تحتاج أولًا زيارة https://host/sharing/<ID>
 * للحصول على كعكة sharing_sid، ثم يعمل التنزيل مع دعم Range (الاستئناف).
 */
object NasHttp {

    private val cookieStore = HashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieStore) {
                val list = cookieStore.getOrPut(url.host) { ArrayList() }
                for (c in cookies) {
                    list.removeAll { it.name == c.name }
                    list.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookieStore) {
            (cookieStore[url.host] ?: emptyList()).filter { it.expiresAt > System.currentTimeMillis() }
        }
    }

    /** عميل بلا معترض (لطلبات تهيئة المشاركة نفسها) */
    private val bareClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val fsdownloadRegex = Regex("^(https?://[^/]+)/fsdownload/([^/]+)/")
    /** رابط ملف داخل مشاركة مجلد DSM: …/webapi/entry.cgi?api=SYNO.FolderSharing.Download&…&_sharing_id=<ID>&path=[…] */
    private val folderShareRegex = Regex("^(https?://[^/]+)/webapi/entry\\.cgi\\?(?:.*&)?_sharing_id=([A-Za-z0-9_-]+)")

    /** رابط تهيئة المشاركة (https://host/sharing/<ID>) إن كان الرابط من نوع fsdownload أو مشاركة مجلد */
    fun sharingPrefix(url: String): String? {
        fsdownloadRegex.find(url)?.let { return it.groupValues[1] + "/sharing/" + it.groupValues[2] }
        folderShareRegex.find(url)?.let { return it.groupValues[1] + "/sharing/" + it.groupValues[2] }
        return null
    }

    fun isShareLink(url: String?): Boolean = url != null && (fsdownloadRegex.containsMatchIn(url) || folderShareRegex.containsMatchIn(url))

    /** واجهة مشاركة المجلد تعيد الأخطاء بحالة 200 وجسم JSON؛ فالجسم JSON في رابط وسائط يعني فشلًا */
    fun isFolderShareLink(url: String?): Boolean = url != null && folderShareRegex.containsMatchIn(url)

    /** خوادم الصوت المعروفة (قواعد أو قوالب فيها {path}) بترتيب التفضيل — تضبطها الإعدادات؛ يُستعمل للتبديل عند تعذر ملف */
    @Volatile var audioServers: List<String> = emptyList()

    /** (رقم الخادم، المسار النسبي) إن كان الرابط من أحد خوادم الصوت */
    private fun audioRel(url: String): Pair<Int, String>? {
        audioServers.forEachIndexed { i, sv -> AudioUrls.relOf(sv, url)?.let { return i to it } }
        return null
    }

    private val failedOn = HashMap<String, Long>() // "index|rel" -> وقت الفشل

    private fun looksBad(resp: okhttp3.Response, url: String): Boolean {
        if (resp.code == 403 || resp.code == 404 || resp.code >= 500) return true
        val ct = resp.header("Content-Type") ?: ""
        if (ct.contains("text/html", true)) return true
        if (isFolderShareLink(url) && ct.contains("application/json", true)) return true
        return false
    }

    @Volatile private var primedSharing: String? = null

    /** تهيئة جلسة المشاركة (كعكة sharing_sid) لرابط fsdownload */
    private fun primeSharing(url: String) {
        val sharing = sharingPrefix(url) ?: return
        runCatching {
            bareClient.newCall(Request.Builder().url(sharing).header("User-Agent", UA).build()).execute().use { }
        }
        primedSharing = sharing
    }

    /**
     * معترض لروابط مشاركة DSM (fsdownload): يهيّئ جلسة المشاركة قبل أول طلب (ولكل مشاركة جديدة)،
     * ويعيد المحاولة مرة عند 403/404 أو صفحة HTML — فتعمل روابط المشاركة في المشغّل والتنزيل مباشرة.
     */
    private val sharingInterceptor = okhttp3.Interceptor { chain ->
        val req = chain.request()
        val url = req.url.toString()
        val sharing = sharingPrefix(url) ?: return@Interceptor chain.proceed(req)
        if (primedSharing != sharing) primeSharing(url)
        var resp = chain.proceed(req)
        if (looksBad(resp, url)) {
            resp.close()
            primeSharing(url)
            resp = chain.proceed(req)
        }
        resp
    }

    /**
     * معترض التبديل بين خوادم الصوت: إن تعذر ملف على الخادم المختار (غير موجود بعد على خادم البيانات مثلًا)
     * يُجلب الملف نفسه من الخوادم الأخرى بالترتيب، ويُحفظ الفشل عشر دقائق كي لا يُعاد الطلب الفاشل مع كل مقطع.
     */
    private val audioFallbackInterceptor = okhttp3.Interceptor { chain ->
        val req = chain.request()
        val url = req.url.toString()
        val (idx, rel) = audioRel(url) ?: return@Interceptor chain.proceed(req)
        val servers = audioServers
        val now = System.currentTimeMillis()
        val order = (listOf(idx) + servers.indices.filter { it != idx })
        var resp: okhttp3.Response? = null
        for ((k, i) in order.withIndex()) {
            val key = "$i|$rel"
            val failedAt = synchronized(failedOn) { failedOn[key] }
            if (failedAt != null && now - failedAt < 10 * 60_000L && k < order.size - 1) continue
            val target = if (i == idx) url else AudioUrls.buildUrl(servers[i], rel)
            resp?.close()
            resp = chain.proceed(req.newBuilder().url(target).build())
            if (!looksBad(resp, target)) {
                synchronized(failedOn) { failedOn.remove(key) }
                return@Interceptor resp
            }
            synchronized(failedOn) { if (failedOn.size > 2000) failedOn.clear(); failedOn[key] = now }
        }
        resp ?: chain.proceed(req)
    }

    /** العميل العام (يشترك في الكعكات مع عميل التهيئة) — يُستعمل أيضًا في مشغّل Media3 */
    val client: OkHttpClient = bareClient.newBuilder().addInterceptor(audioFallbackInterceptor).addInterceptor(sharingInterceptor).build()

    private const val UA = "AhlAlhadeeth-Android/1.0"

    class HttpException(val code: Int, msg: String) : IOException(msg)

    /** جلب نص (manifest.json مثلًا) مع إعادة المحاولة */
    suspend fun fetchText(url: String, attempts: Int = 8): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (i in 0 until attempts) {
            currentCoroutineContext().ensureActive()
            try {
                primeSharing(url)
                client.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Cache-Control", "no-cache").build()).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (resp.isSuccessful && !looksLikeErrorPage(resp.header("Content-Type"), body)) return@withContext body
                    throw HttpException(resp.code, "HTTP ${resp.code}")
                }
            } catch (e: Exception) {
                last = e
                delay(700L * (i + 1))
            }
        }
        throw last ?: IOException("فشل الجلب")
    }

    private fun looksLikeErrorPage(contentType: String?, body: String): Boolean {
        val ct = contentType ?: ""
        if (ct.contains("text/html", true) && body.trimStart().startsWith("<!DOCTYPE", true)) return true
        return false
    }

    /**
     * تنزيل ملف إلى [dest] مع الاستئناف من الحجم الحالي. يستدعي [onProgress] بالبايتات المنزَّلة والحجم الكلي (أو -1).
     * يعيد المحاولة على أخطاء الشبكة وعلى 404 المؤقت الذي يحدث في روابط مشاركة DSM.
     */
    suspend fun download(
        url: String,
        dest: File,
        expectedSize: Long = -1,
        attempts: Int = 12,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        var last: Exception? = null
        for (attempt in 0 until attempts) {
            currentCoroutineContext().ensureActive()
            try {
                var have = if (dest.exists()) dest.length() else 0L
                if (expectedSize > 0 && have >= expectedSize) {
                    if (have > expectedSize) {
                        RandomAccessFile(dest, "rw").use { it.setLength(expectedSize) }
                    }
                    onProgress(expectedSize, expectedSize)
                    return@withContext
                }
                primeSharing(url)
                val rb = Request.Builder().url(url).header("User-Agent", UA)
                if (have > 0) rb.header("Range", "bytes=$have-")
                client.newCall(rb.build()).execute().use { resp ->
                    val code = resp.code
                    val ct = resp.header("Content-Type") ?: ""
                    if (code == 416) {
                        // الخادم يقول إن الملف اكتمل
                        onProgress(have, have)
                        return@withContext
                    }
                    if (!resp.isSuccessful) throw HttpException(code, "HTTP $code")
                    if (ct.contains("text/html", true)) throw HttpException(code, "صفحة خطأ من الخادم")
                    if (isFolderShareLink(resp.request.url.toString()) && ct.contains("application/json", true)) throw HttpException(code, "الملف غير موجود على خادم البيانات")
                    val body = resp.body ?: throw IOException("لا محتوى")
                    val append = code == 206 && have > 0
                    val total: Long = if (append) {
                        val cr = resp.header("Content-Range") ?: ""
                        cr.substringAfter('/', "").toLongOrNull() ?: (have + body.contentLength())
                    } else {
                        if (have > 0) have = 0 // الخادم تجاهل Range: نبدأ من جديد
                        body.contentLength()
                    }
                    if (expectedSize > 0 && total > 0 && total != expectedSize) {
                        throw IOException("حجم الملف على الخادم ($total) لا يطابق المتوقع ($expectedSize)")
                    }
                    RandomAccessFile(dest, "rw").use { raf ->
                        raf.setLength(have)
                        raf.seek(have)
                        val buf = ByteArray(256 * 1024)
                        body.byteStream().use { input ->
                            var lastReport = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                have += n
                                if (have - lastReport > 512 * 1024) {
                                    lastReport = have
                                    onProgress(have, total)
                                }
                            }
                        }
                    }
                    val finalSize = dest.length()
                    if (expectedSize > 0 && finalSize < expectedSize) throw IOException("انقطع التنزيل عند $finalSize من $expectedSize")
                    if (expectedSize <= 0 && total > 0 && finalSize < total) throw IOException("انقطع التنزيل")
                    onProgress(finalSize, if (total > 0) total else finalSize)
                    return@withContext
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
                delay(minOf(15000L, 800L * (attempt + 1)))
            }
        }
        throw last ?: IOException("فشل التنزيل")
    }

    /** حجم الملف عن بُعد (HEAD أو GET بنطاق صفري) */
    suspend fun remoteSize(url: String): Long = withContext(Dispatchers.IO) {
        for (i in 0 until 5) {
            try {
                primeSharing(url)
                client.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-0").build()).execute().use { resp ->
                    if (resp.code == 206) {
                        val cr = resp.header("Content-Range") ?: ""
                        return@withContext cr.substringAfter('/', "").toLongOrNull() ?: -1L
                    }
                    if (resp.isSuccessful && !(resp.header("Content-Type") ?: "").contains("application/json", true)) return@withContext resp.body?.contentLength() ?: -1L
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            delay(600L * (i + 1))
        }
        -1L
    }

    fun sha256(file: File, onProgress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                onProgress(done)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * جسم multipart/form-data مبنيّ يدويًا: واجهة DSM ترفض الأجزاء التي تحمل ترويسة Content-Length خاصة بها
     * (كما يفعل OkHttp MultipartBody) بالخطأ 401، وتحتاج إلى Content-Length كلي للطلب.
     * يعيد (boundary, bytes).
     */
    /** جسم multipart متدفق من ملف (للملفات الكبيرة) بالبنية نفسها التي تقبلها DSM؛ يعيد (boundary, body) */
    fun multipartFile(fields: List<Pair<String, String>>, fileField: String, fileName: String, file: File, mime: String = "application/octet-stream", onProgress: (Long) -> Unit = {}): Pair<String, okhttp3.RequestBody> {
        val boundary = "----AhlAlhadeeth" + java.util.UUID.randomUUID().toString().replace("-", "")
        val head = StringBuilder()
        for ((k, v) in fields) head.append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        val safeName = fileName.replace("\"", "").replace("\r", "").replace("\n", "")
        head.append("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$safeName\"\r\nContent-Type: $mime\r\n\r\n")
        val headBytes = head.toString().toByteArray(Charsets.UTF_8)
        val tailBytes = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "multipart/form-data; boundary=$boundary".toMediaTypeOrNull()
            override fun contentLength(): Long = headBytes.size + file.length() + tailBytes.size
            override fun writeTo(sink: okio.BufferedSink) {
                sink.write(headBytes)
                file.inputStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
                sink.write(tailBytes)
            }
        }
        return boundary to body
    }

    fun multipart(fields: List<Pair<String, String>>, fileField: String, fileName: String, bytes: ByteArray): Pair<String, ByteArray> {
        val boundary = "----AhlAlhadeeth" + java.util.UUID.randomUUID().toString().replace("-", "")
        val out = java.io.ByteArrayOutputStream(bytes.size + 512)
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        val safeName = fileName.replace("\"", "").replace("\r", "").replace("\n", "")
        w("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$safeName\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        out.write(bytes)
        w("\r\n--$boundary--\r\n")
        return boundary to out.toByteArray()
    }
}
