package org.murabbie.ahlalhadeeth.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File

data class Favorite(val contentId: Long, val code: Int, val seq: Int, val sheekhId: Int, val bookId: Int, val addedAt: Long)

data class HistoryEntry(val code: Int, val positionMs: Long, val durationMs: Long, val playedAt: Long)

data class DownloadEntry(
    val code: Int,
    val title: String,
    val url: String,
    val dest: String,
    val bytes: Long,
    val total: Long,
    val state: Int, // 0 قيد الانتظار، 1 جارٍ، 2 متوقف، 3 مكتمل، 4 خطأ
    val error: String,
    val addedAt: Long,
) {
    companion object {
        const val PENDING = 0
        const val RUNNING = 1
        const val PAUSED = 2
        const val DONE = 3
        const val ERROR = 4
    }
}

/** قاعدة بيانات المستخدم: المفضلة، سجل الكلمات، التنزيلات، سجل الاستماع. */
class UserDb(context: Context) {

    private val file = File(context.filesDir, "user.db")
    private val db = Db(file.absolutePath, readOnly = false)
    /** المحتوى المضاف من المستخدم (مشايخ وسلاسل ودروس ومقاطع) */
    val content = UserContent(db)

    private val _favoritesVersion = MutableStateFlow(0)
    val favoritesVersion: StateFlow<Int> = _favoritesVersion
    private val _downloadsVersion = MutableStateFlow(0)
    val downloadsVersion: StateFlow<Int> = _downloadsVersion

    init {
        runBlocking {
            db.exec("CREATE TABLE IF NOT EXISTS favorite(content_id INTEGER PRIMARY KEY, code INTEGER NOT NULL, seq INTEGER NOT NULL, sheekh_id INTEGER NOT NULL, book_id INTEGER NOT NULL, added_at INTEGER NOT NULL)")
            db.exec("CREATE TABLE IF NOT EXISTS word(text TEXT PRIMARY KEY, used_at INTEGER NOT NULL, count INTEGER NOT NULL DEFAULT 1)")
            db.exec("CREATE TABLE IF NOT EXISTS history(code INTEGER PRIMARY KEY, position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL DEFAULT 0, played_at INTEGER NOT NULL)")
            db.exec("CREATE TABLE IF NOT EXISTS download(code INTEGER PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL, dest TEXT NOT NULL, bytes INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0, state INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', added_at INTEGER NOT NULL)")
            db.exec("CREATE TABLE IF NOT EXISTS note(content_id INTEGER PRIMARY KEY, text TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            content.init()
        }
    }

    // ---------- المفضلة ----------
    suspend fun favorites(): List<Favorite> =
        db.query("SELECT content_id, code, seq, sheekh_id, book_id, added_at FROM favorite ORDER BY sheekh_id, book_id, code, seq") {
            Favorite(it.long(0), it.int(1), it.int(2), it.int(3), it.int(4), it.long(5))
        }

    suspend fun favoriteIds(): Set<Long> = db.query("SELECT content_id FROM favorite") { it.long(0) }.toSet()

    suspend fun isFavorite(contentId: Long): Boolean = db.count("SELECT COUNT(*) FROM favorite WHERE content_id = ?", contentId) > 0

    suspend fun addFavorite(seg: Segment) {
        db.exec("INSERT OR REPLACE INTO favorite VALUES (?,?,?,?,?,?)", seg.id, seg.code, seg.seq, seg.sheekhId, seg.bookId, System.currentTimeMillis())
        _favoritesVersion.value++
    }

    suspend fun removeFavorite(contentId: Long) {
        db.exec("DELETE FROM favorite WHERE content_id = ?", contentId)
        _favoritesVersion.value++
    }

    suspend fun clearFavorites() {
        db.exec("DELETE FROM favorite")
        _favoritesVersion.value++
    }

    // ---------- الملاحظات ----------
    suspend fun note(contentId: Long): String = db.queryOne("SELECT text FROM note WHERE content_id = ?", contentId) { it.text(0) } ?: ""
    suspend fun saveNote(contentId: Long, text: String) {
        if (text.isBlank()) db.exec("DELETE FROM note WHERE content_id = ?", contentId)
        else db.exec("INSERT OR REPLACE INTO note VALUES (?,?,?)", contentId, text, System.currentTimeMillis())
    }

    // ---------- سجل الكلمات ----------
    suspend fun words(prefix: String = "", limit: Int = 30): List<String> =
        if (prefix.isBlank()) db.query("SELECT text FROM word ORDER BY used_at DESC LIMIT ?", limit) { it.text(0) }
        else db.query("SELECT text FROM word WHERE text LIKE ? ORDER BY used_at DESC LIMIT ?", "%$prefix%", limit) { it.text(0) }

    suspend fun addWord(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        db.exec("INSERT INTO word(text, used_at, count) VALUES (?,?,1) ON CONFLICT(text) DO UPDATE SET used_at = excluded.used_at, count = count + 1", t, System.currentTimeMillis())
    }

    suspend fun removeWord(text: String) = db.exec("DELETE FROM word WHERE text = ?", text)
    suspend fun clearWords() = db.exec("DELETE FROM word")

    // ---------- سجل الاستماع ----------
    suspend fun savePosition(code: Int, positionMs: Long, durationMs: Long) {
        db.exec("INSERT OR REPLACE INTO history VALUES (?,?,?,?)", code, positionMs, durationMs, System.currentTimeMillis())
    }

    suspend fun position(code: Int): Long = db.queryOne("SELECT position_ms FROM history WHERE code = ?", code) { it.long(0) } ?: 0L

    suspend fun history(limit: Int = 100): List<HistoryEntry> =
        db.query("SELECT code, position_ms, duration_ms, played_at FROM history ORDER BY played_at DESC LIMIT ?", limit) {
            HistoryEntry(it.int(0), it.long(1), it.long(2), it.long(3))
        }

    suspend fun clearHistory() = db.exec("DELETE FROM history")

    // ---------- التنزيلات ----------
    private fun downloadOf(r: Db.Row) = DownloadEntry(r.int(0), r.text(1), r.text(2), r.text(3), r.long(4), r.long(5), r.int(6), r.text(7), r.long(8))
    private val dlCols = "code, title, url, dest, bytes, total, state, error, added_at"

    suspend fun downloads(): List<DownloadEntry> =
        db.query("SELECT $dlCols FROM download ORDER BY state = 3, added_at") { downloadOf(it) }

    suspend fun download(code: Int): DownloadEntry? = db.queryOne("SELECT $dlCols FROM download WHERE code = ?", code) { downloadOf(it) }

    suspend fun downloadedCodes(): Set<Int> = db.query("SELECT code FROM download WHERE state = 3") { it.int(0) }.toSet()

    suspend fun addDownload(code: Int, title: String, url: String, dest: String) {
        db.exec(
            "INSERT INTO download(code, title, url, dest, bytes, total, state, error, added_at) VALUES (?,?,?,?,0,0,0,'',?) ON CONFLICT(code) DO UPDATE SET url = excluded.url, dest = excluded.dest, state = CASE WHEN download.state = 3 THEN 3 ELSE 0 END, error = ''",
            code, title, url, dest, System.currentTimeMillis()
        )
        _downloadsVersion.value++
    }

    suspend fun updateDownload(code: Int, bytes: Long, total: Long, state: Int, error: String = "") {
        db.exec("UPDATE download SET bytes = ?, total = ?, state = ?, error = ? WHERE code = ?", bytes, total, state, error, code)
        _downloadsVersion.value++
    }

    suspend fun setDownloadState(code: Int, state: Int, error: String = "") {
        db.exec("UPDATE download SET state = ?, error = ? WHERE code = ?", state, error, code)
        _downloadsVersion.value++
    }

    suspend fun setAllDownloadsState(from: Int, to: Int) {
        db.exec("UPDATE download SET state = ? WHERE state = ?", to, from)
        _downloadsVersion.value++
    }

    suspend fun removeDownload(code: Int) {
        db.exec("DELETE FROM download WHERE code = ?", code)
        _downloadsVersion.value++
    }

    suspend fun nextPendingDownload(): DownloadEntry? =
        db.queryOne("SELECT $dlCols FROM download WHERE state = 0 ORDER BY added_at LIMIT 1") { downloadOf(it) }

    fun bumpDownloads() { _downloadsVersion.value++ }
}
