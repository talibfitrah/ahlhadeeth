package org.murabbie.ahlalhadeeth.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * تنزيل متوازٍ على أجزاء بطلبات Range (٤ اتصالات × ٤ م.ب) مع استئناف الأجزاء المكتملة (تُحفظ في ملف .chunks بجوار الملف).
 * خوادم يوتيوب تُبطئ الاتصال الواحد الذي يطلب الملف كله ولا تُبطئ الأجزاء؛ الملف الناتج هو نفسه بايتًا بايتًا.
 * يُستدعى [refreshUrl] عند انتهاء صلاحية الرابط (403/410) لإعادة الاستخراج. يرمي استثناءً إن كان الخادم لا يدعم Range.
 */
object ParallelDownload {
    suspend fun download(
        url0: String,
        refreshUrl: suspend () -> String,
        local: File,
        ua: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onBytes: (Long) -> Unit = {},
        conns: Int = 4,
        chunk: Long = 4L * 1024 * 1024,
    ) = withContext(Dispatchers.IO) {
        var url = url0
        val urlLock = Mutex()
        var urlVersion = 0
        // حجم الملف
        var total = -1L
        for (i in 0 until 4) {
            NasHttp.client.newCall(Request.Builder().url(url).header("User-Agent", ua).header("Range", "bytes=0-0").build()).execute().use { r ->
                if (r.code == 206) total = (r.header("Content-Range") ?: "").substringAfter('/', "").toLongOrNull() ?: -1L
                else if (r.code == 200) total = r.body?.contentLength() ?: -1L
                else if (r.code == 403 || r.code == 410) { url = urlLock.withLock { urlVersion++; refreshUrl() } }
            }
            if (total > 0) break
            delay(1000L * (i + 1))
        }
        if (total <= 0) throw java.io.IOException("تعذر معرفة حجم الملف (الخادم لا يدعم Range)")
        val nChunks = ((total + chunk - 1) / chunk).toInt()
        val doneFile = File(local.path + ".chunks")
        val done = java.util.BitSet(nChunks)
        local.parentFile?.mkdirs()
        if (local.exists() && local.length() == total && doneFile.exists()) {
            runCatching { val arr = JSONArray(doneFile.readText()); for (i in 0 until arr.length()) done.set(arr.getInt(i)) }
        } else {
            RandomAccessFile(local, "rw").use { it.setLength(total) }
            doneFile.delete()
        }
        val doneBytes = AtomicLong((0 until nChunks).filter { done.get(it) }.sumOf { minOf(chunk, total - it * chunk) })
        onProgress(doneBytes.get(), total)
        val queue = Channel<Int>(Channel.UNLIMITED)
        for (i in 0 until nChunks) if (!done.get(i)) queue.send(i)
        queue.close()
        val doneLock = Mutex()
        val lastReport = AtomicLong(doneBytes.get())
        suspend fun saveDone() = doneLock.withLock {
            val arr = JSONArray(); for (i in 0 until nChunks) if (done.get(i)) arr.put(i)
            runCatching { doneFile.writeText(arr.toString()) }
        }
        coroutineScope {
            val workers = (0 until conns).map {
                async {
                    val buf = ByteArray(256 * 1024)
                    RandomAccessFile(local, "rw").use { raf ->
                        for (idx in queue) {
                            val start = idx * chunk
                            val end = minOf(start + chunk, total) - 1
                            var attempts = 0
                            var myVersion = urlVersion
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                attempts++
                                try {
                                    val u = url
                                    NasHttp.client.newCall(Request.Builder().url(u).header("User-Agent", ua).header("Range", "bytes=$start-$end").build()).execute().use { r ->
                                        if (r.code == 403 || r.code == 410) {
                                            // رابط منتهٍ: يجدّده عامل واحد فقط
                                            urlLock.withLock { if (urlVersion == myVersion) { url = refreshUrl(); urlVersion++ }; myVersion = urlVersion }
                                            throw java.io.IOException("HTTP ${r.code}")
                                        }
                                        if (r.code != 206) throw java.io.IOException("HTTP ${r.code}")
                                        val body = r.body ?: throw java.io.IOException("لا محتوى")
                                        var pos = start
                                        var got = 0L
                                        body.byteStream().use { input ->
                                            while (true) {
                                                currentCoroutineContext().ensureActive()
                                                val n = input.read(buf); if (n < 0) break
                                                raf.seek(pos); raf.write(buf, 0, n)
                                                pos += n; got += n
                                                onBytes(n.toLong())
                                                val db = doneBytes.addAndGet(n.toLong())
                                                if (db - lastReport.get() > 512 * 1024) { lastReport.set(db); onProgress(db, total) }
                                            }
                                        }
                                        if (got != end - start + 1) { doneBytes.addAndGet(-got); throw java.io.IOException("جزء ناقص") }
                                    }
                                    doneLock.withLock { done.set(idx) }
                                    saveDone()
                                    break
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                    if (attempts >= 8) throw e
                                    delay(minOf(20_000L, 1500L * attempts))
                                }
                            }
                        }
                    }
                }
            }
            workers.forEach { it.await() }
        }
        if (done.cardinality() != nChunks) throw java.io.IOException("لم تكتمل الأجزاء")
        doneFile.delete()
        onProgress(total, total)
    }
}
