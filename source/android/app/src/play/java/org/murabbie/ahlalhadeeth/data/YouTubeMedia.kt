package org.murabbie.ahlalhadeeth.data

/**
 * نسخة Google Play: لا استخراج ولا تنزيل لوسائط يوتيوب داخل التطبيق (تنزيل محتوى يوتيوب مخالف لسياسات Google Play وشروط خدمة يوتيوب،
 * ويُرفض التطبيق بسببه). الواجهة نفسها كما في نكهة التوزيع المباشر حتى يُترجَم بقية المشروع بلا تغيير، لكن الاستخراج يرمي خطأً مفهومًا.
 * نقل دروس يوتيوب إلى الخادم يُجرى من نسخة التوزيع المباشر أو بأداة yt2nas.py من حاسوب.
 */
object YouTubeMedia {

    data class Pick(val url: String, val mime: String, val ext: String, val bitrateKbps: Int, val isVideo: Boolean, val resolution: String)
    data class Extracted(val title: String, val uploader: String, val durationSec: Long, val audio: Pick?, val video: Pick?, val subtitleArUrl: String?, val videoHd: Pick? = null)

    const val QUALITY_AUDIO = 0
    const val QUALITY_VIDEO_SD = 1
    const val QUALITY_VIDEO_HD = 2

    const val UNAVAILABLE = "نقل وسائط يوتيوب غير متاح في هذه النسخة"

    fun qualityLabel(q: Int): String = when (q) { QUALITY_VIDEO_SD -> "مرئية عادية (٣٦٠p)"; QUALITY_VIDEO_HD -> "مرئية عالية (حتى ١٠٨٠p)"; else -> "مسموعة فقط (صوت m4a)" }

    @Suppress("UNUSED_PARAMETER")
    suspend fun extract(videoId: String): Extracted = throw IllegalStateException(UNAVAILABLE)
}
