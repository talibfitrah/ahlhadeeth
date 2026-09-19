package org.murabbie.ahlalhadeeth.data

import android.content.Context
import java.io.File

/** تسجيل الأعطال (الاستثناءات غير المعالجة) في ملف داخل التطبيق ليطّلع عليه المستخدم ويرسله */
object CrashLog {
    private const val FILE = "crash.log"
    private const val MAX = 200 * 1024

    fun install(context: Context, versionName: String) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val f = File(context.filesDir, FILE)
                val sb = StringBuilder()
                sb.append("=== ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                    .append(" — v").append(versionName).append(" — Android ").append(android.os.Build.VERSION.RELEASE).append(" — ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                    .append(" — thread ").append(t.name).append('\n')
                sb.append(android.util.Log.getStackTraceString(e)).append('\n')
                val old = if (f.exists()) f.readText() else ""
                var text = sb.toString() + old
                if (text.length > MAX) text = text.substring(0, MAX)
                f.writeText(text)
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun read(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.ifBlank { null } }.getOrNull()
    fun clear(context: Context) { runCatching { File(context.filesDir, FILE).delete() } }
    fun exists(context: Context): Boolean = File(context.filesDir, FILE).let { it.exists() && it.length() > 0 }
}

/**
 * تنظيف الذاكرة المؤقتة تلقائيًا: ملفات النقل والتفريغ المؤقتة التي لم تعد لازمة، ومخلفات WebView، مع حدّ أقصى لحجم المجلد.
 */
object CacheCleaner {
    /** الحد الأقصى المقبول لمجلد الذاكرة المؤقتة قبل حذف الأقدم */
    private const val MAX_BYTES = 200L * 1024 * 1024

    fun sizeOf(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    fun cacheSize(context: Context): Long = sizeOf(context.cacheDir) + sizeOf(context.externalCacheDir) + sizeOf(context.codeCacheDir)

    /**
     * تنظيف تلقائي: يُستدعى عند بدء التطبيق وبعد انتهاء النقل أو التفريغ.
     * @param keepTransfer أسماء ملفات النقل الجارية أو المعلّقة (لا تُحذف)
     */
    fun autoClean(context: Context, keepTransfer: Set<String> = emptySet(), transferRunning: Boolean = false, autoIndexRunning: Boolean = false): Long {
        var freed = 0L
        val cache = context.cacheDir
        // ملفات التفريغ المؤقتة: تُحذف كلها (كل عملية تنزّل ما تحتاجه من جديد) — إلا أثناء تفريغ جارٍ
        if (!autoIndexRunning) freed += deleteAll(File(cache, "autoindex"))
        // ملفات النقل: ما ليس في القائمة الحالية (أو كلها إن لم يكن نقل جارٍ ولا معلّق)
        val tr = File(cache, "transfer")
        if (tr.isDirectory) tr.listFiles()?.forEach { f ->
            val base = f.name.removeSuffix(".chunks")
            if (transferRunning && base in keepTransfer) return@forEach
            if (!transferRunning && keepTransfer.isNotEmpty() && base in keepTransfer && System.currentTimeMillis() - f.lastModified() < 2L * 24 * 3600_000) return@forEach
            freed += f.length(); f.delete()
        }
        // مخلفات WebView (مشغّل يوتيوب)
        freed += deleteAll(File(cache, "WebView"))
        freed += deleteAll(File(cache, "org.chromium.android_webview"))
        // ملفات مؤقتة يتيمة
        cache.listFiles()?.forEach { f -> if (f.isFile && (f.name.endsWith(".part") || f.name.endsWith(".tmp") || f.name.endsWith(".bin"))) { freed += f.length(); f.delete() } }
        // حدّ أقصى: حذف الأقدم
        var size = sizeOf(cache)
        if (size > MAX_BYTES) {
            val files = cache.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
            for (f in files) {
                if (size <= MAX_BYTES) break
                if (transferRunning && f.name.removeSuffix(".chunks") in keepTransfer) continue
                val n = f.length(); if (f.delete()) { size -= n; freed += n }
            }
        }
        return freed
    }

    /** مسح يدوي كامل للذاكرة المؤقتة (لا يمس التنزيلات ولا البيانات) */
    fun clearAll(context: Context, keepTransfer: Set<String> = emptySet()): Long {
        var freed = 0L
        for (dir in listOfNotNull(context.cacheDir, context.externalCacheDir, context.codeCacheDir)) {
            dir.walkBottomUp().forEach { f ->
                if (f == dir) return@forEach
                if (f.isFile) {
                    if (f.name.removeSuffix(".chunks") in keepTransfer) return@forEach
                    val n = f.length(); if (f.delete()) freed += n
                } else if (f.isDirectory) f.delete()
            }
        }
        return freed
    }

    private fun deleteAll(dir: File): Long {
        if (!dir.exists()) return 0
        var freed = 0L
        dir.walkBottomUp().forEach { f -> if (f.isFile) { freed += f.length(); f.delete() } else if (f != dir) f.delete() }
        return freed
    }
}
