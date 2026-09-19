package org.murabbie.ahlalhadeeth.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * دمج مسار فيديو (mp4 بلا صوت) مع مسار صوت (m4a) في ملف mp4 واحد بلا إعادة ترميز (MediaMuxer)،
 * مع تداخل العينات بحسب الزمن ليصلح الملف للبث من الخادم بطلبات Range.
 */
object Mp4Mux {
    fun mux(video: File, audio: File, out: File) {
        out.delete()
        val vx = MediaExtractor(); val ax = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            vx.setDataSource(video.absolutePath); ax.setDataSource(audio.absolutePath)
            val vIdx = firstTrack(vx, "video/") ?: throw IllegalStateException("لا مسار فيديو")
            val aIdx = firstTrack(ax, "audio/") ?: throw IllegalStateException("لا مسار صوت")
            vx.selectTrack(vIdx); ax.selectTrack(aIdx)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vt = muxer.addTrack(vx.getTrackFormat(vIdx))
            val at = muxer.addTrack(ax.getTrackFormat(aIdx))
            muxer.start()
            val buf = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var vDone = false; var aDone = false
            while (!vDone || !aDone) {
                // العينة الأبكر زمنًا أولًا (تداخل)
                val vt0 = if (vDone) Long.MAX_VALUE else vx.sampleTime
                val at0 = if (aDone) Long.MAX_VALUE else ax.sampleTime
                val useVideo = !vDone && (aDone || vt0 <= at0)
                val ex = if (useVideo) vx else ax
                val n = ex.readSampleData(buf, 0)
                if (n < 0) { if (useVideo) vDone = true else aDone = true; continue }
                info.offset = 0; info.size = n; info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(if (useVideo) vt else at, buf, info)
                if (!ex.advance()) { if (useVideo) vDone = true else aDone = true }
            }
            muxer.stop()
        } finally {
            runCatching { muxer?.release() }
            runCatching { vx.release() }; runCatching { ax.release() }
        }
    }

    private fun firstTrack(ex: MediaExtractor, prefix: String): Int? {
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        return null
    }
}
