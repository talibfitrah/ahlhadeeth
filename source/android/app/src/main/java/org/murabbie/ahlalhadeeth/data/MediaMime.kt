package org.murabbie.ahlalhadeeth.data

/** أنواع MIME لوسائط الدروس عند إرسالها إلى Gemini (دوال خالصة بلا واجهات أندرويد) */
object MediaMime {
    /** النوع الصوتي (audio) الموافق لحاوية فيديو (أو النوع نفسه إن كان صوتيًا) */
    fun audioMimeFor(mime: String, name: String): String {
        val n = name.lowercase()
        return when {
            mime.startsWith("audio/") && mime != "audio/x-m4a" -> mime
            mime == "audio/x-m4a" || mime == "video/mp4" || mime == "video/quicktime" || n.endsWith(".m4a") || n.endsWith(".mp4") -> "audio/mp4"
            mime == "video/webm" || mime == "video/x-matroska" || n.endsWith(".webm") || n.endsWith(".mkv") -> "audio/webm"
            mime == "video/ogg" -> "audio/ogg"
            else -> mime
        }
    }

    /** أنواع بديلة تُجرَّب إن رفض Gemini معالجة الملف بالنوع الأول */
    fun alternatives(mime: String, name: String): List<String> {
        val a = audioMimeFor(mime, name)
        val n = name.lowercase()
        val list = ArrayList<String>()
        if (a != mime) list.add(a)
        when {
            a == "audio/webm" -> list.add("video/webm")
            a == "audio/mp4" -> list.add("video/mp4")
            a == "audio/aac" && (n.endsWith(".m4a") || n.endsWith(".mp4")) -> list.add("audio/mp4")
        }
        return list.filter { it != mime }.distinct()
    }

}
