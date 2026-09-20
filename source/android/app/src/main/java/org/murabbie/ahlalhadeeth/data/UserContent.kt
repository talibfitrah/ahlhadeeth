package org.murabbie.ahlalhadeeth.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * المحتوى المضاف من المستخدم: مشايخ وسلاسل ودروس (صوتية أو مرئية) مع فهرس مواضيع بالأزمنة وتفريغات،
 * يُخزَّن في user.db ويُدمج مع البيانات الأصلية بمعرّفات سالبة (شيخ -١، درس -٧، مقطع -٣٠…).
 */
class UserContent(private val db: Db) {

    private val _version = MutableStateFlow(0)
    /** يزداد مع كل تعديل ليُعاد تحميل الشاشات */
    val version: StateFlow<Int> = _version
    private fun bump() { _version.value++ }

    suspend fun init() {
        db.exec("CREATE TABLE IF NOT EXISTS u_sheekh(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ord INTEGER NOT NULL DEFAULT 0, key TEXT NOT NULL DEFAULT '')")
        db.exec("CREATE TABLE IF NOT EXISTS u_book(id INTEGER PRIMARY KEY AUTOINCREMENT, sheekh_id INTEGER NOT NULL, name TEXT NOT NULL, type_name TEXT NOT NULL DEFAULT '', ord INTEGER NOT NULL DEFAULT 0, key TEXT NOT NULL DEFAULT '')")
        db.exec("CREATE TABLE IF NOT EXISTS u_chapter(id INTEGER PRIMARY KEY AUTOINCREMENT, sheekh_id INTEGER NOT NULL, book_id INTEGER NOT NULL, title TEXT NOT NULL, media_uri TEXT NOT NULL DEFAULT '', is_video INTEGER NOT NULL DEFAULT 0, file_size INTEGER NOT NULL DEFAULT 0, duration INTEGER NOT NULL DEFAULT 0, ord INTEGER NOT NULL DEFAULT 0, notes TEXT NOT NULL DEFAULT '', key TEXT NOT NULL DEFAULT '')")
        db.exec("CREATE TABLE IF NOT EXISTS u_content(id INTEGER PRIMARY KEY AUTOINCREMENT, chapter_id INTEGER NOT NULL, seq INTEGER NOT NULL DEFAULT 0, line TEXT NOT NULL, line_n TEXT NOT NULL DEFAULT '', offset_start INTEGER NOT NULL DEFAULT 0, offset_end INTEGER NOT NULL DEFAULT 0, write TEXT, write_n TEXT NOT NULL DEFAULT '', ques INTEGER NOT NULL DEFAULT 0, hnum INTEGER NOT NULL DEFAULT 0)")
        db.exec("CREATE INDEX IF NOT EXISTS u_content_ch ON u_content(chapter_id, offset_start)")
        db.exec("CREATE TABLE IF NOT EXISTS u_content_cat(content_id INTEGER NOT NULL, category_id INTEGER NOT NULL, PRIMARY KEY(category_id, content_id))")
        db.exec("CREATE INDEX IF NOT EXISTS u_content_cat_c ON u_content_cat(content_id)")
        db.exec("CREATE VIRTUAL TABLE IF NOT EXISTS u_content_fts USING fts5(line_n, write_n, content='u_content', content_rowid='id', tokenize='unicode61')")
        db.exec("CREATE TRIGGER IF NOT EXISTS u_content_ai AFTER INSERT ON u_content BEGIN INSERT INTO u_content_fts(rowid, line_n, write_n) VALUES (new.id, new.line_n, new.write_n); END")
        db.exec("CREATE TRIGGER IF NOT EXISTS u_content_ad AFTER DELETE ON u_content BEGIN INSERT INTO u_content_fts(u_content_fts, rowid, line_n, write_n) VALUES('delete', old.id, old.line_n, old.write_n); END")
        db.exec("CREATE TRIGGER IF NOT EXISTS u_content_au AFTER UPDATE ON u_content BEGIN INSERT INTO u_content_fts(u_content_fts, rowid, line_n, write_n) VALUES('delete', old.id, old.line_n, old.write_n); INSERT INTO u_content_fts(rowid, line_n, write_n) VALUES (new.id, new.line_n, new.write_n); END")
        db.exec("CREATE TABLE IF NOT EXISTS u_pack(id TEXT PRIMARY KEY, name TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0, installed_at INTEGER NOT NULL)")
        // ترقية: أصل المحتوى (local/shared) وعلامة التعديل غير المنشور، وشواهد الحذف
        for (sql in listOf(
            "ALTER TABLE u_sheekh ADD COLUMN origin TEXT NOT NULL DEFAULT 'local'",
            "ALTER TABLE u_book ADD COLUMN origin TEXT NOT NULL DEFAULT 'local'",
            "ALTER TABLE u_chapter ADD COLUMN origin TEXT NOT NULL DEFAULT 'local'",
            "ALTER TABLE u_chapter ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE u_chapter ADD COLUMN source_url TEXT NOT NULL DEFAULT ''",
        )) runCatching { db.exec(sql) }
        db.exec("CREATE TABLE IF NOT EXISTS u_removed(key TEXT PRIMARY KEY, removed_at INTEGER NOT NULL)")
        // مفاتيح ثابتة لما أُنشئ قبل الترقية
        for (t in listOf("u_sheekh", "u_book", "u_chapter")) {
            val ids = db.query("SELECT id FROM $t WHERE key = ''") { it.int(0) }
            for (id in ids) db.exec("UPDATE $t SET key = ? WHERE id = ?", newKey(t.substring(2, 3)), id)
        }
    }

    /** مفتاح ثابت جديد (يُستخدم للدمج بين الأجهزة) */
    fun newKey(prefix: String): String {
        val r = java.security.SecureRandom()
        val b = ByteArray(6); r.nextBytes(b)
        return prefix + "-" + b.joinToString("") { "%02x".format(it) }
    }

    suspend fun pendingCounts(): Pair<Int, Int> = db.count("SELECT COUNT(*) FROM u_chapter WHERE dirty <> 0").toInt() to db.count("SELECT COUNT(*) FROM u_removed").toInt()

    /** قبل التصدير للنشر: المعلَّق الآن يُعلَّم 2 «قيد النشر»، وأي تعديل أثناء الرفع يعيده 1 (markDirty) فلا يمسحه markPublished. يعيد مفاتيح الحذف المرسَلة. */
    suspend fun markPublishing(): List<String> {
        db.exec("UPDATE u_chapter SET dirty = 2 WHERE dirty <> 0")
        return removedKeys()
    }

    /** بعد نجاح الرفع: يُنظَّف ما أُرسل فقط؛ إن فشل الرفع بقيت 2 معلَّقة (dirty <> 0) فلا يضيع شيء */
    suspend fun markPublished(removedSent: List<String>) {
        db.exec("UPDATE u_chapter SET dirty = 0 WHERE dirty = 2")
        for (k in removedSent) db.exec("DELETE FROM u_removed WHERE key = ?", k)
        bump()
    }

    /** بعد النشر يصبح كل المحتوى المحلي «مشتركًا» */
    suspend fun markAllShared() {
        db.exec("UPDATE u_sheekh SET origin = 'shared'")
        db.exec("UPDATE u_book SET origin = 'shared'")
        db.exec("UPDATE u_chapter SET origin = 'shared'")
    }

    suspend fun removedKeys(): List<String> = db.query("SELECT key FROM u_removed ORDER BY removed_at") { it.text(0) }

    private suspend fun markDirty(uchapter: Int) { db.exec("UPDATE u_chapter SET dirty = 1 WHERE id = ?", uchapter) }

    // ---------- تحويل الصفوف إلى النماذج (معرّفات سالبة) ----------

    private fun sheekhOf(r: Db.Row) = Sheekh(-r.int(0), r.text(1), true, 10000 + r.int(2))
    private fun bookOf(r: Db.Row) = Book(-r.int(0), r.text(1), r.int(2), 0, r.text(3))

    private val chapterCols = """c.id, c.sheekh_id, c.book_id, c.title, c.media_uri, c.is_video, c.file_size, c.duration, c.ord, c.notes,
        IFNULL(s.name,''), IFNULL(b.name,''),
        (SELECT COUNT(*) FROM u_content x WHERE x.chapter_id = c.id), (SELECT COUNT(*) FROM u_content x WHERE x.chapter_id = c.id AND x.write IS NOT NULL),
        c.origin, c.dirty, c.key, c.source_url"""
    private val chapterFrom = "FROM u_chapter c LEFT JOIN u_sheekh s ON s.id = c.sheekh_id LEFT JOIN u_book b ON b.id = c.book_id"

    private fun chapterOf(r: Db.Row) = Chapter(
        code = -r.int(0), sheekhId = -r.int(1), bookId = -r.int(2), title = r.text(3), fileName = "u" + r.int(0), fileSize = r.long(6),
        path = "user", cdNumber = "", ord = r.int(8), segCount = r.int(12), writeCount = r.int(13), sheekhName = r.text(10), bookName = r.text(11),
        mediaUri = r.text(4), isVideo = r.bool(5), notes = r.text(9), origin = r.text(14), dirty = r.bool(15), key = r.text(16), sourceUrl = r.text(17),
    )

    private val segCols = "c.id, c.chapter_id, c.seq, ch.sheekh_id, ch.book_id, c.hnum, c.line, c.offset_start, c.offset_end, (c.write IS NOT NULL), c.ques"
    private val segFrom = "FROM u_content c JOIN u_chapter ch ON ch.id = c.chapter_id"
    private fun segmentOf(r: Db.Row) = Segment(
        id = -r.long(0), code = -r.int(1), seq = r.int(2), sheekhId = -r.int(3), bookId = -r.int(4), hnum = r.int(5), line = r.text(6),
        offsetStart = r.long(7), offsetEnd = r.long(8), hasWrite = r.bool(9), ques = r.bool(10),
    )

    // ---------- قراءة ----------

    suspend fun sheekhs(): List<Sheekh> = db.query("SELECT id, name, ord FROM u_sheekh ORDER BY ord, id") { sheekhOf(it) }
    suspend fun sheekh(uid: Int): Sheekh? = db.queryOne("SELECT id, name, ord FROM u_sheekh WHERE id = ?", uid) { sheekhOf(it) }
    suspend fun count(): Int = db.count("SELECT COUNT(*) FROM u_chapter").toInt()

    suspend fun booksOfSheekh(uid: Int): List<SheekhBook> =
        db.query(
            """SELECT b.id, b.name, b.ord, b.type_name, (SELECT COUNT(*) FROM u_chapter c WHERE c.book_id = b.id),
                      (SELECT COUNT(*) FROM u_content x JOIN u_chapter c ON c.id = x.chapter_id WHERE c.book_id = b.id)
               FROM u_book b WHERE b.sheekh_id = ? ORDER BY b.ord, b.id""", uid
        ) { SheekhBook(bookOf(it), -uid, it.int(4), it.int(5)) }

    suspend fun bookKey(ubook: Int): String = db.queryOne("SELECT key FROM u_book WHERE id = ?", ubook) { it.text(0) } ?: ""
    suspend fun book(ubook: Int): Book? = db.queryOne("SELECT id, name, ord, type_name FROM u_book WHERE id = ?", ubook) { bookOf(it) }

    suspend fun chapters(usheekh: Int, ubook: Int): List<Chapter> =
        db.query("SELECT $chapterCols $chapterFrom WHERE c.sheekh_id = ? AND c.book_id = ? ORDER BY c.ord, c.id", usheekh, ubook) { chapterOf(it) }

    suspend fun chapter(uid: Int): Chapter? = db.queryOne("SELECT $chapterCols $chapterFrom WHERE c.id = ?", uid) { chapterOf(it) }

    suspend fun chaptersByIds(uids: Collection<Int>): List<Chapter> {
        if (uids.isEmpty()) return emptyList()
        return db.query("SELECT $chapterCols $chapterFrom WHERE c.id IN (${uids.joinToString(",")})") { chapterOf(it) }
    }

    suspend fun searchChapters(text: String, limit: Int): List<Chapter> =
        db.query("SELECT $chapterCols $chapterFrom WHERE c.title LIKE ? ORDER BY c.id LIMIT ?", "%${text.trim()}%", limit) { chapterOf(it) }

    suspend fun segments(uchapter: Int): List<Segment> =
        db.query("SELECT $segCols $segFrom WHERE c.chapter_id = ? ORDER BY c.offset_start, c.seq, c.id", uchapter) { segmentOf(it) }

    suspend fun segment(uid: Long): Segment? = db.queryOne("SELECT $segCols $segFrom WHERE c.id = ?", uid) { segmentOf(it) }

    suspend fun segmentsByIds(uids: Collection<Long>): List<Segment> {
        if (uids.isEmpty()) return emptyList()
        return db.query("SELECT $segCols $segFrom WHERE c.id IN (${uids.joinToString(",")})") { segmentOf(it) }
    }

    suspend fun write(uid: Long): String? = db.queryOne("SELECT write FROM u_content WHERE id = ?", uid) { it.textOrNull(0) }

    suspend fun writesOfChapter(uchapter: Int): Map<Long, String> =
        db.query("SELECT id, write FROM u_content WHERE chapter_id = ? AND write IS NOT NULL", uchapter) { -it.long(0) to it.text(1) }.toMap()

    suspend fun categoryIdsOfSegment(uid: Long): List<Int> = db.query("SELECT category_id FROM u_content_cat WHERE content_id = ? ORDER BY category_id", uid) { it.int(0) }

    /** مقاطع المحتوى المضاف المرتبطة بتصانيف معيّنة */
    suspend fun segmentsOfCategories(catIds: List<Int>, usheekh: Int?, limit: Int, offset: Int): List<Segment> {
        if (catIds.isEmpty()) return emptyList()
        val f = if (usheekh != null && usheekh > 0) "AND ch.sheekh_id = $usheekh" else ""
        return db.query(
            "SELECT DISTINCT $segCols $segFrom JOIN u_content_cat cc ON cc.content_id = c.id WHERE cc.category_id IN (${catIds.joinToString(",")}) $f ORDER BY c.chapter_id, c.offset_start LIMIT ? OFFSET ?",
            limit, offset
        ) { segmentOf(it) }
    }

    suspend fun countSegmentsOfCategories(catIds: List<Int>, usheekh: Int?): Long {
        if (catIds.isEmpty()) return 0
        val f = if (usheekh != null && usheekh > 0) "AND ch.sheekh_id = $usheekh" else ""
        return db.count("SELECT COUNT(DISTINCT c.id) $segFrom JOIN u_content_cat cc ON cc.content_id = c.id WHERE cc.category_id IN (${catIds.joinToString(",")}) $f")
    }

    /** بحث FTS في المحتوى المضاف؛ يعيد المقاطع مع نص التفريغ للمقتطف */
    suspend fun search(opts: SearchOptions): List<Pair<Segment, String?>> {
        val words = ArabicText.queryWords(opts.query)
        if (words.isEmpty()) return emptyList()
        val column = when {
            opts.inLine && !opts.inWrite -> "line_n"
            !opts.inLine && opts.inWrite -> "write_n"
            else -> null
        }
        val fts = ArabicText.buildFtsQuery(words, opts.matchAll, column, opts.exactPhrase) ?: return emptyList()
        val where = StringBuilder("u_content_fts MATCH ?")
        // تصفية بالشيخ: معرّفات المستخدم سالبة في الخيارات
        val us = opts.sheekhIds.filter { it < 0 }.map { -it }
        val ub = opts.bookIds.filter { it < 0 }.map { -it }
        if (opts.sheekhIds.isNotEmpty() && us.isEmpty()) return emptyList()
        if (opts.bookIds.isNotEmpty() && ub.isEmpty()) return emptyList()
        if (us.isNotEmpty()) where.append(" AND ch.sheekh_id IN (${us.joinToString(",")})")
        if (ub.isNotEmpty()) where.append(" AND ch.book_id IN (${ub.joinToString(",")})")
        if (opts.questionsOnly) where.append(" AND c.ques <> 0")
        return db.query(
            "SELECT $segCols, c.write FROM u_content_fts f JOIN u_content c ON c.id = f.rowid JOIN u_chapter ch ON ch.id = c.chapter_id WHERE $where ORDER BY bm25(u_content_fts, 4.0, 1.0) LIMIT ? OFFSET ?",
            fts, opts.limit, opts.offset
        ) { segmentOf(it) to it.textOrNull(11) }
    }

    suspend fun searchCount(opts: SearchOptions): Long {
        val words = ArabicText.queryWords(opts.query)
        if (words.isEmpty()) return 0
        val column = when {
            opts.inLine && !opts.inWrite -> "line_n"
            !opts.inLine && opts.inWrite -> "write_n"
            else -> null
        }
        val fts = ArabicText.buildFtsQuery(words, opts.matchAll, column, opts.exactPhrase) ?: return 0
        val where = StringBuilder("u_content_fts MATCH ?")
        val us = opts.sheekhIds.filter { it < 0 }.map { -it }
        val ub = opts.bookIds.filter { it < 0 }.map { -it }
        if (opts.sheekhIds.isNotEmpty() && us.isEmpty()) return 0
        if (opts.bookIds.isNotEmpty() && ub.isEmpty()) return 0
        if (us.isNotEmpty()) where.append(" AND ch.sheekh_id IN (${us.joinToString(",")})")
        if (ub.isNotEmpty()) where.append(" AND ch.book_id IN (${ub.joinToString(",")})")
        if (opts.questionsOnly) where.append(" AND c.ques <> 0")
        return db.count("SELECT COUNT(*) FROM u_content_fts f JOIN u_content c ON c.id = f.rowid JOIN u_chapter ch ON ch.id = c.chapter_id WHERE $where", fts)
    }

    suspend fun stats(): Triple<Int, Int, Int> = Triple(
        db.count("SELECT COUNT(*) FROM u_sheekh").toInt(), db.count("SELECT COUNT(*) FROM u_chapter").toInt(), db.count("SELECT COUNT(*) FROM u_content").toInt()
    )

    // ---------- كتابة ----------

    suspend fun addSheekh(name: String, key: String = "", origin: String = "local"): Int {
        val ord = db.count("SELECT IFNULL(MAX(ord),0)+1 FROM u_sheekh").toInt()
        db.exec("INSERT INTO u_sheekh(name, ord, key, origin) VALUES (?,?,?,?)", name.trim(), ord, key.ifBlank { newKey("s") }, origin)
        val id = db.count("SELECT last_insert_rowid()").toInt()
        bump(); return id
    }

    suspend fun updateSheekh(uid: Int, name: String) { db.exec("UPDATE u_sheekh SET name = ? WHERE id = ?", name.trim(), uid); bump() }

    suspend fun deleteSheekh(uid: Int) {
        tombstone(db.query("SELECT key FROM u_chapter WHERE sheekh_id = ?", uid) { it.text(0) })
        db.exec("DELETE FROM u_content_cat WHERE content_id IN (SELECT c.id FROM u_content c JOIN u_chapter ch ON ch.id = c.chapter_id WHERE ch.sheekh_id = ?)", uid)
        db.exec("DELETE FROM u_content WHERE chapter_id IN (SELECT id FROM u_chapter WHERE sheekh_id = ?)", uid)
        db.exec("DELETE FROM u_chapter WHERE sheekh_id = ?", uid)
        db.exec("DELETE FROM u_book WHERE sheekh_id = ?", uid)
        db.exec("DELETE FROM u_sheekh WHERE id = ?", uid)
        bump()
    }

    suspend fun addBook(usheekh: Int, name: String, typeName: String = "", key: String = "", origin: String = "local"): Int {
        val ord = db.count("SELECT IFNULL(MAX(ord),0)+1 FROM u_book WHERE sheekh_id = ?", usheekh).toInt()
        db.exec("INSERT INTO u_book(sheekh_id, name, type_name, ord, key, origin) VALUES (?,?,?,?,?,?)", usheekh, name.trim(), typeName.trim(), ord, key.ifBlank { newKey("b") }, origin)
        val id = db.count("SELECT last_insert_rowid()").toInt()
        bump(); return id
    }

    suspend fun updateBook(ubook: Int, name: String, typeName: String) { db.exec("UPDATE u_book SET name = ?, type_name = ? WHERE id = ?", name.trim(), typeName.trim(), ubook); bump() }

    suspend fun deleteBook(ubook: Int) {
        tombstone(db.query("SELECT key FROM u_chapter WHERE book_id = ?", ubook) { it.text(0) })
        db.exec("DELETE FROM u_content_cat WHERE content_id IN (SELECT c.id FROM u_content c JOIN u_chapter ch ON ch.id = c.chapter_id WHERE ch.book_id = ?)", ubook)
        db.exec("DELETE FROM u_content WHERE chapter_id IN (SELECT id FROM u_chapter WHERE book_id = ?)", ubook)
        db.exec("DELETE FROM u_chapter WHERE book_id = ?", ubook)
        db.exec("DELETE FROM u_book WHERE id = ?", ubook)
        bump()
    }

    suspend fun addChapter(usheekh: Int, ubook: Int, title: String, mediaUri: String, isVideo: Boolean, notes: String = "", key: String = "", fileSize: Long = 0, origin: String = "local", dirty: Boolean = true, sourceUrl: String = ""): Int {
        val ord = db.count("SELECT IFNULL(MAX(ord),0)+1 FROM u_chapter WHERE book_id = ?", ubook).toInt()
        db.exec("INSERT INTO u_chapter(sheekh_id, book_id, title, media_uri, is_video, file_size, ord, notes, key, origin, dirty, source_url) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", usheekh, ubook, title.trim(), mediaUri.trim(), isVideo, fileSize, ord, notes, key.ifBlank { newKey("t") }, origin, dirty, sourceUrl.trim())
        val id = db.count("SELECT last_insert_rowid()").toInt()
        bump(); return id
    }

    suspend fun updateChapter(uid: Int, title: String, mediaUri: String, isVideo: Boolean, notes: String, dirty: Boolean = true, sourceUrl: String? = null) {
        db.exec("UPDATE u_chapter SET title = ?, media_uri = ?, is_video = ?, notes = ?, dirty = CASE WHEN ? THEN 1 ELSE dirty END WHERE id = ?", title.trim(), mediaUri.trim(), isVideo, notes, dirty, uid)
        if (sourceUrl != null) db.exec("UPDATE u_chapter SET source_url = ? WHERE id = ?", sourceUrl.trim(), uid)
        bump()
    }

    suspend fun setChapterSize(uid: Int, size: Long) { db.exec("UPDATE u_chapter SET file_size = ? WHERE id = ?", size, uid); bump() }

    private suspend fun tombstone(keys: List<String>) {
        for (k in keys) if (k.isNotBlank()) db.exec("INSERT OR REPLACE INTO u_removed(key, removed_at) VALUES (?,?)", k, System.currentTimeMillis())
    }

    suspend fun deleteChapter(uid: Int, tombstone: Boolean = true) {
        if (tombstone) tombstone(db.query("SELECT key FROM u_chapter WHERE id = ?", uid) { it.text(0) })
        db.exec("DELETE FROM u_content_cat WHERE content_id IN (SELECT id FROM u_content WHERE chapter_id = ?)", uid)
        db.exec("DELETE FROM u_content WHERE chapter_id = ?", uid)
        db.exec("DELETE FROM u_chapter WHERE id = ?", uid)
        bump()
    }

    suspend fun addSegment(uchapter: Int, line: String, offsetStart: Long, write: String?, ques: Boolean, hnum: Int, categories: List<Int> = emptyList()): Long {
        val w = write?.trim()?.ifBlank { null }
        db.exec(
            "INSERT INTO u_content(chapter_id, seq, line, line_n, offset_start, offset_end, write, write_n, ques, hnum) VALUES (?,?,?,?,?,?,?,?,?,?)",
            uchapter, 0, line.trim(), ArabicText.normalize(line), offsetStart, 0, w, ArabicText.normalize(w ?: ""), ques, hnum
        )
        val id = db.count("SELECT last_insert_rowid()")
        if (categories.isNotEmpty()) setSegmentCategories(id, categories, bumpVersion = false)
        renumber(uchapter)
        markDirty(uchapter)
        bump(); return id
    }

    suspend fun updateSegment(uid: Long, line: String, offsetStart: Long, write: String?, ques: Boolean, hnum: Int) {
        val w = write?.trim()?.ifBlank { null }
        db.exec(
            "UPDATE u_content SET line = ?, line_n = ?, offset_start = ?, write = ?, write_n = ?, ques = ?, hnum = ? WHERE id = ?",
            line.trim(), ArabicText.normalize(line), offsetStart, w, ArabicText.normalize(w ?: ""), ques, hnum, uid
        )
        val ch = db.queryOne("SELECT chapter_id FROM u_content WHERE id = ?", uid) { it.int(0) }
        if (ch != null) { renumber(ch); markDirty(ch) }
        bump()
    }

    suspend fun updateWrite(uid: Long, write: String?) {
        val w = write?.trim()?.ifBlank { null }
        db.exec("UPDATE u_content SET write = ?, write_n = ? WHERE id = ?", w, ArabicText.normalize(w ?: ""), uid)
        db.queryOne("SELECT chapter_id FROM u_content WHERE id = ?", uid) { it.int(0) }?.let { markDirty(it) }
        bump()
    }

    /** حذف كل مواضع درس (قبل استبدال فهرسه) */
    suspend fun deleteSegmentsOfChapter(uchapter: Int) {
        db.exec("DELETE FROM u_content_cat WHERE content_id IN (SELECT id FROM u_content WHERE chapter_id = ?)", uchapter)
        db.exec("DELETE FROM u_content WHERE chapter_id = ?", uchapter)
        markDirty(uchapter)
        bump()
    }

    suspend fun deleteSegment(uid: Long) {
        val ch = db.queryOne("SELECT chapter_id FROM u_content WHERE id = ?", uid) { it.int(0) }
        db.exec("DELETE FROM u_content_cat WHERE content_id = ?", uid)
        db.exec("DELETE FROM u_content WHERE id = ?", uid)
        if (ch != null) { renumber(ch); markDirty(ch) }
        bump()
    }

    suspend fun setSegmentCategories(uid: Long, categories: List<Int>, bumpVersion: Boolean = true) {
        db.exec("DELETE FROM u_content_cat WHERE content_id = ?", uid)
        for (c in categories.distinct()) db.exec("INSERT OR IGNORE INTO u_content_cat(content_id, category_id) VALUES (?,?)", uid, c)
        db.queryOne("SELECT chapter_id FROM u_content WHERE id = ?", uid) { it.int(0) }?.let { markDirty(it) }
        if (bumpVersion) bump()
    }

    /** إعادة ترقيم المقاطع بحسب زمن البداية */
    private suspend fun renumber(uchapter: Int) {
        val ids = db.query("SELECT id FROM u_content WHERE chapter_id = ? ORDER BY offset_start, id", uchapter) { it.long(0) }
        ids.forEachIndexed { i, id -> db.exec("UPDATE u_content SET seq = ? WHERE id = ?", i + 1, id) }
        // offset_end = بداية المقطع التالي
        db.exec("UPDATE u_content SET offset_end = IFNULL((SELECT MIN(n.offset_start) FROM u_content n WHERE n.chapter_id = u_content.chapter_id AND n.offset_start > u_content.offset_start), 0) WHERE chapter_id = ?", uchapter)
    }

    // ---------- تحليل الأزمنة والفهارس النصية ----------

    companion object {
        /** يقبل: ١:٠٢:٠٥ أو 1:02:05 أو 02:05 أو 125 (ثوانٍ) أو 125.5 */
        fun parseTime(text: String): Long? {
            val t = text.trim().map { c -> if (c in '٠'..'٩') ('0' + (c - '٠')) else if (c in '۰'..'۹') ('0' + (c - '۰')) else c }.joinToString("").replace('٫', '.').replace('،', ',')
            if (t.isEmpty()) return null
            if (':' in t) {
                val parts = t.split(':').map { it.trim() }
                if (parts.any { it.isEmpty() } || parts.size > 3) return null
                var total = 0.0
                for (p in parts) {
                    val v = p.toDoubleOrNull() ?: return null
                    total = total * 60 + v
                }
                return (total * 1000).toLong()
            }
            val v = t.toDoubleOrNull() ?: return null
            return (v * 1000).toLong()
        }

        data class ParsedSegment(val start: Long, val line: String, val write: String?, val ques: Boolean, val hnum: Int)

        private val timeLine = Regex("^\\s*\\[?([0-9٠-٩]{1,2}(?::[0-9٠-٩]{1,2}){1,2}(?:[.٫][0-9٠-٩]+)?)\\]?\\s*[-–—:|]?\\s*(.*)$")

        /**
         * فهرس نصي ملصوق: كل سطر يبدأ بزمن (س:دد:ثث أو دد:ثث) ثم عنوان الموضوع، وما يليه من أسطر حتى الزمن التالي تفريغٌ لذلك المقطع.
         * علامة «؟» في آخر العنوان تُعدّ سؤالًا، و«ح ١٢» أو «حديث ١٢» في أوله رقم حديث.
         */
        fun parseIndexText(text: String): List<ParsedSegment> {
            val out = ArrayList<ParsedSegment>()
            var cur: Triple<Long, String, StringBuilder>? = null
            fun flush() {
                val c = cur ?: return
                val title = c.second.trim()
                val hn = Regex("^(?:ح|حديث)\\s*([0-9٠-٩]+)").find(title)?.groupValues?.get(1)?.let { ArabicText.normalize(it).toIntOrNull() } ?: 0
                out.add(ParsedSegment(c.first, title, c.third.toString().trim().ifBlank { null }, title.endsWith("؟") || title.endsWith("?"), hn))
            }
            for (raw in text.lines()) {
                val m = timeLine.find(raw)
                val start = m?.let { parseTime(it.groupValues[1]) }
                if (m != null && start != null) {
                    flush()
                    cur = Triple(start, m.groupValues[2], StringBuilder())
                } else {
                    val c = cur
                    if (c != null) {
                        if (c.third.isNotEmpty()) c.third.append('\n')
                        c.third.append(raw.trimEnd())
                    }
                }
            }
            flush()
            return out.sortedBy { it.start }
        }
    }

    suspend fun addSegments(uchapter: Int, parsed: List<ParsedSegment>): Int {
        for (p in parsed) {
            val w = p.write
            db.exec(
                "INSERT INTO u_content(chapter_id, seq, line, line_n, offset_start, offset_end, write, write_n, ques, hnum) VALUES (?,?,?,?,?,?,?,?,?,?)",
                uchapter, 0, p.line, ArabicText.normalize(p.line), p.start, 0, w, ArabicText.normalize(w ?: ""), p.ques, p.hnum
            )
        }
        renumber(uchapter)
        markDirty(uchapter)
        bump()
        return parsed.size
    }

    // ---------- حزم JSON: استيراد وتصدير ----------

    data class ImportResult(val sheekhs: Int, val books: Int, val chapters: Int, val segments: Int, val packName: String)

    /**
     * صيغة الحزمة: {"format":"ahl-alhadeeth-pack","version":1,"id":"...","name":"...",
     *   "sheekhs":[{"name":"…","key":"…","series":[{"name":"…","type":"…","key":"…","tapes":[
     *      {"title":"…","key":"…","media_url":"https://…/01.mp3","kind":"audio|video","notes":"…",
     *       "segments":[{"start":"0:00:27","line":"…","write":"…","ques":false,"hnum":0,"categories":[5,943]}]}]}]}]}
     * الدرس ذو المفتاح key نفسه داخل السلسلة نفسها يُستبدل (تحديث).
     */
    suspend fun importPack(json: String, origin: String = "local"): ImportResult {
        val o = JSONObject(json)
        if (o.optString("format") != "ahl-alhadeeth-pack") throw IllegalArgumentException("الملف ليس حزمة محتوى للتطبيق (format غير مطابق)")
        var ns = 0; var nb = 0; var nc = 0; var nseg = 0
        val packName = o.optString("name", "")
        val shared = origin == "shared"
        val localTombstones = if (shared) removedKeys().toSet() else emptySet()
        // ما حُذف على الخادم يُحذف محليًا (بلا شاهد جديد)
        o.optJSONArray("removed")?.let { arr ->
            for (i in 0 until arr.length()) {
                val k = arr.optString(i)
                if (k.isBlank()) continue
                db.query("SELECT id FROM u_chapter WHERE key = ?", k) { it.int(0) }.forEach { deleteChapter(it, tombstone = false) }
                if (shared) db.exec("DELETE FROM u_removed WHERE key = ?", k)
            }
        }
        val sheekhsArr = o.optJSONArray("sheekhs") ?: JSONArray()
        for (i in 0 until sheekhsArr.length()) {
            val so = sheekhsArr.optJSONObject(i) ?: continue // عنصر معطوب يُتخطى ولا يقطع الاستيراد
            val sname = so.optString("name").trim()
            if (sname.isEmpty()) continue
            val skey = so.optString("key", "")
            var sid = if (skey.isNotEmpty()) db.queryOne("SELECT id FROM u_sheekh WHERE key = ?", skey) { it.int(0) } else null
            if (sid == null) sid = db.queryOne("SELECT id FROM u_sheekh WHERE name = ?", sname) { it.int(0) }
            if (sid == null) { sid = addSheekh(sname, skey, origin); ns++ } else if (shared) db.exec("UPDATE u_sheekh SET name = ? WHERE id = ? AND origin = 'shared'", sname, sid)
            val seriesArr = so.optJSONArray("series") ?: JSONArray()
            for (j in 0 until seriesArr.length()) {
                val bo = seriesArr.optJSONObject(j) ?: continue
                val bname = bo.optString("name").trim()
                if (bname.isEmpty()) continue
                val bkey = bo.optString("key", "")
                var bid = if (bkey.isNotEmpty()) db.queryOne("SELECT id FROM u_book WHERE sheekh_id = ? AND key = ?", sid, bkey) { it.int(0) } else null
                if (bid == null) bid = db.queryOne("SELECT id FROM u_book WHERE sheekh_id = ? AND name = ?", sid, bname) { it.int(0) }
                if (bid == null) { bid = addBook(sid, bname, bo.optString("type", ""), bkey, origin); nb++ } else if (bo.has("type")) db.exec("UPDATE u_book SET type_name = ? WHERE id = ?", bo.optString("type", ""), bid)
                val tapes = bo.optJSONArray("tapes") ?: JSONArray()
                for (k in 0 until tapes.length()) {
                    val to = tapes.optJSONObject(k) ?: continue
                    val title = to.optString("title").trim().ifEmpty { "درس ${ArabicText.arabicDigits(k + 1)}" }
                    val key = to.optString("key", "")
                    val url = to.optString("media_url", to.optString("url", "")).trim()
                    val kind = to.optString("kind", "")
                    val isVideo = kind.equals("video", true) || (kind.isEmpty() && Regex("\\.(mp4|m4v|webm|mkv|mov)($|\\?)", RegexOption.IGNORE_CASE).containsMatchIn(url))
                    val notes = to.optString("notes", "")
                    val sourceUrl = to.optString("source_url", "")
                    if (shared && key.isNotEmpty() && key in localTombstones) continue // حُذف محليًا وينتظر النشر
                    // غير http(s) يُفتح لاحقًا مسارًا محليًا (file:/content:) على جهاز المتلقي وقد يُرفع عند التفريغ؛ لا يُقبل من حزمة مشتركة (روابط يوتيوب https فتمرّ)
                    if (shared && url.isNotEmpty() && !url.startsWith("http://", true) && !url.startsWith("https://", true)) continue
                    var cid = if (key.isNotEmpty()) db.queryOne("SELECT id FROM u_chapter WHERE key = ?", key) { it.int(0) } else null
                    if (cid == null && url.isNotEmpty()) cid = db.queryOne("SELECT id FROM u_chapter WHERE book_id = ? AND media_uri = ?", bid, url) { it.int(0) }
                    // درس محلي يُشغَّل من يوتيوب ووصلت نسخته المنقولة إلى الخادم (source_url = رابط يوتيوب نفسه): يُرقّى في مكانه
                    var upgraded = false
                    if (cid == null && sourceUrl.isNotEmpty()) {
                        val srcId = YouTube.extractId(sourceUrl)
                        val rows = db.query("SELECT id, media_uri FROM u_chapter WHERE media_uri LIKE '%youtu%' OR source_url <> ''") { it.int(0) to it.text(1) }
                        cid = rows.firstOrNull { (_, mu) -> srcId != null && YouTube.extractId(mu) == srcId }?.first
                            ?: db.queryOne("SELECT id FROM u_chapter WHERE source_url = ?", sourceUrl) { it.int(0) }
                        upgraded = cid != null
                    }
                    var incomingSegs = to.optJSONArray("segments") ?: JSONArray()
                    // المفضلة والملاحظات تشير إلى معرّف المقطع: المقطع الذي لم يتغير (الزمن والعنوان) يُعاد إدراجه بمعرّفه القديم
                    val oldIds = HashMap<Pair<Long, String>, MutableList<Long>>()
                    if (cid != null) {
                        if (shared && !upgraded) {
                            val localDirty = db.queryOne("SELECT dirty FROM u_chapter WHERE id = ?", cid) { it.bool(0) } ?: false
                            if (localDirty) continue // تعديل محلي معلّق للمشرف يغلب حتى يُنشر
                        }
                        val localSegCount = db.count("SELECT COUNT(*) FROM u_content WHERE chapter_id = ?", cid).toInt()
                        updateChapter(cid, title, url, isVideo, notes, dirty = !shared, sourceUrl = sourceUrl)
                        db.exec("UPDATE u_chapter SET origin = ?, dirty = ?, sheekh_id = ?, book_id = ? WHERE id = ?", if (shared) "shared" else origin, !shared, sid, bid, cid)
                        if (key.isNotEmpty()) db.exec("UPDATE u_chapter SET key = ? WHERE id = ?", key, cid)
                        if (upgraded && incomingSegs.length() == 0 && localSegCount > 0) {
                            incomingSegs = JSONArray() // نُبقي الفهرس المحلي
                        } else {
                            db.query("SELECT id, offset_start, line FROM u_content WHERE chapter_id = ? ORDER BY id", cid) { Triple(it.long(0), it.long(1), it.text(2)) }.forEach { (id, st, ln) -> oldIds.getOrPut(st to ln) { ArrayList() }.add(id) }
                            db.exec("DELETE FROM u_content_cat WHERE content_id IN (SELECT id FROM u_content WHERE chapter_id = ?)", cid)
                            db.exec("DELETE FROM u_content WHERE chapter_id = ?", cid)
                        }
                    } else {
                        cid = addChapter(sid, bid, title, url, isVideo, notes, key, to.optLong("size", 0), origin, dirty = !shared, sourceUrl = sourceUrl); nc++
                    }
                    val segs = incomingSegs
                    for (m in 0 until segs.length()) {
                        val g = segs.optJSONObject(m) ?: continue // وإلا بقي الدرس محذوف المقاطع أو ناقصها
                        val start = if (g.has("start_ms")) g.optLong("start_ms") else parseTime(g.optString("start", "0")) ?: 0L
                        val line = g.optString("line", g.optString("title", "")).trim()
                        if (line.isEmpty()) continue
                        val write = g.optString("write", "").ifBlank { null }
                        val cats = ArrayList<Int>()
                        g.optJSONArray("categories")?.let { arr -> for (x in 0 until arr.length()) cats.add(arr.optInt(x)) }
                        addSegmentQuiet(cid, line, start, write, g.optBoolean("ques", false), g.optInt("hnum", 0), cats, oldIds[start to line]?.removeFirstOrNull())
                        nseg++
                    }
                    renumber(cid)
                }
            }
        }
        val packId = o.optString("id", "")
        if (packId.isNotEmpty()) db.exec("INSERT OR REPLACE INTO u_pack(id, name, version, installed_at) VALUES (?,?,?,?)", packId, packName, o.optInt("pack_version", o.optInt("version", 1)), System.currentTimeMillis())
        bump()
        return ImportResult(ns, nb, nc, nseg, packName)
    }

    /** keepId = معرّف قديم يُحافَظ عليه، أو null لمعرّف جديد تلقائي */
    private suspend fun addSegmentQuiet(uchapter: Int, line: String, offsetStart: Long, write: String?, ques: Boolean, hnum: Int, categories: List<Int>, keepId: Long?) {
        db.exec(
            "INSERT INTO u_content(id, chapter_id, seq, line, line_n, offset_start, offset_end, write, write_n, ques, hnum) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            keepId, uchapter, 0, line, ArabicText.normalize(line), offsetStart, 0, write, ArabicText.normalize(write ?: ""), ques, hnum
        )
        if (categories.isNotEmpty()) {
            val id = db.count("SELECT last_insert_rowid()")
            for (c in categories.distinct()) db.exec("INSERT OR IGNORE INTO u_content_cat(content_id, category_id) VALUES (?,?)", id, c)
        }
    }

    suspend fun installedPacks(): List<Triple<String, String, Int>> = db.query("SELECT id, name, version FROM u_pack ORDER BY installed_at") { Triple(it.text(0), it.text(1), it.int(2)) }

    /** تصدير المحتوى المضاف (كله أو شيخ واحد) حزمةً JSON */
    suspend fun exportPack(usheekh: Int? = null, name: String = "محتوى مضاف", removed: Collection<String> = emptyList(), extra: Map<String, Any?> = emptyMap()): String {
        val root = JSONObject().put("format", "ahl-alhadeeth-pack").put("version", 1).put("name", name).put("exported_at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
        for ((k, v) in extra) root.put(k, v)
        val allRemoved = (removed + removedKeys()).distinct()
        if (allRemoved.isNotEmpty()) root.put("removed", JSONArray(allRemoved))
        val sheekhsArr = JSONArray()
        val sheekhRows = db.query("SELECT id, name, key FROM u_sheekh " + (if (usheekh != null) "WHERE id = $usheekh " else "") + "ORDER BY ord, id") { Triple(it.int(0), it.text(1), it.text(2)) }
        for ((sid, sname, skey) in sheekhRows) {
            val so = JSONObject().put("name", sname)
            if (skey.isNotEmpty()) so.put("key", skey)
            val seriesArr = JSONArray()
            val books = db.query("SELECT id, name, type_name, key FROM u_book WHERE sheekh_id = ? ORDER BY ord, id", sid) { listOf(it.int(0).toString(), it.text(1), it.text(2), it.text(3)) }
            for (b in books) {
                val bid = b[0].toInt()
                val bo = JSONObject().put("name", b[1]).put("type", b[2])
                if (b[3].isNotEmpty()) bo.put("key", b[3])
                val tapes = JSONArray()
                val chs = db.query("SELECT id, title, media_uri, is_video, notes, key, source_url FROM u_chapter WHERE book_id = ? ORDER BY ord, id", bid) {
                    listOf(it.int(0).toString(), it.text(1), it.text(2), if (it.bool(3)) "video" else "audio", it.text(4), it.text(5), it.text(6))
                }
                for (c in chs) {
                    val cid = c[0].toInt()
                    val to = JSONObject().put("title", c[1]).put("media_url", c[2]).put("kind", c[3])
                    if (c[4].isNotEmpty()) to.put("notes", c[4])
                    if (c[5].isNotEmpty()) to.put("key", c[5])
                    if (c[6].isNotEmpty()) to.put("source_url", c[6])
                    val segs = JSONArray()
                    val rows = db.query("SELECT id, line, offset_start, write, ques, hnum FROM u_content WHERE chapter_id = ? ORDER BY offset_start, seq, id", cid) {
                        arrayOf<Any?>(it.long(0), it.text(1), it.long(2), it.textOrNull(3), it.bool(4), it.int(5))
                    }
                    for (r in rows) {
                        val g = JSONObject().put("start", ArabicText.formatTime(r[2] as Long).map { ch -> if (ch in '٠'..'٩') ('0' + (ch - '٠')) else ch }.joinToString("")).put("start_ms", r[2] as Long).put("line", r[1] as String)
                        (r[3] as String?)?.let { g.put("write", it) }
                        if (r[4] as Boolean) g.put("ques", true)
                        if ((r[5] as Int) > 0) g.put("hnum", r[5] as Int)
                        val cats = categoryIdsOfSegment(r[0] as Long)
                        if (cats.isNotEmpty()) g.put("categories", JSONArray(cats))
                        segs.put(g)
                    }
                    to.put("segments", segs)
                    tapes.put(to)
                }
                bo.put("tapes", tapes)
                seriesArr.put(bo)
            }
            so.put("series", seriesArr)
            sheekhsArr.put(so)
        }
        root.put("sheekhs", sheekhsArr)
        return root.toString(2)
    }
}
