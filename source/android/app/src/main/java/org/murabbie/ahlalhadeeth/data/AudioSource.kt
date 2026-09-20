package org.murabbie.ahlalhadeeth.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** تحديد مصدر الملف الصوتي لشريط: ملف منزَّل، أو مجلد محلي اختاره المستخدم، أو الخادم. */
class AudioSource(private val context: Context, private val settings: Settings) {

    /** كاش قوائم المجلدات داخل شجرة SAF: المسار النسبي للمجلد -> (اسم صغير -> DocumentFile) */
    private val dirCache = HashMap<String, Map<String, DocumentFile>?>()
    private var cachedTreeUri: String = ""

    fun downloadsDir(): File {
        val s = settings.value
        val dir = if (s.downloadToExternal) (context.getExternalFilesDir("sound") ?: File(context.filesDir, "sound")) else File(context.filesDir, "sound")
        dir.mkdirs()
        return dir
    }

    fun localFile(chapter: Chapter): File = File(downloadsDir(), chapter.relativeAudioPath(settings.value.audioExt))

    fun remoteUrl(chapter: Chapter): String {
        if (chapter.isUser) return if (chapter.mediaUri.startsWith("http", true)) chapter.mediaUri else ""
        val s = settings.value
        return AudioUrls.buildUrl(s.audioBaseUrl, chapter.relativeAudioPath(s.audioExt))
    }

    @Synchronized
    private fun listDir(tree: String, dirRel: String): Map<String, DocumentFile>? {
        if (tree != cachedTreeUri) {
            dirCache.clear()
            cachedTreeUri = tree
        }
        if (dirCache.containsKey(dirRel)) return dirCache[dirRel]
        val result: Map<String, DocumentFile>? = runCatching {
            if (dirRel.isEmpty()) {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return@runCatching null
                listChildren(root)
            } else {
                val parentRel = dirRel.substringBeforeLast('/', "")
                val name = dirRel.substringAfterLast('/')
                val parentListing = listDir(tree, parentRel) ?: return@runCatching null
                val dir = parentListing[name.lowercase()] ?: return@runCatching null
                if (!dir.isDirectory) return@runCatching null
                listChildren(dir)
            }
        }.getOrNull()
        dirCache[dirRel] = result
        return result
    }

    private fun listChildren(dir: DocumentFile): Map<String, DocumentFile> {
        val m = HashMap<String, DocumentFile>()
        for (f in dir.listFiles()) {
            val n = f.name ?: continue
            m[n.lowercase()] = f
        }
        return m
    }

    /** ملف في مجلد صوت محلي (SAF) إن وُجد */
    fun localTreeFile(chapter: Chapter): Uri? {
        val tree = settings.value.localAudioTreeUri
        if (tree.isEmpty()) return null
        val rel = chapter.relativeAudioPath(settings.value.audioExt)
        val dirRel = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        val listing = listDir(tree, dirRel) ?: return null
        val f = listing[name.lowercase()] ?: return null
        return if (f.isFile) f.uri else null
    }

    data class Resolved(val uri: Uri, val isLocal: Boolean)

    fun resolve(chapter: Chapter): Resolved {
        val f = localFile(chapter)
        if (f.exists() && f.length() > 0) return Resolved(Uri.fromFile(f), true)
        if (chapter.isUser) {
            val m = chapter.mediaUri.trim()
            return if (m.startsWith("http", true)) Resolved(Uri.parse(m), false) else Resolved(Uri.parse(m), true)
        }
        localTreeFile(chapter)?.let { return Resolved(it, true) }
        return Resolved(Uri.parse(remoteUrl(chapter)), false)
    }

    fun isDownloaded(chapter: Chapter): Boolean = localFile(chapter).let { it.exists() && it.length() > 0 }
}
