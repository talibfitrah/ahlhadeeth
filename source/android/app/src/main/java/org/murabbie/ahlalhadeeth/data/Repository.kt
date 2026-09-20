package org.murabbie.ahlalhadeeth.data

import java.io.File

/** استعلامات قاعدة المحتوى (للقراءة فقط). */
class Repository(private val db: Db, val dbFile: File) {

    /** المحتوى المضاف من المستخدم (معرّفات سالبة)؛ يُضبط من التطبيق بعد الفتح */
    @Volatile var user: UserContent? = null

    // ---------- الجداول المرجعية ----------

    suspend fun sheekhs(): List<Sheekh> =
        db.query("SELECT id, name, is_default, ord FROM sheekh ORDER BY ord, id") { Sheekh(it.int(0), it.text(1), it.bool(2), it.int(3)) } + (user?.sheekhs() ?: emptyList())

    /** المشايخ الأصليون فقط */
    suspend fun mainSheekhs(): List<Sheekh> =
        db.query("SELECT id, name, is_default, ord FROM sheekh ORDER BY ord, id") { Sheekh(it.int(0), it.text(1), it.bool(2), it.int(3)) }

    suspend fun sheekh(id: Int): Sheekh? =
        if (id < 0) user?.sheekh(-id)
        else db.queryOne("SELECT id, name, is_default, ord FROM sheekh WHERE id = ?", id) { Sheekh(it.int(0), it.text(1), it.bool(2), it.int(3)) }

    suspend fun books(): List<Book> {
        val main = db.query("SELECT b.id, b.name, b.ord, b.type_id, IFNULL(t.name,'') FROM book b LEFT JOIN type t ON t.id = b.type_id ORDER BY t.ord, b.ord, b.name") {
            Book(it.int(0), it.text(1), it.int(2), it.int(3), it.text(4))
        }
        val u = user ?: return main
        val extra = ArrayList<Book>()
        for (s in u.sheekhs()) for (sb in u.booksOfSheekh(-s.id)) extra.add(sb.book.copy(typeName = "محتوى مضاف — ${s.name}" ))
        return main + extra
    }

    suspend fun book(id: Int): Book? =
        if (id < 0) user?.book(-id)
        else db.queryOne("SELECT b.id, b.name, b.ord, b.type_id, IFNULL(t.name,'') FROM book b LEFT JOIN type t ON t.id = b.type_id WHERE b.id = ?", id) {
            Book(it.int(0), it.text(1), it.int(2), it.int(3), it.text(4))
        }

    /** كتب الشيخ (كما في نسخة ويندوز: مرتبة بترتيب النوع ثم ترتيب الكتاب) مع عدد الأشرطة والمقاطع. */
    suspend fun booksOfSheekh(sheekhId: Int): List<SheekhBook> =
        if (sheekhId < 0) (user?.booksOfSheekh(-sheekhId) ?: emptyList()) else db.query(
            """SELECT b.id, b.name, b.ord, b.type_id, IFNULL(t.name,''), COUNT(c.code), IFNULL(SUM(c.seg_count),0)
               FROM chapter c JOIN book b ON b.id = c.book_id LEFT JOIN type t ON t.id = b.type_id
               WHERE c.sheekh_id = ?
               GROUP BY b.id ORDER BY t.ord, b.ord, b.name""", sheekhId
        ) { SheekhBook(Book(it.int(0), it.text(1), it.int(2), it.int(3), it.text(4)), sheekhId, it.int(5), it.int(6)) }

    // ---------- الأشرطة ----------

    private val chapterCols = """c.code, c.sheekh_id, c.book_id, c.title, c.file_name, c.file_size, c.path, c.cd_number, c.ord, c.seg_count, c.write_count,
        IFNULL(s.name,''), IFNULL(b.name,'')"""
    private val chapterFrom = "FROM chapter c LEFT JOIN sheekh s ON s.id = c.sheekh_id LEFT JOIN book b ON b.id = c.book_id"

    private fun chapterOf(r: Db.Row) = Chapter(
        code = r.int(0), sheekhId = r.int(1), bookId = r.int(2), title = r.text(3), fileName = r.text(4), fileSize = r.long(5),
        path = r.text(6), cdNumber = r.text(7), ord = r.int(8), segCount = r.int(9), writeCount = r.int(10), sheekhName = r.text(11), bookName = r.text(12)
    )

    suspend fun chapters(sheekhId: Int, bookId: Int): List<Chapter> =
        if (sheekhId < 0) (user?.chapters(-sheekhId, -bookId) ?: emptyList())
        else db.query("SELECT $chapterCols $chapterFrom WHERE c.sheekh_id = ? AND c.book_id = ? ORDER BY c.ord, c.title, c.file_name", sheekhId, bookId) { chapterOf(it) }

    suspend fun chapter(code: Int): Chapter? =
        if (code < 0) user?.chapter(-code)
        else db.queryOne("SELECT $chapterCols $chapterFrom WHERE c.code = ?", code) { chapterOf(it) }

    suspend fun chaptersByCodes(codes: Collection<Int>): Map<Int, Chapter> {
        if (codes.isEmpty()) return emptyMap()
        val out = HashMap<Int, Chapter>()
        codes.filter { it > 0 }.chunked(400).forEach { chunk ->
            val q = chunk.joinToString(",")
            db.query("SELECT $chapterCols $chapterFrom WHERE c.code IN ($q)") { chapterOf(it) }.forEach { out[it.code] = it }
        }
        val neg = codes.filter { it < 0 }.map { -it }
        if (neg.isNotEmpty()) user?.chaptersByIds(neg)?.forEach { out[it.code] = it }
        return out
    }

    suspend fun searchChapters(text: String, limit: Int = 200): List<Chapter> {
        val like = "%${text.trim()}%"
        val main = db.query("SELECT $chapterCols $chapterFrom WHERE c.title LIKE ? OR c.file_name LIKE ? ORDER BY s.ord, b.ord, c.ord, c.title LIMIT ?", like, like, limit) { chapterOf(it) }
        return main + (user?.searchChapters(text, 50) ?: emptyList())
    }

    // ---------- المقاطع ----------

    private val segCols = "id, code, seq, sheekh_id, book_id, hnum, line, offset_start, offset_end, (write IS NOT NULL), ques"
    private fun segmentOf(r: Db.Row) = Segment(
        id = r.long(0), code = r.int(1), seq = r.int(2), sheekhId = r.int(3), bookId = r.int(4), hnum = r.int(5), line = r.text(6),
        offsetStart = r.long(7), offsetEnd = r.long(8), hasWrite = r.bool(9), ques = r.bool(10)
    )

    suspend fun segments(code: Int): List<Segment> =
        if (code < 0) (user?.segments(-code) ?: emptyList())
        else db.query("SELECT $segCols FROM content WHERE code = ? ORDER BY seq", code) { segmentOf(it) }

    suspend fun segment(id: Long): Segment? =
        if (id < 0) user?.segment(-id)
        else db.queryOne("SELECT $segCols FROM content WHERE id = ?", id) { segmentOf(it) }

    suspend fun segment(code: Int, seq: Int): Segment? =
        if (code < 0) user?.segments(-code)?.firstOrNull { it.seq == seq }
        else db.queryOne("SELECT $segCols FROM content WHERE code = ? AND seq = ?", code, seq) { segmentOf(it) }

    suspend fun write(id: Long): String? =
        if (id < 0) user?.write(-id)
        else db.queryOne("SELECT write FROM content WHERE id = ?", id) { it.textOrNull(0) }

    /** تفريغات شريط كامل: معرّف المقطع -> نص التفريغ (المقاطع التي لها تفريغ فقط) */
    suspend fun writesOfChapter(code: Int): Map<Long, String> =
        if (code < 0) (user?.writesOfChapter(-code) ?: emptyMap())
        else db.query("SELECT id, write FROM content WHERE code = ? AND write IS NOT NULL ORDER BY seq", code) { it.long(0) to it.text(1) }.toMap()

    suspend fun segmentsByIds(ids: Collection<Long>): Map<Long, Segment> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, Segment>()
        ids.filter { it > 0 }.chunked(400).forEach { chunk ->
            db.query("SELECT $segCols FROM content WHERE id IN (${chunk.joinToString(",")})") { segmentOf(it) }.forEach { out[it.id] = it }
        }
        val neg = ids.filter { it < 0 }.map { -it }
        if (neg.isNotEmpty()) user?.segmentsByIds(neg)?.forEach { out[it.id] = it }
        return out
    }

    suspend fun categoriesOfSegment(id: Long): List<Category> =
        if (id < 0) (user?.categoryIdsOfSegment(-id) ?: emptyList()).mapNotNull { category(it) }
        else db.query(
            "SELECT c.id, c.name, c.ord, c.parent, c.level, c.direct_count, c.total_count, c.child_count FROM content_cat cc JOIN category c ON c.id = cc.category_id WHERE cc.content_id = ? ORDER BY c.id",
            id
        ) { categoryOf(it) }

    // ---------- التصانيف ----------

    private fun categoryOf(r: Db.Row) = Category(r.int(0), r.text(1), r.int(2), r.int(3), r.int(4), r.int(5), r.int(6), r.int(7))
    private val catCols = "id, name, ord, parent, level, direct_count, total_count, child_count"

    suspend fun categoryChildren(parent: Int): List<Category> =
        db.query("SELECT $catCols FROM category WHERE parent = ? ORDER BY ord, id", parent) { categoryOf(it) }

    suspend fun category(id: Int): Category? =
        db.queryOne("SELECT $catCols FROM category WHERE id = ?", id) { categoryOf(it) }

    suspend fun categoryPath(id: Int): List<Category> {
        val path = ArrayList<Category>()
        var cur = id
        var guard = 0
        while (cur > 0 && guard++ < 20) {
            val c = category(cur) ?: break
            path.add(0, c)
            cur = c.parent
        }
        return path
    }

    suspend fun searchCategories(text: String): List<Category> =
        db.query("SELECT $catCols FROM category WHERE name LIKE ? ORDER BY id", "%${text.trim()}%") { categoryOf(it) }

    /** كل الأصناف الفرعية (للبحث الشامل في فرع كامل) */
    suspend fun categoryDescendants(id: Int): List<Int> {
        val result = ArrayList<Int>()
        var frontier = listOf(id)
        var guard = 0
        while (frontier.isNotEmpty() && guard++ < 20) {
            result.addAll(frontier)
            val q = frontier.joinToString(",")
            frontier = db.query("SELECT id FROM category WHERE parent IN ($q)") { it.int(0) }
        }
        return result
    }

    /** مقاطع صنف معيّن (مباشرة أو مع فروعه) مع تصفية اختيارية بالشيخ */
    suspend fun segmentsOfCategory(categoryId: Int, includeChildren: Boolean, sheekhId: Int?, limit: Int, offset: Int): List<SegmentWithChapter> {
        val ids = if (includeChildren) categoryDescendants(categoryId) else listOf(categoryId)
        val idList = ids.joinToString(",")
        val sheekhFilter = if (sheekhId != null && sheekhId > 0) "AND c.sheekh_id = $sheekhId" else ""
        val segs = if (sheekhId != null && sheekhId < 0) emptyList() else db.query(
            """SELECT DISTINCT c.id, c.code, c.seq, c.sheekh_id, c.book_id, c.hnum, c.line, c.offset_start, c.offset_end, (c.write IS NOT NULL), c.ques
               FROM content_cat cc JOIN content c ON c.id = cc.content_id
               WHERE cc.category_id IN ($idList) $sheekhFilter
               ORDER BY c.code, c.seq LIMIT ? OFFSET ?""", limit, offset
        ) { segmentOf(it) }
        // المحتوى المضاف يُلحق بالصفحة الأولى
        val extra = if (offset == 0 && (sheekhId == null || sheekhId <= 0)) (user?.segmentsOfCategories(ids, if (sheekhId != null && sheekhId < 0) -sheekhId else null, 500, 0) ?: emptyList()) else emptyList()
        return attachChapters(segs + extra)
    }

    suspend fun countSegmentsOfCategory(categoryId: Int, includeChildren: Boolean, sheekhId: Int?): Long {
        val ids = if (includeChildren) categoryDescendants(categoryId) else listOf(categoryId)
        val idList = ids.joinToString(",")
        val sheekhFilter = if (sheekhId != null && sheekhId > 0) "AND c.sheekh_id = $sheekhId" else ""
        val main = if (sheekhId != null && sheekhId < 0) 0L else db.count("SELECT COUNT(DISTINCT c.id) FROM content_cat cc JOIN content c ON c.id = cc.content_id WHERE cc.category_id IN ($idList) $sheekhFilter")
        val extra = if (sheekhId == null || sheekhId <= 0) (user?.countSegmentsOfCategories(ids, if (sheekhId != null && sheekhId < 0) -sheekhId else null) ?: 0L) else 0L
        return main + extra
    }

    suspend fun attachChapters(segs: List<Segment>, snippets: Map<Long, String> = emptyMap()): List<SegmentWithChapter> {
        val chapters = chaptersByCodes(segs.map { it.code }.toSet())
        return segs.mapNotNull { s -> chapters[s.code]?.let { SegmentWithChapter(s, it, snippets[s.id] ?: "") } }
    }

    // ---------- البحث ----------

    /** البحث بالفهرس النصي (FTS5). يعيد المقاطع مع مقتطف. */
    suspend fun search(opts: SearchOptions): List<SegmentWithChapter> {
        val words = ArabicText.queryWords(opts.query)
        if (words.isEmpty()) return emptyList()
        val column = when {
            opts.inLine && !opts.inWrite -> "line"
            !opts.inLine && opts.inWrite -> "write"
            else -> null
        }
        val fts = ArabicText.buildFtsQuery(words, opts.matchAll, column, opts.exactPhrase) ?: return emptyList()
        val onlyUser = opts.sheekhIds.isNotEmpty() && opts.sheekhIds.all { it < 0 } || opts.bookIds.isNotEmpty() && opts.bookIds.all { it < 0 }
        val mainSheekhs = opts.sheekhIds.filter { it > 0 }
        val mainBooks = opts.bookIds.filter { it > 0 }
        val whereMain = StringBuilder("content_fts MATCH ?")
        if (mainSheekhs.isNotEmpty()) whereMain.append(" AND c.sheekh_id IN (${mainSheekhs.joinToString(",")})")
        if (mainBooks.isNotEmpty()) whereMain.append(" AND c.book_id IN (${mainBooks.joinToString(",")})")
        if (opts.questionsOnly) whereMain.append(" AND c.ques <> 0")
        val mainRows = if (onlyUser) emptyList() else db.query(
            """SELECT c.id, c.code, c.seq, c.sheekh_id, c.book_id, c.hnum, c.line, c.offset_start, c.offset_end, (c.write IS NOT NULL), c.ques,
                      c.write
               FROM content_fts f JOIN content c ON c.id = f.rowid
               WHERE $whereMain ORDER BY bm25(content_fts, 4.0, 1.0) LIMIT ? OFFSET ?""", fts, opts.limit, opts.offset
        ) { r -> segmentOf(r) to r.textOrNull(11) }
        val userRows = if (opts.offset == 0) (user?.search(opts) ?: emptyList()) else emptyList()
        val rows = mainRows + userRows
        val snippets = HashMap<Long, String>()
        val inWrite = HashSet<Long>()
        for ((seg, w) in rows) {
            val lineMatch = ArabicText.findMatches(seg.line, words).isNotEmpty()
            if (!lineMatch && w != null) {
                val m = ArabicText.findMatches(w, words)
                if (m.isNotEmpty()) {
                    snippets[seg.id] = ArabicText.snippet(w, words)
                    inWrite.add(seg.id)
                    continue
                }
            }
            if (w != null && (opts.inWrite && !opts.inLine)) {
                snippets[seg.id] = ArabicText.snippet(w, words)
                inWrite.add(seg.id)
            }
        }
        val res = attachChapters(rows.map { it.first }, snippets)
        return res.map { if (it.segment.id in inWrite) it.copy(matchedInWrite = true) else it }
    }

    suspend fun searchCount(opts: SearchOptions): Long {
        val words = ArabicText.queryWords(opts.query)
        if (words.isEmpty()) return 0
        val column = when {
            opts.inLine && !opts.inWrite -> "line"
            !opts.inLine && opts.inWrite -> "write"
            else -> null
        }
        val fts = ArabicText.buildFtsQuery(words, opts.matchAll, column, opts.exactPhrase) ?: return 0
        val onlyUser = opts.sheekhIds.isNotEmpty() && opts.sheekhIds.all { it < 0 } || opts.bookIds.isNotEmpty() && opts.bookIds.all { it < 0 }
        val mainSheekhs = opts.sheekhIds.filter { it > 0 }
        val mainBooks = opts.bookIds.filter { it > 0 }
        val where = StringBuilder("content_fts MATCH ?")
        if (mainSheekhs.isNotEmpty()) where.append(" AND c.sheekh_id IN (${mainSheekhs.joinToString(",")})")
        if (mainBooks.isNotEmpty()) where.append(" AND c.book_id IN (${mainBooks.joinToString(",")})")
        if (opts.questionsOnly) where.append(" AND c.ques <> 0")
        val main = if (onlyUser) 0L else db.count("SELECT COUNT(*) FROM content_fts f JOIN content c ON c.id = f.rowid WHERE $where", fts)
        return main + (user?.searchCount(opts) ?: 0L)
    }

    /**
     * بحث حرفي بطيء (يفحص كل النصوص كما في نسخة ويندوز LIKE '%كلمة%') — يستخدم عند الحاجة لمطابقة داخل الكلمات.
     * يُقيَّد بالشيخ/الكتاب لتسريعه. onProgress يستقبل عدد الصفوف المفحوصة.
     */
    suspend fun literalSearch(opts: SearchOptions, onProgress: suspend (Int) -> Unit = {}): List<SegmentWithChapter> {
        val words = ArabicText.queryWords(opts.query)
        if (words.isEmpty()) return emptyList()
        val where = StringBuilder("1=1")
        if (opts.sheekhIds.isNotEmpty()) where.append(" AND sheekh_id IN (${opts.sheekhIds.joinToString(",")})")
        if (opts.bookIds.isNotEmpty()) where.append(" AND book_id IN (${opts.bookIds.joinToString(",")})")
        if (opts.questionsOnly) where.append(" AND ques <> 0")
        val found = ArrayList<Segment>()
        val snippets = HashMap<Long, String>()
        val inWrite = HashSet<Long>()
        var lastId = 0L
        var scanned = 0
        val chunk = 3000
        val phrase = words.joinToString(" ")
        while (found.size < opts.limit) {
            val rows = db.query(
                "SELECT $segCols, ${if (opts.inWrite) "write" else "NULL"} FROM content WHERE id > ? AND $where ORDER BY id LIMIT ?", lastId, chunk
            ) { r -> segmentOf(r) to r.textOrNull(11) }
            if (rows.isEmpty()) break
            for ((seg, w) in rows) {
                lastId = seg.id
                val nl = if (opts.inLine) ArabicText.normalize(seg.line) else ""
                val nw = if (opts.inWrite && w != null) ArabicText.normalize(w) else ""
                val hit: Boolean = if (opts.exactPhrase) {
                    nl.contains(phrase) || nw.contains(phrase)
                } else if (opts.matchAll) {
                    words.all { nl.contains(it) || nw.contains(it) }
                } else {
                    words.any { nl.contains(it) || nw.contains(it) }
                }
                if (hit) {
                    found.add(seg)
                    val lineHit = if (opts.exactPhrase) nl.contains(phrase) else words.any { nl.contains(it) }
                    if (!lineHit && w != null) {
                        snippets[seg.id] = ArabicText.snippet(w, words)
                        inWrite.add(seg.id)
                    }
                    if (found.size >= opts.limit) break
                }
            }
            scanned += rows.size
            onProgress(scanned)
        }
        val res = attachChapters(found, snippets)
        return res.map { if (it.segment.id in inWrite) it.copy(matchedInWrite = true) else it }
    }

    // ---------- الإحصاءات ----------

    suspend fun sheekhStats(): List<SheekhStats> =
        db.query(
            """SELECT s.id, s.name, s.is_default, s.ord, COUNT(DISTINCT c.book_id), COUNT(c.code), IFNULL(SUM(c.seg_count),0), IFNULL(SUM(c.write_count),0), IFNULL(SUM(c.file_size),0)
               FROM sheekh s LEFT JOIN chapter c ON c.sheekh_id = s.id GROUP BY s.id ORDER BY s.ord"""
        ) { SheekhStats(Sheekh(it.int(0), it.text(1), it.bool(2), it.int(3)), it.int(4), it.int(5), it.int(6), it.int(7), it.long(8)) }

    suspend fun bookStats(sheekhId: Int?): List<BookStats> {
        val filter = if (sheekhId != null && sheekhId > 0) "WHERE c.sheekh_id = $sheekhId" else ""
        return db.query(
            """SELECT b.id, b.name, b.ord, b.type_id, IFNULL(t.name,''), COUNT(c.code), IFNULL(SUM(c.seg_count),0), IFNULL(SUM(c.write_count),0), IFNULL(SUM(c.file_size),0)
               FROM chapter c JOIN book b ON b.id = c.book_id LEFT JOIN type t ON t.id = b.type_id $filter
               GROUP BY b.id ORDER BY t.ord, b.ord, b.name"""
        ) { BookStats(Book(it.int(0), it.text(1), it.int(2), it.int(3), it.text(4)), it.int(5), it.int(6), it.int(7), it.long(8)) }
    }

    suspend fun meta(): Map<String, String> = db.query("SELECT key, value FROM meta") { it.text(0) to it.text(1) }.toMap()

    suspend fun info(): DbInfo = DbInfo(meta(), dbFile.length())

    fun close() = db.close()
}
