package org.murabbie.ahlalhadeeth.data

data class Sheekh(val id: Int, val name: String, val isDefault: Boolean, val ord: Int)

data class BookType(val id: Int, val name: String, val ord: Int)

data class Book(val id: Int, val name: String, val ord: Int, val typeId: Int, val typeName: String = "")

/** كتاب مع عدد أشرطته عند شيخ معيّن */
data class SheekhBook(val book: Book, val sheekhId: Int, val chapterCount: Int, val segmentCount: Int)

data class Chapter(
    val code: Int,
    val sheekhId: Int,
    val bookId: Int,
    val title: String,
    val fileName: String,
    val fileSize: Long,
    val path: String,
    val cdNumber: String,
    val ord: Int,
    val segCount: Int,
    val writeCount: Int,
    val sheekhName: String = "",
    val bookName: String = "",
    /** للمحتوى المضاف: رابط الوسائط (http/https) أو content:// أو file:// */
    val mediaUri: String = "",
    val isVideo: Boolean = false,
    val notes: String = "",
    /** للمحتوى المضاف: local (على هذا الجهاز) أو shared (منشور من المشرفين) */
    val origin: String = "local",
    /** تعديلات لم تُنشر بعد */
    val dirty: Boolean = false,
    val key: String = "",
    /** رابط المصدر الأصلي (فيديو يوتيوب) عندما تكون الوسائط منقولة إلى الخادم */
    val sourceUrl: String = "",
) {
    /** المحتوى المضاف من المستخدم له رموز سالبة */
    val isUser: Boolean get() = code < 0
    /** معرّف فيديو يوتيوب إن كان الدرس يُشغَّل من يوتيوب */
    val youtubeId: String? get() = if (isUser) YouTube.extractId(mediaUri) else null
    val isYouTube: Boolean get() = youtubeId != null
    /** معرّف فيديو يوتيوب للتفريغ الآلي (من رابط التشغيل أو من رابط المصدر بعد النقل إلى الخادم) */
    val captionSourceId: String? get() = if (isUser) (YouTube.extractId(mediaUri) ?: YouTube.extractId(sourceUrl)) else null
    /** الوسائط على خادم البيانات (رابط مشاركة DSM) */
    val isOnServer: Boolean get() = isUser && NasHttp.isShareLink(mediaUri)
    /** درس مضاف وسائطه على الإنترنت خارج خادم البيانات (يوتيوب أو رابط مباشر) — يلزم نقله إلى الخادم */
    val needsTransfer: Boolean get() = isUser && mediaUri.trim().startsWith("http", true) && !isOnServer && org.murabbie.ahlalhadeeth.BuildConfig.DISTRIBUTION != "play"
    /** المسار النسبي للملف الصوتي، مثل alalbani/alnoor/001.mp3 (وللمحتوى المضاف user/u12.mp4) */
    fun relativeAudioPath(ext: String = ".mp3"): String = if (isUser) "$path/$fileName${if (isVideo) ".mp4" else ".mp3"}" else "$path/$fileName$ext"
    val displayTitle: String get() = if (title.isBlank()) fileName else title
}

data class Category(
    val id: Int,
    val name: String,
    val ord: Int,
    val parent: Int,
    val level: Int,
    val directCount: Int,
    val totalCount: Int,
    val childCount: Int,
)

data class Segment(
    val id: Long,
    val code: Int,
    val seq: Int,
    val sheekhId: Int,
    val bookId: Int,
    val hnum: Int,
    val line: String,
    val offsetStart: Long,
    val offsetEnd: Long,
    val hasWrite: Boolean,
    val ques: Boolean,
) {
    val isUser: Boolean get() = id < 0
}

/** مقطع مع بيانات شريطه (لنتائج البحث والتصانيف والمفضلة) */
data class SegmentWithChapter(
    val segment: Segment,
    val chapter: Chapter,
    val snippet: String = "",
    val matchedInWrite: Boolean = false,
)

data class SearchOptions(
    val query: String,
    val matchAll: Boolean = true,
    val inLine: Boolean = true,
    val inWrite: Boolean = true,
    val exactPhrase: Boolean = false,
    val sheekhIds: Set<Int> = emptySet(),
    val bookIds: Set<Int> = emptySet(),
    val questionsOnly: Boolean = false,
    val limit: Int = 300,
    val offset: Int = 0,
)

data class SheekhStats(
    val sheekh: Sheekh,
    val books: Int,
    val chapters: Int,
    val segments: Int,
    val writes: Int,
    val bytes: Long,
)

data class BookStats(val book: Book, val chapters: Int, val segments: Int, val writes: Int, val bytes: Long)

data class DbInfo(val meta: Map<String, String>, val sizeBytes: Long)
