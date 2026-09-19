package org.murabbie.ahlalhadeeth.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * نقل دروس المشرف (يوتيوب أو روابط مباشرة) إلى خادم البيانات: تنزيل على الهاتف (جسر مؤقت) ثم رفع إلى NAS،
 * رابط مشاركة دائم، وتحويل الدرس ليُشغَّل من الخادم (مع حفظ الرابط الأصلي مصدرًا للتفريغ الآلي).
 *
 * التقنية:
 * - التنزيل من يوتيوب بأربعة اتصالات متوازية على أجزاء من ٤ م.ب (طلبات Range) — خوادم يوتيوب تُبطئ الاتصال الواحد
 *   الذي يطلب الملف كله، ولا تُبطئ الأجزاء الصغيرة؛ الجودة نفسها (الملف نفسه بايتًا بايتًا).
 * - خط أنابيب: يُنزَّل الدرس التالي أثناء رفع الدرس الحالي.
 * - الاستئناف: الأجزاء المكتملة تُحفظ بجوار الملف، والقائمة تُحفظ في ملف فيُستأنف النقل بعد إغلاق التطبيق أو قتله.
 * - تعمل في الخلفية مع خدمة أمامية وإشعار، والحالة متاحة لكل الشاشات (شريط أسفل التطبيق وشاشة التنزيلات).
 */
class TransferJob(
    private val content: UserContent,
    private val sync: SharedSync,
    private val cacheDir: File,
    private val filesDir: File,
    private val onRunningChanged: (Boolean) -> Unit = {},
    private val onFinished: () -> Unit = {},
) {
    /** phase: 0 بانتظار، 1 تنزيل، 2 رفع، 3 تم، 4 تعذر */
    data class Item(val code: Int, val title: String, val state: String = "", val done: Boolean = false, val error: String? = null, val phase: Int = 0, val bytes: Long = 0, val total: Long = 0)

    data class State(
        val running: Boolean = false,
        val items: List<Item> = emptyList(),
        val doneCount: Int = 0,
        val current: String = "",
        val phase: String = "",
        val bytes: Long = 0,
        val total: Long = 0,
        val finishedAt: Long = 0,
        /** الجودة: YouTubeMedia.QUALITY_AUDIO / QUALITY_VIDEO_SD / QUALITY_VIDEO_HD */
        val quality: Int = YouTubeMedia.QUALITY_AUDIO,
        /** نتيجة النشر التلقائي بعد النقل (فارغة إن لم يُنشر) */
        val publishMessage: String = "",
        val published: Boolean = false,
        val sheekhId: Int = 0,
        val bookId: Int = 0,
        val bookName: String = "",
        val subDir: String = "",
        /** السرعة الكلية (تنزيل + رفع) بايت/ث خلال آخر ثوانٍ */
        val speedBps: Long = 0,
        val startedAt: Long = 0,
        val bytesDone: Long = 0,
        /** أغلق المستخدم شريط الحالة بعد الانتهاء */
        val dismissed: Boolean = false,
        /** استُؤنف تلقائيًا بعد إعادة فتح التطبيق */
        val resumed: Boolean = false,
    ) {
        val okCount: Int get() = items.count { it.done }
        val errCount: Int get() = items.count { it.error != null }
        val pendingCount: Int get() = items.count { !it.done && it.error == null }
        val visible: Boolean get() = items.isNotEmpty() && !dismissed
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val queueFile get() = File(filesDir, "transfer-queue.json")

    // قياس السرعة: نافذة منزلقة
    private val speedSamples = ArrayDeque<Pair<Long, Long>>() // (وقت، مجموع البايتات)
    private val movedBytes = AtomicLong(0)

    private fun recordBytes(n: Long) {
        val total = movedBytes.addAndGet(n)
        val now = System.currentTimeMillis()
        synchronized(speedSamples) {
            speedSamples.addLast(now to total)
            while (speedSamples.size > 2 && now - speedSamples.first().first > 8000) speedSamples.removeFirst()
            val (t0, b0) = speedSamples.first()
            val dt = now - t0
            if (dt > 800) _state.update { it.copy(speedBps = (total - b0) * 1000 / dt, bytesDone = total) }
        }
    }

    private fun upd(code: Int, f: (Item) -> Item) {
        _state.update { st -> st.copy(items = st.items.map { if (it.code == code) f(it) else it }) }
    }

    private fun refreshSummary() {
        _state.update { st ->
            val down = st.items.firstOrNull { it.phase == 1 }
            val up = st.items.firstOrNull { it.phase == 2 }
            val parts = ArrayList<String>()
            if (up != null) parts.add("رفع «${up.title.take(30)}» " + (if (up.total > 0) "${ArabicText.arabicDigits((up.bytes * 100 / up.total).toInt())}٪" else ""))
            if (down != null) parts.add("تنزيل «${down.title.take(30)}» " + (if (down.total > 0) "${ArabicText.arabicDigits((down.bytes * 100 / down.total).toInt())}٪" else ""))
            val cur = up ?: down
            st.copy(
                doneCount = st.items.count { it.done || it.error != null },
                current = cur?.title ?: "",
                phase = parts.joinToString(" — "),
                bytes = cur?.bytes ?: 0, total = cur?.total ?: 0,
            )
        }
    }

    // ---------- الحفظ والاستئناف ----------

    private fun saveQueue() {
        val st = _state.value
        runCatching {
            val o = JSONObject().put("sheekhId", st.sheekhId).put("bookId", st.bookId).put("bookName", st.bookName).put("subDir", st.subDir).put("quality", st.quality).put("startedAt", st.startedAt)
            val arr = JSONArray()
            for (it in st.items) arr.put(JSONObject().put("code", it.code).put("title", it.title).put("done", it.done).put("error", it.error ?: JSONObject.NULL))
            o.put("items", arr)
            queueFile.writeText(o.toString())
        }
    }

    private fun clearQueue() { runCatching { queueFile.delete() } }

    /** استئناف قائمة نقل لم تكتمل (بعد إغلاق التطبيق أو قتله) — يُستدعى عند بدء التطبيق */
    fun resumeIfPending() {
        if (_state.value.running) return
        val text = runCatching { queueFile.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        scope.launch {
            try {
                val o = JSONObject(text)
                val arr = o.getJSONArray("items")
                val pending = ArrayList<Chapter>()
                val doneItems = ArrayList<Item>()
                for (i in 0 until arr.length()) {
                    val it = arr.getJSONObject(i)
                    val code = it.getInt("code")
                    if (it.optBoolean("done")) { doneItems.add(Item(code, it.optString("title"), "تم — يُشغَّل من الخادم", done = true, phase = 3)); continue }
                    val ch = content.chapter(-code)
                    if (ch == null || !ch.needsTransfer) { if (ch != null) doneItems.add(Item(code, ch.displayTitle, "على الخادم", done = true, phase = 3)); continue }
                    pending.add(ch)
                }
                if (pending.isEmpty() || !sync.isAdmin) { clearQueue(); return@launch }
                start(pending, o.optString("subDir"), if (o.has("quality")) o.optInt("quality") else (if (o.optBoolean("video")) YouTubeMedia.QUALITY_VIDEO_SD else YouTubeMedia.QUALITY_AUDIO), o.optInt("sheekhId"), o.optInt("bookId"), o.optString("bookName"), previousDone = doneItems, resumed = true, startedAt = o.optLong("startedAt"))
            } catch (e: Exception) {
                clearQueue()
            }
        }
    }

    // ---------- التشغيل ----------

    fun start(chapters: List<Chapter>, subDir: String, quality: Int, sheekhId: Int = 0, bookId: Int = 0, bookName: String = "", previousDone: List<Item> = emptyList(), resumed: Boolean = false, startedAt: Long = 0) {
        if (_state.value.running) return
        val todo = chapters.filter { it.needsTransfer }
        if (todo.isEmpty()) return
        val now = System.currentTimeMillis()
        movedBytes.set(0); synchronized(speedSamples) { speedSamples.clear() }
        _state.value = State(
            running = true, items = previousDone + todo.map { Item(it.code, it.displayTitle, "بانتظار") }, quality = quality,
            sheekhId = sheekhId, bookId = bookId, bookName = bookName, subDir = subDir, startedAt = if (startedAt > 0) startedAt else now, resumed = resumed,
        )
        saveQueue()
        onRunningChanged(true)
        job = scope.launch {
            try {
                runPipeline(todo, subDir, quality)
                // نشر تلقائي للجميع بعد النقل (إن كان المشرف مسجَّل الدخول) حتى يُشغَّل من الخادم عند كل المستخدمين بلا خطوة يدوية
                if (isActive && _state.value.items.any { it.done } && sync.isAdmin) {
                    _state.update { it.copy(current = "", phase = "نشر التغييرات للجميع…") }
                    try {
                        val msg = sync.publish()
                        _state.update { it.copy(publishMessage = msg, published = true) }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        _state.update { it.copy(publishMessage = "لم يُنشر تلقائيًا: ${e.message} — انشر من شاشة المحتوى المضاف", published = false) }
                    }
                }
                clearQueue()
            } catch (e: kotlinx.coroutines.CancellationException) {
                saveQueue() // يبقى في القائمة للاستئناف
            } finally {
                _state.update { it.copy(running = false, current = "", phase = "", finishedAt = System.currentTimeMillis()) }
                onRunningChanged(false)
                onFinished()
            }
        }
    }

    fun cancel() { job?.cancel() }

    /** أسماء ملفات النقل التي يجب إبقاؤها في الذاكرة المؤقتة (القائمة الجارية أو المعلّقة) */
    fun keepFiles(): Set<String> {
        val st = _state.value
        val pending = st.items.filter { !it.done }
        if (pending.isEmpty() && !queueFile.exists()) return emptySet()
        val dir = File(cacheDir, "transfer")
        return dir.listFiles()?.map { it.name.removeSuffix(".chunks") }?.toSet() ?: emptySet()
    }
    fun clear() { if (!_state.value.running) { _state.value = State(); clearQueue() } }
    fun dismiss() { if (!_state.value.running) _state.update { it.copy(dismissed = true) } }

    /** متابعة ما لم يكتمل في القائمة الحالية (بعد إيقاف أو تعذر) */
    fun retryPending() {
        val st = _state.value
        if (st.running) return
        scope.launch {
            val chapters = st.items.filter { !it.done }.mapNotNull { content.chapter(-it.code) }
            start(chapters, st.subDir, st.quality, st.sheekhId, st.bookId, st.bookName, previousDone = st.items.filter { it.done }, startedAt = st.startedAt)
        }
    }

    private class Downloaded(val ch: Chapter, val ordinal: Int, val file: File, val mime: String, val isVideo: Boolean, val sourceUrl: String)

    /** خط أنابيب: منزِّل ← قناة ← رافع */
    private suspend fun runPipeline(todo: List<Chapter>, subDir: String, quality: Int) = coroutineScope {
        val channel = Channel<Downloaded>(capacity = 1)
        val downloader = launch {
            try {
                for ((i, ch) in todo.withIndex()) {
                    ensureActive()
                    var lastErr: String? = null
                    var out: Downloaded? = null
                    for (attempt in 1..3) {
                        try {
                            upd(ch.code) { it.copy(phase = 1, state = "تنزيل…", error = null) }; refreshSummary()
                            out = downloadOne(ch, i + 1, quality)
                            lastErr = null; break
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            upd(ch.code) { it.copy(state = "أُوقف — يُستأنف لاحقًا", phase = 0) }; throw e
                        } catch (e: Exception) {
                            lastErr = e.message ?: e.javaClass.simpleName
                            upd(ch.code) { it.copy(state = "محاولة ${ArabicText.arabicDigits(attempt)} تعذرت: $lastErr") }
                            delay(3000L * attempt)
                        }
                    }
                    if (out == null) {
                        upd(ch.code) { it.copy(state = "تعذر: $lastErr", error = lastErr, phase = 4) }; refreshSummary(); saveQueue()
                        continue
                    }
                    upd(ch.code) { it.copy(state = "بانتظار الرفع", phase = 0, bytes = 0, total = 0) }; refreshSummary()
                    channel.send(out)
                }
            } finally {
                channel.close()
            }
        }
        val uploader = launch {
            for (d in channel) {
                ensureActive()
                var lastErr: String? = null
                for (attempt in 1..3) {
                    try {
                        upd(d.ch.code) { it.copy(phase = 2, state = "رفع إلى الخادم…", bytes = 0, total = d.file.length()) }; refreshSummary()
                        uploadOne(d, subDir)
                        upd(d.ch.code) { it.copy(state = "تم — يُشغَّل من الخادم", done = true, error = null, phase = 3, bytes = d.file.length(), total = d.file.length()) }
                        lastErr = null; break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        upd(d.ch.code) { it.copy(state = "أُوقف — يُستأنف لاحقًا", phase = 0) }; throw e
                    } catch (e: Exception) {
                        lastErr = e.message ?: e.javaClass.simpleName
                        upd(d.ch.code) { it.copy(state = "محاولة رفع ${ArabicText.arabicDigits(attempt)} تعذرت: $lastErr") }
                        delay(3000L * attempt)
                    }
                }
                if (lastErr != null) upd(d.ch.code) { it.copy(state = "تعذر الرفع: $lastErr", error = lastErr, phase = 4) }
                else d.file.delete()
                refreshSummary(); saveQueue()
            }
        }
        downloader.join(); uploader.join()
    }

    // ---------- التنزيل ----------

    private suspend fun downloadOne(ch: Chapter, ordinal: Int, quality: Int): Downloaded {
        val tmp = File(cacheDir, "transfer").apply { mkdirs() }
        val vid = ch.youtubeId
        if (vid != null) {
            upd(ch.code) { it.copy(state = "استخراج رابط الوسائط من يوتيوب…") }
            val ex = YouTubeMedia.extract(vid)
            val hd0 = ex.videoHd; val au0 = ex.audio
            if (quality == YouTubeMedia.QUALITY_VIDEO_HD && hd0 != null && au0 != null) {
                // جودة عالية: مسار فيديو بلا صوت + مسار صوت، ثم دمج على الهاتف بلا إعادة ترميز
                var vPick: YouTubeMedia.Pick = hd0; var aPick: YouTubeMedia.Pick = au0
                val vLocal = File(tmp, "%03d-%s.video.mp4".format(java.util.Locale.US, ordinal, vid))
                val aLocal = File(tmp, "%03d-%s.audio.%s".format(java.util.Locale.US, ordinal, vid, aPick.ext))
                val out = File(tmp, "%03d-%s.mp4".format(java.util.Locale.US, ordinal, vid))
                if (!(out.exists() && out.length() > 10_000 && !vLocal.exists() && !aLocal.exists())) {
                    val refreshV: suspend () -> String = { val e2 = YouTubeMedia.extract(vid); e2.videoHd?.let { vPick = it }; vPick.url }
                    val refreshA: suspend () -> String = { val e2 = YouTubeMedia.extract(vid); e2.audio?.let { if (it.ext == aPick.ext) aPick = it }; aPick.url }
                    upd(ch.code) { it.copy(state = "تنزيل الفيديو (${vPick.resolution})…") }
                    parallelDownload(vPick.url, refreshV, vLocal, ch.code, "Mozilla/5.0")
                    upd(ch.code) { it.copy(state = "تنزيل الصوت…") }
                    parallelDownload(aPick.url, refreshA, aLocal, ch.code, "Mozilla/5.0")
                    upd(ch.code) { it.copy(state = "دمج الصوت مع الصورة…") }
                    if (aPick.ext == "m4a") {
                        Mp4Mux.mux(vLocal, aLocal, out)
                        vLocal.delete(); aLocal.delete()
                    } else {
                        // صوت opus لا يُدمج في mp4: يُكتفى بالفيديو المدمج العادي إن وُجد
                        vLocal.delete(); aLocal.delete()
                        val sd = ex.video ?: throw IllegalStateException("لا مسار صوت m4a لدمجه مع الفيديو العالي")
                        parallelDownload(sd.url, { YouTubeMedia.extract(vid).video?.url ?: sd.url }, out, ch.code, "Mozilla/5.0")
                    }
                }
                if (out.length() < 10_000) throw IllegalStateException("الملف المنزَّل صغير جدًا")
                return Downloaded(ch, ordinal, out, "video/mp4", true, YouTube.watchUrl(vid))
            }
            val wantVideo = quality != YouTubeMedia.QUALITY_AUDIO
            var pick = (if (wantVideo) ex.video ?: ex.audio else ex.audio) ?: throw IllegalStateException("لم يُعثر على مسار وسائط لهذا الفيديو")
            val name = "%03d-%s.%s".format(java.util.Locale.US, ordinal, vid, pick.ext)
            val local = File(tmp, name)
            val refresh: suspend () -> String = {
                val ex2 = YouTubeMedia.extract(vid)
                val p2 = if (wantVideo) ex2.video ?: ex2.audio else ex2.audio
                if (p2 != null && p2.ext == pick.ext) pick = p2
                pick.url
            }
            parallelDownload(pick.url, refresh, local, ch.code, "Mozilla/5.0")
            if (local.length() < 10_000) throw IllegalStateException("الملف المنزَّل صغير جدًا")
            return Downloaded(ch, ordinal, local, pick.mime, pick.isVideo, YouTube.watchUrl(vid))
        }
        // رابط مباشر
        val src = ch.mediaUri.trim()
        val cleanPath = src.substringBefore('?').substringBefore('#')
        val ext = cleanPath.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } } ?: (if (ch.isVideo) "mp4" else "mp3")
        val id = java.security.MessageDigest.getInstance("SHA-1").digest(src.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)
        val name = "%03d-%s.%s".format(java.util.Locale.US, ordinal, id, ext)
        val local = File(tmp, name)
        var mime = ""
        runCatching {
            NasHttp.client.newCall(Request.Builder().url(src).header("Range", "bytes=0-0").build()).execute().use { r -> mime = (r.header("Content-Type") ?: "").substringBefore(';').trim() }
        }
        try {
            parallelDownload(src, { src }, local, ch.code, "AhlAlhadeeth-Android/1.0")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // خادم لا يدعم Range: تنزيل عادي مع استئناف
            NasHttp.download(src, local, -1, attempts = 6) { b, t -> upd(ch.code) { it.copy(bytes = b, total = t) }; refreshSummary() }
        }
        if (local.length() < 10_000) throw IllegalStateException("الملف المنزَّل صغير جدًا")
        val isVideo = ch.isVideo || mime.startsWith("video/") || ext in setOf("mp4", "webm", "mkv", "m4v")
        if (mime.isBlank() || mime == "application/octet-stream") mime = if (isVideo) "video/mp4" else if (ext == "m4a") "audio/mp4" else "audio/mpeg"
        return Downloaded(ch, ordinal, local, mime, isVideo, src)
    }

    /** تنزيل متوازٍ (انظر [ParallelDownload]) مع تحديث تقدّم الدرس والسرعة الكلية */
    private suspend fun parallelDownload(url0: String, refreshUrl: suspend () -> String, local: File, code: Int, ua: String) {
        ParallelDownload.download(url0, refreshUrl, local, ua,
            onProgress = { b, t -> upd(code) { it.copy(bytes = b, total = t) }; refreshSummary() },
            onBytes = { n -> recordBytes(n) })
    }

    // ---------- الرفع ----------

    private suspend fun uploadOne(d: Downloaded, subDir: String) {
        val size = d.file.length()
        var lastRep = 0L
        var lastSent = 0L
        val remotePath = sync.uploadMedia(d.file, subDir, d.file.name, d.mime) { sent ->
            recordBytes(sent - lastSent); lastSent = sent
            if (sent - lastRep > 512 * 1024) { lastRep = sent; upd(d.ch.code) { it.copy(bytes = sent, total = size) }; refreshSummary() }
        }
        upd(d.ch.code) { it.copy(state = "إنشاء رابط المشاركة…", bytes = size, total = size) }; refreshSummary()
        val link = sync.createShareLink(remotePath)
        content.updateChapter(-d.ch.code, d.ch.title, link, d.isVideo, d.ch.notes, dirty = true, sourceUrl = d.sourceUrl)
        content.setChapterSize(-d.ch.code, size)
    }
}
