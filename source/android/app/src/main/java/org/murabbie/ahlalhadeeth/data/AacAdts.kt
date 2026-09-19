package org.murabbie.ahlalhadeeth.data

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/** ما في ملف وسائط من مسارات: هل فيه فيديو؟ ونوع مسار الصوت (mp4a-latm، opus، vorbis، mpeg…) */
data class MediaTracks(val hasVideo: Boolean, val audioMime: String?, val readable: Boolean)

/**
 * تهيئة ملف وسائط لإرساله إلى Gemini: Gemini يرفض معالجة (FAILED) ملف WebM صوتي فقط إن أُرسل بنوع video/webm،
 * وحاويات MP4 المجزّأة أحيانًا؛ فتُفحص المسارات، ويُستخرج مسار AAC إلى ملف ADTS صغير بلا إعادة ترميز إن وُجد،
 * وإلا يُصحَّح نوع الملف إلى نوع صوتي (audio) إن لم يكن فيه فيديو (audio/webm مقبول ومجرَّب).
 */
object MediaPrep {
    fun probe(file: File): MediaTracks {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            var video = false; var audio: String? = null
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) video = true
                if (m.startsWith("audio/") && audio == null) audio = m
            }
            MediaTracks(video, audio, ex.trackCount > 0)
        } catch (e: Exception) {
            MediaTracks(false, null, false)
        } finally { runCatching { ex.release() } }
    }

    /**
     * يعيد (الملف، النوع) الأنسب للإرسال: ملف AAC مستخرج، أو الملف نفسه بنوع مصحَّح.
     * @param outDir مجلد الملف المستخرج (يُحذف الأصل عند نجاح الاستخراج)
     */
    fun prepare(file: File, mime: String, outDir: File, onStatus: (String) -> Unit): Pair<File, String> {
        val name = file.name.lowercase()
        if (name.endsWith(".mp3") || mime == "audio/mpeg") return file to "audio/mpeg"
        if (name.endsWith(".aac")) return file to "audio/aac"
        val t = probe(file)
        if (t.audioMime == "audio/mp4a-latm") {
            onStatus("استخراج الصوت من الملف…")
            val aac = File(outDir, file.nameWithoutExtension + ".aac")
            if (AacAdts.extract(file, aac) && aac.length() > 10_000) {
                runCatching { file.delete() }
                return aac to "audio/aac"
            }
            runCatching { aac.delete() }
        }
        if (!t.readable) return file to mime
        return if (t.hasVideo) file to mime else file to MediaMime.audioMimeFor(mime, name)
    }
}

/**
 * استخراج مسار الصوت AAC من حاوية MP4/M4A/MKV (ولو كانت مجزّأة كما تأتي من يوتيوب) إلى ملف ADTS (.aac) بلا إعادة ترميز.
 */
object AacAdts {
    private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** يعيد true إن نجح الاستخراج إلى [out] */
    fun extract(src: File, out: File): Boolean {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src.absolutePath)
            val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" } ?: return false
            val fmt = ex.getTrackFormat(idx)
            // AudioSpecificConfig (csd-0): 5 بت نوع الكائن، 4 بت فهرس معدل العينات، 4 بت تشكيل القنوات
            val csd = fmt.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { arr -> b.duplicate().get(arr) } }
            var aot = 2; var srIdx = -1; var ch = -1
            if (csd != null && csd.size >= 2) {
                val b0 = csd[0].toInt() and 0xFF; val b1 = csd[1].toInt() and 0xFF
                aot = b0 shr 3; srIdx = ((b0 and 7) shl 1) or (b1 shr 7); ch = (b1 shr 3) and 0xF
            }
            if (aot !in 1..4) aot = 2 // HE-AAC (5/29) يُشار إليه في ADTS بـ LC ضمنيًا
            if (srIdx < 0 || srIdx > 12) srIdx = SAMPLE_RATES.indexOf(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)).takeIf { it >= 0 } ?: 4
            if (ch <= 0 || ch > 7) ch = runCatching { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2).coerceIn(1, 7)
            val profile = aot - 1 // في ADTS: 1 = AAC LC
            ex.selectTrack(idx)
            val buf = ByteBuffer.allocate(1 shl 20)
            val hdr = ByteArray(7)
            var frames = 0
            out.outputStream().buffered(1 shl 16).use { os ->
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    if (n > 0 && n + 7 < (1 shl 13)) {
                        val len = n + 7
                        hdr[0] = 0xFF.toByte(); hdr[1] = 0xF1.toByte()
                        hdr[2] = ((profile shl 6) or (srIdx shl 2) or (ch shr 2)).toByte()
                        hdr[3] = (((ch and 3) shl 6) or (len shr 11)).toByte()
                        hdr[4] = ((len shr 3) and 0xFF).toByte()
                        hdr[5] = (((len and 7) shl 5) or 0x1F).toByte()
                        hdr[6] = 0xFC.toByte()
                        os.write(hdr); os.write(buf.array(), buf.arrayOffset(), n)
                        frames++
                    }
                    if (!ex.advance()) break
                }
            }
            if (frames == 0) { runCatching { out.delete() }; return false }
            return true
        } catch (e: Exception) {
            runCatching { out.delete() }
            return false
        } finally {
            runCatching { ex.release() }
        }
    }
}
