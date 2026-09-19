package org.murabbie.ahlalhadeeth.data

import java.net.URLDecoder
import java.net.URLEncoder

/** بناء عناوين ملفات الصوت على خوادم الصوت (قاعدة يُلحق بها المسار، أو قالب فيه {path}) — بلا اعتماد على أندرويد */
object AudioUrls {
    const val PLACEHOLDER = "{path}"

    fun encodePath(rel: String): String = rel.split('/').joinToString("/") { seg -> URLEncoder.encode(seg, "UTF-8").replace("+", "%20") }

    fun decodePath(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** عنوان الملف: قالب فيه {path} (كخادم البيانات عبر مشاركة مجلد DSM) أو قاعدة عادية */
    fun buildUrl(server: String, rel: String): String {
        val base = server.trim()
        if (base.contains(PLACEHOLDER)) return base.replace(PLACEHOLDER, encodePath(rel))
        return (if (base.endsWith("/")) base else "$base/") + rel
    }

    /** المسار النسبي إن كان [url] مبنيًّا من [server] بالطريقة نفسها (لتبديل الخادم عند تعذر الملف) */
    fun relOf(server: String, url: String): String? {
        val base = server.trim()
        if (base.contains(PLACEHOLDER)) {
            val i = base.indexOf(PLACEHOLDER)
            val prefix = base.substring(0, i)
            val suffix = base.substring(i + PLACEHOLDER.length)
            if (url.length > prefix.length + suffix.length && url.startsWith(prefix) && url.endsWith(suffix)) return decodePath(url.substring(prefix.length, url.length - suffix.length))
            return null
        }
        val b = if (base.endsWith("/")) base else "$base/"
        return if (url.startsWith(b) && url.length > b.length) url.substring(b.length) else null
    }
}
