import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.murabbie.ahlalhadeeth.data.ArabicText
import org.murabbie.ahlalhadeeth.data.Db
import org.murabbie.ahlalhadeeth.data.NasHttp
import org.murabbie.ahlalhadeeth.data.Repository
import org.murabbie.ahlalhadeeth.data.SearchOptions
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections
import java.util.zip.GZIPInputStream

fun check(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError("FAILED: $msg") else println("ok: $msg")
}

fun main(args: Array<String>) = runBlocking {
    val dbPath = args.getOrElse(0) { "/home/claude/alathar/build/ahl_alhadeeth.db" }
    val mode = args.getOrElse(1) { "db" }

    if (mode == "db") {
        // ---------- التطبيع ----------
        check(ArabicText.normalize("الهَدْيِ هَدْيُ مُحَمَّدٍ") == "الهدي هدي محمد", "normalize diacritics")
        check(ArabicText.normalize("أإآٱ ىیئ ة ؤ ١٢٣ ـــ") == "اااا ييي ه و 123 ", "normalize letters and digits")
        check(ArabicText.queryWords("التوسّل، والبدعة!") == listOf("التوسل", "والبدعه"), "query words ${ArabicText.queryWords("التوسّل، والبدعة!")}")
        check(ArabicText.formatTime(3_725_000) == "١:٠٢:٠٥", "format time ${ArabicText.formatTime(3_725_000)}")
        // ---------- أنواع الوسائط لـ Gemini ----------
        val mm = org.murabbie.ahlalhadeeth.data.MediaMime
        check(mm.audioMimeFor("video/webm", "027-abc.webm") == "audio/webm", "webm audio mime")
        check(mm.audioMimeFor("video/mp4", "x.mp4") == "audio/mp4" && mm.audioMimeFor("audio/mpeg", "x.mp3") == "audio/mpeg", "mp4/mp3 audio mime")
        check(mm.alternatives("video/webm", "x.webm") == listOf("audio/webm"), "alternatives of video/webm ${mm.alternatives("video/webm", "x.webm")}")
        check(mm.alternatives("audio/webm", "x.webm") == listOf("video/webm"), "alternatives of audio/webm ${mm.alternatives("audio/webm", "x.webm")}")
        check(mm.alternatives("audio/aac", "m5.aac").isEmpty(), "alternatives of aac")
        // ---------- بث Gemini وتقدّم التفريغ ----------
        val gc = org.murabbie.ahlalhadeeth.data.GeminiClient
        val sse = okio.Buffer().writeUtf8("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"topics\\\":[{\\\"start\\\":\\\"0:00:00\\\",\"}]}}]}\n\n" +
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\\\"title\\\":\\\"أ\\\"},{\\\"start\\\":\\\"0:15:00\\\",\\\"title\\\":\\\"ب\\\"}]}\"}]},\"finishReason\":\"STOP\"}]}\n\n")
        val partials = ArrayList<String>()
        val full = gc.readStream(sse) { partials.add(it) }
        check(partials.size == 2 && full.contains("0:15:00"), "sse chunks ${partials.size}: $full")
        val fr1 = gc.streamedFraction(partials[0], 0L, 1_800_000L); val fr2 = gc.streamedFraction(partials[1], 0L, 1_800_000L)
        check(fr1 == 0f && fr2 != null && Math.abs(fr2 - 0.5f) < 0.01f, "streamed fraction $fr1 $fr2")
        check(gc.streamedFraction("{\"topics\":[", 0L, 1000L) == null && gc.streamedFraction(partials[1], 1_800_000L, 3_600_000L) == 0f, "fraction before first start / clamped")
        val parsed = gc.parseTopics(full, allowText = false)
        check(parsed.size == 2 && parsed[1].start == 900_000L, "parse streamed topics ${parsed.size}")
        val (n, map) = ArabicText.normalizeWithMap("مُحَمَّد")
        check(n == "محمد" && map.size == 4 && map[3] == 7, "normalizeWithMap ${map.toList()}")
        val m = ArabicText.findMatches("قال الشيخ الألبانيُّ رحمه الله", ArabicText.queryWords("البانى"))
        check(m.size == 1 && m[0].first == 12 && m[0].last == 17, "findMatches ${m}")

        // ---------- القاعدة ----------
        val db = Db(dbPath, readOnly = true)
        val repo = Repository(db, File(dbPath))
        val meta = repo.meta()
        check(meta["schema_version"] == "1", "meta schema ${meta}")
        val sheekhs = repo.sheekhs()
        check(sheekhs.size == 10 && sheekhs.first().name.contains("ابن باز"), "sheekhs ordered: ${sheekhs.map { it.name }}")
        val books = repo.booksOfSheekh(1)
        check(books.isNotEmpty(), "books of albani: ${books.size} — ${books.take(3).map { it.book.name + "/" + it.chapterCount }}")
        val chapters = repo.chapters(1, 1)
        check(chapters.size == 705, "alnoor chapters ${chapters.size}")
        val ch = chapters.first()
        check(ch.relativeAudioPath() == "alalbani/alnoor/001.mp3", "audio path ${ch.relativeAudioPath()}")
        val segs = repo.segments(ch.code)
        check(segs.isNotEmpty() && segs[0].line.isNotEmpty(), "segments of tape 1: ${segs.size}; first: ${segs[0].line.take(40)} @${segs[0].offsetStart}")
        val w = repo.write(segs[0].id)
        check(w != null && w.length > 100, "write text length ${w?.length}")
        val cats = repo.categoriesOfSegment(segs[0].id)
        check(cats.isNotEmpty(), "categories of segment: ${cats.map { it.name }}")
        val roots = repo.categoryChildren(0)
        check(roots.size == 17, "root categories ${roots.size}: ${roots.take(3).map { it.name + "/" + it.totalCount }}")
        val path = repo.categoryPath(5)
        check(path.map { it.id } == listOf(1, 2, 3, 5), "category path ${path.map { it.name }}")
        val catSegs = repo.segmentsOfCategory(5, true, 1, 20, 0)
        check(catSegs.isNotEmpty() && catSegs.all { it.chapter.sheekhId == 1 }, "segments of category 5 for albani: ${catSegs.size}")
        val catCount = repo.countSegmentsOfCategory(1, true, null)
        check(catCount == 26396L, "total count of root 1 = $catCount")

        // ---------- البحث ----------
        var t = System.currentTimeMillis()
        val r1 = repo.search(SearchOptions("التوسل", limit = 50))
        println("search التوسل: ${r1.size} in ${System.currentTimeMillis() - t} ms; first: ${r1.firstOrNull()?.segment?.line?.take(60)} | snippet: ${r1.firstOrNull()?.snippet?.take(60)}")
        check(r1.isNotEmpty(), "search returns results")
        val c1 = repo.searchCount(SearchOptions("التوسل"))
        println("count التوسل = $c1")
        t = System.currentTimeMillis()
        val r2 = repo.search(SearchOptions("بدعة صلاة", matchAll = true, sheekhIds = setOf(6), limit = 50))
        println("search بدعة+صلاة (uthaymeen): ${r2.size} in ${System.currentTimeMillis() - t} ms")
        check(r2.all { it.chapter.sheekhId == 6 }, "sheekh filter works")
        val r3 = repo.search(SearchOptions("إنما الأعمال بالنيات", exactPhrase = true, limit = 20))
        println("exact phrase: ${r3.size}; first: ${r3.firstOrNull()?.segment?.line?.take(80)}")
        check(r3.isNotEmpty(), "exact phrase search")
        val r4 = repo.search(SearchOptions("الوضوء", inLine = true, inWrite = false, questionsOnly = true, limit = 20))
        println("questions only line search: ${r4.size}")
        check(r4.all { it.segment.ques }, "questions-only filter")
        t = System.currentTimeMillis()
        val r5 = repo.literalSearch(SearchOptions("سلفي", sheekhIds = setOf(2), inWrite = true, limit = 50)) { }
        println("literal search سلفي (shanqiti): ${r5.size} in ${System.currentTimeMillis() - t} ms")
        val stats = repo.sheekhStats()
        check(stats.sumOf { it.chapters } == 13716, "stats chapters ${stats.sumOf { it.chapters }}")
        val bs = repo.bookStats(6)
        check(bs.isNotEmpty(), "book stats for uthaymeen ${bs.size}")
        val found = repo.searchChapters("نور على الدرب", 10)
        check(found.isNotEmpty(), "chapter title search ${found.size}")
        val byIds = repo.segmentsByIds(listOf(1L, 2L, 3L))
        check(byIds.size == 3, "segmentsByIds")
        db.close()
        println("ALL DB TESTS PASSED")
    }

    if (mode == "user") {
        // ---------- المحتوى المضاف: حزمة، فهرس نصي، دمج في المستودع ----------
        val udbFile = File("/tmp/claude-0/-home-claude/05351d93-98bc-5059-844e-6845587e9cea/scratchpad/jvm_user.db")
        udbFile.delete()
        val udb = Db(udbFile.absolutePath, readOnly = false)
        val uc = org.murabbie.ahlalhadeeth.data.UserContent(udb)
        uc.init()
        val parsed = org.murabbie.ahlalhadeeth.data.UserContent.parseIndexText("""
            ٠:٠٠:٢٧ - مقدمة الدرس وبيان أهمية التوحيد
            الحمد لله رب العالمين، هذا تفريغ المقدمة.
            سطر ثانٍ من التفريغ.
            12:30 - ما حكم التوسل بالصالحين؟
            الجواب: التوسل المشروع ثلاثة أنواع…
            [1:05:10] حديث 12 عن ابن عمر في الصلاة
        """.trimIndent())
        check(parsed.size == 3, "parsed ${parsed.size}: ${parsed.map { it.start to it.line }}")
        check(parsed[0].start == 27_000L && parsed[0].write!!.contains("سطر ثانٍ"), "first segment start/write")
        check(parsed[1].start == 750_000L && parsed[1].ques, "question detected at 12:30")
        check(parsed[2].start == 3_910_000L && parsed[2].hnum == 12, "hadith number ${parsed[2].hnum} at ${parsed[2].start}")
        check(org.murabbie.ahlalhadeeth.data.UserContent.parseTime("١:٠٢:٠٥") == 3_725_000L, "parseTime arabic digits")
        check(org.murabbie.ahlalhadeeth.data.UserContent.parseTime("125.5") == 125_500L, "parseTime seconds")

        val sid = uc.addSheekh("الشيخ عبد الرزاق البدر")
        val bid = uc.addBook(sid, "شرح كتاب التوحيد", "عقيدة")
        val cid = uc.addChapter(sid, bid, "الدرس الأول", "https://example.org/badr/01.mp3", false)
        uc.addSegments(cid, parsed)
        val segId = uc.addSegment(cid, "فائدة في الإخلاص", 2_000_000L, "نص عن الإخلاص والنية", false, 0, listOf(5, 943))
        val segs = uc.segments(cid)
        check(segs.size == 4 && segs.map { it.seq } == listOf(1, 2, 3, 4) && segs[2].id == -segId, "renumbered by time: ${segs.map { it.seq to it.offsetStart }}")
        check(uc.categoryIdsOfSegment(segId) == listOf(5, 943), "categories saved")

        // الدمج مع المستودع الأصلي
        val db = Db(dbPath, readOnly = true)
        val repo = Repository(db, File(dbPath))
        repo.user = uc
        val all = repo.sheekhs()
        check(all.size == 11 && all.last().id == -sid, "merged sheekhs ${all.size}, last ${all.last()}")
        val books = repo.booksOfSheekh(-sid)
        check(books.size == 1 && books[0].chapterCount == 1 && books[0].segmentCount == 4, "user books ${books}")
        val ch = repo.chapter(-cid)!!
        check(ch.isUser && ch.mediaUri.endsWith("01.mp3") && ch.segCount == 4 && ch.writeCount == 3, "user chapter $ch")
        check(ch.relativeAudioPath() == "user/u$cid.mp3", "user audio path ${ch.relativeAudioPath()}")
        val r = repo.search(SearchOptions("التوسل", limit = 20))
        check(r.any { it.segment.isUser && it.chapter.isUser }, "user segment appears in merged search (${r.count { it.segment.isUser }} user of ${r.size})")
        val rOnlyUser = repo.search(SearchOptions("التوسل", sheekhIds = setOf(-sid)))
        check(rOnlyUser.size == 1 && rOnlyUser[0].segment.isUser, "filter by user sheekh only -> ${rOnlyUser.size}")
        check(repo.searchCount(SearchOptions("التوسل", sheekhIds = setOf(-sid))) == 1L, "count only user")
        val catSegs = repo.segmentsOfCategory(5, false, null, 5000, 0)
        check(catSegs.any { it.segment.id == -segId }, "user segment linked to category 5 appears (${catSegs.size})")
        check(repo.categoriesOfSegment(-segId).map { it.id } == listOf(5, 943), "categories of user segment resolved to names: ${repo.categoriesOfSegment(-segId).map { it.name }}")
        val byIds = repo.segmentsByIds(listOf(1L, -segId))
        check(byIds.size == 2, "segmentsByIds mixed")
        check(repo.writesOfChapter(-cid).size == 3, "writesOfChapter user")

        // تصدير ثم استيراد في قاعدة أخرى
        val json = uc.exportPack(null, "اختبار")
        val udb2 = File("/tmp/claude-0/-home-claude/05351d93-98bc-5059-844e-6845587e9cea/scratchpad/jvm_user2.db"); udb2.delete()
        val uc2 = org.murabbie.ahlalhadeeth.data.UserContent(Db(udb2.absolutePath, readOnly = false)); uc2.init()
        val res = uc2.importPack(json)
        check(res.sheekhs == 1 && res.books == 1 && res.chapters == 1 && res.segments == 4, "import result $res")
        val res2 = uc2.importPack(json)
        check(res2.chapters == 0 && res2.segments == 4 && uc2.stats().second == 1, "re-import replaces instead of duplicating: $res2 ${uc2.stats()}")
        val seg2 = uc2.segments(1)
        check(seg2.size == 4 && seg2.map { it.offsetStart } == segs.map { it.offsetStart }, "segments survive round trip")
        check(uc2.categoryIdsOfSegment(-seg2[2].id).size == 2, "categories survive round trip")
        uc.deleteSheekh(sid)
        check(uc.stats() == Triple(0, 0, 0), "cascade delete ${uc.stats()}")
        db.close()
        println("ALL USER TESTS PASSED")
    }

    if (mode == "net") {
        // ---------- تنزيل جزء من NAS عبر رابط المشاركة مع الاستئناف ----------
        val url = args.getOrElse(2) { "https://files.murabbie.org/fsdownload/yuS07Yrll/ahl_alhadeeth.db.gz.007" }
        val dest = File("/tmp/claude-0/-home-claude/05351d93-98bc-5059-844e-6845587e9cea/scratchpad/jvm_part7.bin")
        dest.delete()
        val manifest = NasHttp.fetchText("https://files.murabbie.org/fsdownload/gIX4l2mhu/manifest.json")
        check(manifest.contains("\"parts\""), "manifest fetched (${manifest.length} bytes)")
        val size = NasHttp.remoteSize(url)
        println("remote size $size")
        // تنزيل جزئي ثم استئناف
        var t = System.currentTimeMillis()
        try {
            NasHttp.download(url, dest, size) { have, total -> if (have > 5_000_000) throw RuntimeException("simulated interruption at $have") }
        } catch (e: Exception) {
            println("first attempt ended: ${e.message}; have ${dest.length()}")
        }
        val partial = dest.length()
        NasHttp.download(url, dest, size) { have, total -> }
        println("downloaded ${dest.length()} bytes in ${System.currentTimeMillis() - t} ms (resumed from $partial)")
        check(dest.length() == size, "size matches")
        val sha = NasHttp.sha256(dest)
        check(sha == "ed0e96f9499451ab1bac2377e7c25f566019a0cb92d37427cef69251828de5d8", "sha256 of part 7 matches: $sha")
        println("ALL NET TESTS PASSED")
    }

    if (mode == "gz") {
        // ---------- فك ضغط الأجزاء المتسلسلة ----------
        val parts = (1..7).map { File("/home/claude/alathar/build/ahl_alhadeeth.db.gz.%03d".format(it)) }
        val out = File("/tmp/claude-0/-home-claude/05351d93-98bc-5059-844e-6845587e9cea/scratchpad/jvm_unpacked.db")
        val streams = Collections.enumeration(parts.map { it.inputStream() as InputStream })
        val t = System.currentTimeMillis()
        GZIPInputStream(SequenceInputStream(streams), 256 * 1024).use { gz -> FileOutputStream(out).use { o -> gz.copyTo(o, 1024 * 1024) } }
        println("unpacked ${out.length()} in ${System.currentTimeMillis() - t} ms")
        check(out.length() == 569331712L, "unpacked size")
        val db = Db(out.absolutePath, readOnly = true)
        val repo = Repository(db, out)
        check(repo.meta()["schema_version"] == "1", "unpacked db opens")
        db.close()
        out.delete()
        println("ALL GZ TESTS PASSED")
    }

    if (mode == "yt") {
        val yt = org.murabbie.ahlalhadeeth.data.YouTube
        // رمز المتابعة المولَّد يطابق ما يولّده سكربت بايثون المرجعي (index=200)
        val tok = yt.continuationToken("PL7yR8Ll1ftQvhUpxinD8sHpBZOxNB8_9J", 200)
        val pyTok = ProcessBuilder("python3", "-c", """
import base64, urllib.parse
def varint(n):
    out=bytearray()
    while True:
        b=n&0x7f; n>>=7
        if n: out.append(b|0x80)
        else: out.append(b); break
    return bytes(out)
def field(fn, wt, payload): return varint((fn<<3)|wt)+(payload if wt==0 else varint(len(payload))+payload)
pl='PL7yR8Ll1ftQvhUpxinD8sHpBZOxNB8_9J'
pt=b'PT:'+base64.urlsafe_b64encode(field(1,0,varint(200))).rstrip(b'=')
params=base64.b64encode(field(1,0,varint(1))+field(15,2,pt)).decode()
inner=field(2,2,('VL'+pl).encode())+field(3,2,urllib.parse.quote(params, safe='').encode())+field(35,2,pl.encode())
print(base64.b64encode(field(80226972,2,inner)).decode())
""").redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
        check(tok == pyTok, "continuation token matches reference generator: $tok vs $pyTok")
        val info = yt.fetchInfo("https://www.youtube.com/playlist?list=PL3523jgHxjkb5EReLsmikzywCm02kXkN4")
        check(info != null && info.first.contains("فتح المجيد") && info.second.isNotBlank(), "oEmbed playlist info: $info")
        var t = System.currentTimeMillis()
        val small = yt.fetchPlaylist("PLClZ7RmnwNVP5UIU8gUaWJQGouOtl6jBS") { }
        println("small playlist: ${small.title} / ${small.author}: ${small.videos.size} in ${System.currentTimeMillis() - t} ms; first: ${small.videos.first()}")
        check(small.videos.size in 50..60 && small.videos.all { it.id.length == 11 && it.title.isNotBlank() } && small.title.contains("التوحيد"), "small playlist (${small.videos.size})")
        t = System.currentTimeMillis()
        var progress = 0
        val big = yt.fetchPlaylist("PL7yR8Ll1ftQvhUpxinD8sHpBZOxNB8_9J") { progress = it }
        println("big playlist: ${big.title}: ${big.videos.size} videos in ${System.currentTimeMillis() - t} ms; last: ${big.videos.last()}")
        check(big.videos.size > 900 && big.videos.map { it.id }.toSet().size == big.videos.size && progress == big.videos.size, "big playlist paginated (${big.videos.size}, progress $progress)")
        check(runCatching { yt.fetchPlaylist("PLxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx") }.isFailure, "invalid playlist fails cleanly")
        println("ALL YT TESTS PASSED")
    }

    if (mode == "share") {
        // معترض روابط المشاركة: طلب مباشر عبر NasHttp.client (كما يفعل مشغّل Media3) بلا تهيئة يدوية
        val url = "https://files.murabbie.org/fsdownload/gIX4l2mhu/manifest.json"
        var ok = false
        for (i in 0 until 8) {
            NasHttp.client.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=0-99").build()).execute().use { r ->
                println("attempt $i: ${r.code} ${r.header("Content-Type")} ${r.header("Content-Range")}")
                if (r.code == 206 && (r.header("Content-Range") ?: "").startsWith("bytes 0-99/")) ok = true
            }
            if (ok) break
            Thread.sleep(1500)
        }
        check(ok, "range request through interceptor gives 206")
        val db2 = NasHttp.client.newCall(okhttp3.Request.Builder().url("https://files.murabbie.org/fsdownload/4aPZIQpaW/shared-content.json").build()).execute().use { it.code to (it.body?.string() ?: "") }
        println("second share: ${db2.first} ${db2.second.take(40)}")

        // ---------- خادم البيانات عبر مشاركة مجلد (قالب {path}) والتبديل التلقائي إلى alathar ----------
        val nasTpl = "https://files.murabbie.org/webapi/entry.cgi?api=SYNO.FolderSharing.Download&version=2&method=download&mode=open&_sharing_id=ehniFEwJg&path=%5B%22%2Fdownloads%2Fahl-alhadeeth%2Fsound%2F{path}%22%5D"
        val alathar = "https://www.alathar.net/files/sound/"
        val u1 = org.murabbie.ahlalhadeeth.data.AudioUrls.buildUrl(nasTpl, "alalbani/alnoor/001.mp3")
        check(u1.endsWith("sound%2Falalbani/alnoor/001.mp3%22%5D"), "template url $u1")
        check(org.murabbie.ahlalhadeeth.data.AudioUrls.relOf(nasTpl, u1) == "alalbani/alnoor/001.mp3", "relOf template")
        check(org.murabbie.ahlalhadeeth.data.AudioUrls.relOf(alathar, alathar + "x/y/1.mp3") == "x/y/1.mp3" && org.murabbie.ahlalhadeeth.data.AudioUrls.relOf(alathar, u1) == null, "relOf base")
        check(NasHttp.isShareLink(u1) && NasHttp.sharingPrefix(u1) == "https://files.murabbie.org/sharing/ehniFEwJg", "folder share detection ${NasHttp.sharingPrefix(u1)}")
        NasHttp.audioServers = listOf(nasTpl, alathar)
        var ok2 = false
        for (i in 0 until 8) {
            NasHttp.client.newCall(okhttp3.Request.Builder().url(u1).header("Range", "bytes=0-99").build()).execute().use { r ->
                println("nas folder share attempt $i: ${r.code} ${r.header("Content-Type")} ${r.header("Content-Range")} -> ${r.request.url.host}")
                if (r.code == 206 && (r.header("Content-Range") ?: "").startsWith("bytes 0-99/") && r.request.url.host == "files.murabbie.org") ok2 = true
            }
            if (ok2) break
            Thread.sleep(1500)
        }
        check(ok2, "folder-share range request gives 206 from NAS")
        // ملف غير موجود بعد على الخادم → يُجلب من alathar تلقائيًا
        val u2 = org.murabbie.ahlalhadeeth.data.AudioUrls.buildUrl(nasTpl, "mansour/alsira/101.mp3")
        var ok3 = false
        for (i in 0 until 6) {
            NasHttp.client.newCall(okhttp3.Request.Builder().url(u2).header("Range", "bytes=0-99").build()).execute().use { r ->
                println("fallback attempt $i: ${r.code} ${r.header("Content-Type")} -> ${r.request.url.host}")
                if (r.code == 206 && r.request.url.host == "www.alathar.net") ok3 = true
            }
            if (ok3) break
            Thread.sleep(1500)
        }
        check(ok3, "missing NAS file falls back to alathar")
        println("ALL SHARE TESTS PASSED")
    }

    if (mode == "segment") {
        // واجهة سطر أوامر لأداة النقل: json3 (+ وصف الفيديو) → مواضع JSON على المخرج القياسي
        val json3 = File(args[2]).readText()
        val desc = args.getOrNull(3)?.let { p -> File(p).takeIf { it.exists() }?.readText() } ?: ""
        val items = org.murabbie.ahlalhadeeth.data.YouTube.parseJson3(json3)
        val chapters = org.murabbie.ahlalhadeeth.data.YouTube.parseChapters(desc)
        val segs = org.murabbie.ahlalhadeeth.data.TopicSegmenter.segment(items, chapters)
        val arr = org.json.JSONArray()
        for (s in segs) {
            val g = org.json.JSONObject().put("start", org.murabbie.ahlalhadeeth.data.GeminiClient.formatClock(s.start)).put("start_ms", s.start).put("line", s.line)
            s.write?.let { g.put("write", it) }
            if (s.ques) g.put("ques", true)
            if (s.hnum > 0) g.put("hnum", s.hnum)
            arr.put(g)
        }
        val out = org.json.JSONObject().put("lines", items.size).put("chapters", chapters.size).put("segments", arr)
        val w = java.io.PrintStream(System.out, true, "UTF-8")
        w.println(out.toString())
        return@runBlocking
    }

    if (mode == "auto") {
        val yt = org.murabbie.ahlalhadeeth.data.YouTube
        val seg = org.murabbie.ahlalhadeeth.data.TopicSegmenter
        // json3
        val json3 = """{"events":[{"tStartMs":0,"dDurationMs":2000,"segs":[{"utf8":"بسم الله"},{"utf8":" الرحمن الرحيم"}]},{"tStartMs":2000,"dDurationMs":1000,"aAppend":1,"segs":[{"utf8":"\n"}]},{"tStartMs":3000,"dDurationMs":2500,"segs":[{"utf8":"[موسيقى]"}]},{"tStartMs":6000,"dDurationMs":3000,"segs":[{"utf8":"الحمد لله رب العالمين"}]}]}"""
        val items = yt.parseJson3(json3)
        check(items.size == 2 && items[0].text == "بسم الله الرحمن الرحيم" && items[1].startMs == 6000L, "parseJson3 $items")
        // chapters from description
        val desc = "مقدمة\n0:00 المقدمة\n02:15 - شرح الحديث الأول\n[1:05:10] حديث ١٢ عن ابن عمر\nرابط القناة: https://x"
        val ch = yt.parseChapters(desc)
        check(ch.size == 3 && ch[1] == (135_000L to "شرح الحديث الأول") && ch[2].first == 3_910_000L, "parseChapters $ch")
        check(yt.parseChapters("5:00 فقط\n3:00 قبله").isEmpty() && yt.parseChapters("").isEmpty(), "non-monotonic chapters rejected")
        // تفريغ اصطناعي: مقدمة ثم شرح ثم سؤالان
        val lines = ArrayList<org.murabbie.ahlalhadeeth.data.YouTube.TimedText>()
        var t = 0L
        fun add(text: String, dur: Long = 5000) { lines.add(org.murabbie.ahlalhadeeth.data.YouTube.TimedText(t, dur, text)); t += dur }
        add("بسم الله الرحمن الرحيم الحمد لله رب العالمين والصلاة والسلام على رسول الله")
        repeat(10) { add("وبعد فهذا مجلس في شرح كتاب التوحيد نتكلم فيه عن معنى التوحيد وأقسامه") }
        add("قال المصنف رحمه الله باب فضل التوحيد وما يكفر من الذنوب")
        repeat(30) { add("والحديث في هذا الباب حديث عبادة بن الصامت رضي الله عنه من شهد أن لا إله إلا الله") }
        add("السؤال أحسن الله إليكم يقول السائل ما حكم التوسل بالصالحين")
        repeat(12) { add("الجواب التوسل المشروع ثلاثة أنواع التوسل بأسماء الله وصفاته") }
        add("سؤال آخر هل يجوز الحلف بغير الله")
        repeat(8) { add("الجواب لا يجوز الحلف بغير الله لقوله صلى الله عليه وسلم من حلف بغير الله فقد أشرك") }
        repeat(120) { add("ثم نتكلم عن مسائل الباب مسألة مسألة ونذكر ما فيها من الفوائد والأحكام") }
        val segs = seg.segment(lines)
        println("segments: " + segs.map { org.murabbie.ahlalhadeeth.data.ArabicText.formatTime(it.start) + " " + (if (it.ques) "؟ " else "") + it.line })
        check(segs.size >= 5 && segs[0].line == "المقدمة" && segs[0].start == 0L, "intro detected: ${segs[0]}")
        check(segs.any { it.ques && it.line.contains("التوسل") } && segs.any { it.ques && it.line.contains("الحلف") }, "questions detected")
        check(segs.any { it.line.startsWith("قال المصنف") }, "topic cue detected")
        check(segs.all { it.write != null && it.write!!.isNotBlank() } && segs.zipWithNext().all { (a, b) -> b.start > a.start }, "texts filled, increasing")
        check(segs.zipWithNext().all { (a, b) -> b.start - a.start <= 8 * 60_000 + 5000 }, "max length respected: ${segs.map { it.start / 1000 }}")
        val withCh = seg.segment(lines, listOf(0L to "المقدمة", 60_000L to "شرح الباب", 260_000L to "ما حكم التوسل؟"))
        check(withCh.size == 3 && withCh[2].ques && withCh[1].write!!.contains("عبادة"), "chapters segmentation $withCh")
        check(seg.hadithNumber("شرح الحديث رقم ١٢ في الباب") == 12 && seg.hadithNumber("كلام بلا رقم") == 0, "hadith number")
        check(seg.makeTitle("يعني نعم هذا الكلام الطويل جدا عن معنى التوحيد وأقسامه الثلاثة وما يتعلق بها من مسائل كثيرة") .let { !it.startsWith("يعني") && it.endsWith("…") }, "title cleaned: ${seg.makeTitle("يعني نعم هذا الكلام الطويل جدا عن معنى التوحيد وأقسامه الثلاثة وما يتعلق بها من مسائل كثيرة")}")
        // Gemini parse
        val gm = org.murabbie.ahlalhadeeth.data.GeminiClient
        val resp = "```json\n{\"topics\":[{\"start\":\"0:00:00\",\"title\":\"المقدمة\",\"question\":false,\"hadith\":0},{\"start\":\"0:02:15\",\"title\":\"ما حكم التوسل بالصالحين؟\",\"question\":true,\"hadith\":0,\"text\":\"نص\"},{\"start\":125.5,\"title\":\"بعد\",\"hadith\":3}]}\n```"
        val topics = gm.parseTopics(resp, allowText = true)
        val q = topics.first { it.ques }
        check(topics.size == 3 && q.write == "نص" && q.start == 135_000L && topics[1].start == 125_500L && topics[1].hnum == 3 && topics[0].start == 0L, "parseTopics sorted by time $topics")
        check(gm.parseTopics("[{\"start\":\"1:00\",\"title\":\"x\"}]", false).single().start == 60_000L, "parseTopics array form")
        check(gm.formatClock(3_725_000) == "1:02:05", "formatClock")
        println("ALL AUTO TESTS PASSED")
    }

    if (mode == "admins") {
        val ac = org.murabbie.ahlalhadeeth.data.AdminCrypto
        // ---------- التشفير ----------
        check(ac.normalizePin(" ١٢٣٤ 5678 ") == "12345678" && ac.normalizePin("۱۲-۳۴") == "1234", "normalize pin")
        val salt = ac.randomBytes(16)
        var t0 = System.currentTimeMillis()
        val k1 = ac.deriveKey("12345678", salt)
        println("pbkdf2 100k took ${System.currentTimeMillis() - t0} ms")
        check(k1.contentEquals(ac.deriveKey("١٢٣٤٥٦٧٨", salt)) && !k1.contentEquals(ac.deriveKey("12345679", salt)), "derive key stable / differs")
        // مطابقة PBKDF2 مع بايثون
        val pySalt = "c2FsdHNhbHRzYWx0c2FsdA==" // "saltsaltsaltsalt"
        val py = ProcessBuilder("python3", "-c", "import hashlib,base64;print(base64.b64encode(hashlib.pbkdf2_hmac('sha256',b'12345678',b'saltsaltsaltsalt',100000,32)).decode())").redirectErrorStream(true).start()
        val pyKey = py.inputStream.bufferedReader().readText().trim()
        check(ac.b64(ac.deriveKey("12345678", ac.unb64(pySalt))) == pyKey, "pbkdf2 matches python hashlib: $pyKey")
        val w = ac.wrap(k1, "manus\nsecret")
        check(ac.unwrap(k1, w) == "manus\nsecret" && ac.unwrap(ac.deriveKey("0000", salt), w) == null, "wrap/unwrap + wrong key rejected")
        // ---------- السجل ----------
        val reg = org.murabbie.ahlalhadeeth.data.AdminRegistry.create("admin", "المشرف العام", "1111 2222", "manus\npw")
        val text1 = reg.toText()
        val reg2 = org.murabbie.ahlalhadeeth.data.AdminRegistry.parse(text1)
        val auth = reg2.authenticate("admin", "١١١١٢٢٢٢")
        check(auth.role == "super" && auth.credential == "manus\npw" && auth.name == "المشرف العام", "super login with arabic digits")
        check(runCatching { reg2.authenticate("admin", "11112223") }.exceptionOrNull()?.message == "الرقم السري غير صحيح", "wrong pin message")
        check(runCatching { reg2.authenticate("ghost", "1234") }.exceptionOrNull()?.message == "لا يوجد مشرف بهذا الاسم", "unknown user message")
        reg2.addAdmin("أحمد", "ahmad", "87654321", auth.credential, "admin")
        check(runCatching { reg2.addAdmin("x", "ahmad", "1234", auth.credential, "admin") }.isFailure && runCatching { reg2.addAdmin("x", "admin", "1234", auth.credential, "admin") }.isFailure, "duplicate / super username rejected")
        check(runCatching { reg2.addAdmin("x", "a b", "1234", auth.credential, "admin") }.isFailure && runCatching { reg2.addAdmin("x", "y", "123", auth.credential, "admin") }.isFailure, "spaces / short pin rejected")
        val a1 = reg2.authenticate("ahmad", "87654321")
        check(a1.role == "admin" && a1.credential == "manus\npw" && reg2.verify("ahmad", a1.key), "admin login + verify")
        reg2.setActive("ahmad", false)
        check(!reg2.verify("ahmad", a1.key) && runCatching { reg2.authenticate("ahmad", "87654321") }.exceptionOrNull()?.message!!.contains("موقوف"), "disabled admin blocked")
        reg2.setActive("ahmad", true)
        reg2.setPin("ahmad", "5555", a1.credential)
        check(!reg2.verify("ahmad", a1.key) && reg2.authenticate("ahmad", "5555").credential == "manus\npw", "pin reset invalidates old key")
        // تغيير حساب الخادم: المشرف العام يبقى، والمشرفون يحتاجون أرقامًا جديدة
        reg2.changeCredential(auth.key, "newuser\nnewpw")
        check(reg2.authenticate("admin", "11112222").credential == "newuser\nnewpw", "super unwraps new credential")
        check(reg2.entries().single().needsPin && runCatching { reg2.authenticate("ahmad", "5555") }.exceptionOrNull()?.message!!.contains("رقم سري جديد"), "admins need new pin after credential change")
        reg2.setPin("ahmad", "6666", "newuser\nnewpw")
        check(reg2.authenticate("ahmad", "6666").credential == "newuser\nnewpw", "admin works after new pin")
        reg2.remove("ahmad")
        check(reg2.entries().isEmpty() && reg2.find("ahmad") == null, "remove admin")
        // ---------- التوافق مع أداة بايثون ----------
        val scratch = File("/tmp/claude-0/-home-claude/05351d93-98bc-5059-844e-6845587e9cea/scratchpad")
        val pyFile = File(scratch, "admins_py.json")
        val tool = "/home/claude/alathar/admins_tool.py"
        fun run(vararg a: String): String { val p = ProcessBuilder(listOf("python3", tool) + a).redirectErrorStream(true).start(); val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0, "python tool ${a.toList()} -> $out"); return out }
        run("init", pyFile.absolutePath, "--super-pin", "24681357", "--nas-user", "manus", "--nas-pass", "pw!")
        run("add", pyFile.absolutePath, "--user", "khalid", "--name", "خالد", "--pin", "1357", "--super-pin", "24681357")
        val regPy = org.murabbie.ahlalhadeeth.data.AdminRegistry.parse(pyFile.readText())
        check(regPy.authenticate("admin", "24681357").credential == "manus\npw!" && regPy.authenticate("khalid", "١٣٥٧").credential == "manus\npw!", "kotlin reads python-made file")
        val ktFile = File(scratch, "admins_kt.json")
        regPy.addAdmin("سعيد", "saeed", "9999", "manus\npw!", "admin")
        ktFile.writeText(regPy.toText())
        val out = run("check", ktFile.absolutePath, "--user", "saeed", "--pin", "9999")
        check(out.contains("pin ok: True") && out.contains("credential user: manus"), "python reads kotlin-made entry: $out")
        println("ALL ADMIN TESTS PASSED")
    }

    if (mode == "shared") {
        // ---------- يوتيوب: استخراج المعرّفات ----------
        val yt = org.murabbie.ahlalhadeeth.data.YouTube
        check(yt.extractId("https://www.youtube.com/watch?v=dQw4w9WgXcQ") == "dQw4w9WgXcQ", "watch url")
        check(yt.extractId("https://youtu.be/dQw4w9WgXcQ?t=30") == "dQw4w9WgXcQ", "short url")
        check(yt.extractId("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&list=PLx") == "dQw4w9WgXcQ", "mobile url with list")
        check(yt.extractId("https://www.youtube.com/live/dQw4w9WgXcQ") == "dQw4w9WgXcQ", "live url")
        check(yt.extractId("https://www.youtube.com/shorts/dQw4w9WgXcQ") == "dQw4w9WgXcQ", "shorts url")
        check(yt.extractId("https://example.org/a.mp3") == null && yt.extractId("") == null && yt.extractId("dQw4w9WgXcQ") == null, "non-youtube")
        check(yt.extractPlaylistId("https://www.youtube.com/playlist?list=PLabc_DEF-123") == "PLabc_DEF-123", "playlist id")
        check(yt.extractPlaylistId("https://youtu.be/dQw4w9WgXcQ") == null, "no playlist id")
        check(yt.isYouTubeUrl("https://www.youtube.com/playlist?list=PLabc") && !yt.isYouTubeUrl("https://x.org/v.mp4"), "isYouTubeUrl")
        val html = yt.playerHtml("dQw4w9WgXcQ", null, 65, true)
        check(html.contains("videoId:'dQw4w9WgXcQ'") && html.contains("start:65") && html.contains("fs:0"), "player html")
        val plHtml = yt.playerHtml(null, "PLabc", 0, false)
        check(plHtml.contains("listType: 'playlist', list: 'PLabc'") && plHtml.contains("videoId:undefined"), "playlist html")

        // ---------- الدمج المشترك: مشرف (A) ومستخدم (B) ----------
        val scratch = File("/tmp/claude-0/-home-claude/05351d93-98bc-5059-844e-6845587e9cea/scratchpad")
        val fa = File(scratch, "jvm_shared_a.db"); fa.delete()
        val fb = File(scratch, "jvm_shared_b.db"); fb.delete()
        val a = org.murabbie.ahlalhadeeth.data.UserContent(Db(fa.absolutePath, readOnly = false)); a.init()
        val b = org.murabbie.ahlalhadeeth.data.UserContent(Db(fb.absolutePath, readOnly = false)); b.init()
        val sid = a.addSheekh("الشيخ صالح الفوزان")
        val bid = a.addBook(sid, "شرح العقيدة الواسطية", "عقيدة")
        val c1 = a.addChapter(sid, bid, "الدرس الأول", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", true)
        val c2 = a.addChapter(sid, bid, "الدرس الثاني", "https://example.org/02.mp3", false)
        a.addSegment(c1, "مقدمة", 27_000L, "تفريغ المقدمة", false, 0, listOf(5))
        a.addSegment(c1, "ما حكم كذا؟", 600_000L, null, true, 0)
        check(a.pendingCounts() == (2 to 0), "admin pending before publish ${a.pendingCounts()}")
        val chA = a.chapter(c1)!!
        check(chA.isYouTube && chA.youtubeId == "dQw4w9WgXcQ" && chA.dirty && chA.origin == "local" && chA.key.startsWith("t-"), "youtube chapter model $chA")
        // «نشر» المشرف
        val pub1 = a.exportPack(null, "المحتوى المشترك", emptyList(), mapOf("id" to "shared-content", "pack_version" to 1))
        a.markAllClean(); a.markAllShared()
        check(a.pendingCounts() == (0 to 0) && a.chapter(c1)!!.origin == "shared" && !a.chapter(c1)!!.dirty, "admin clean after publish")
        // المستخدم يجلب
        val r1 = b.importPack(pub1, origin = "shared")
        check(r1.sheekhs == 1 && r1.books == 1 && r1.chapters == 2 && r1.segments == 2, "user first pull $r1")
        val chB = b.chapters(1, 1)
        check(chB.size == 2 && chB.all { it.origin == "shared" && !it.dirty } && chB[0].isYouTube, "user chapters shared & clean: $chB")
        check(b.pendingCounts() == (0 to 0), "user has nothing pending")
        // إعادة الجلب نفسه لا يكرر
        val r1b = b.importPack(pub1, origin = "shared")
        check(r1b.chapters == 0 && b.stats().second == 2 && b.segments(1).size == 2, "re-pull idempotent $r1b ${b.stats()}")
        // المشرف يعدّل درسًا ويحذف آخر ثم ينشر
        a.updateChapter(c1, "الدرس الأول (معدَّل)", "https://youtu.be/dQw4w9WgXcQ", true, "")
        a.addSegment(c1, "فائدة", 900_000L, "نص", false, 0)
        val keyC2 = a.chapter(c2)!!.key
        a.deleteChapter(c2)
        check(a.pendingCounts() == (1 to 1) && a.removedKeys() == listOf(keyC2), "admin pending after edit/delete ${a.pendingCounts()} ${a.removedKeys()}")
        val pub2 = a.exportPack(null, "المحتوى المشترك", emptyList(), mapOf("id" to "shared-content", "pack_version" to 2))
        check(org.json.JSONObject(pub2).getJSONArray("removed").getString(0) == keyC2, "tombstone exported")
        a.markAllClean(); a.markAllShared()
        val r2 = b.importPack(pub2, origin = "shared")
        val chB2 = b.chapters(1, 1)
        check(chB2.size == 1 && chB2[0].title == "الدرس الأول (معدَّل)" && b.segments(chB2[0].code.let { -it }).size == 3, "user got update + deletion: $chB2 ${r2}")
        check(b.pendingCounts() == (0 to 0), "deletion from server creates no local tombstone ${b.pendingCounts()}")
        // مشرف ثانٍ (C) لديه تعديل محلي معلّق على الدرس نفسه: يغلب حتى يُنشر
        val fc = File(scratch, "jvm_shared_c.db"); fc.delete()
        val c = org.murabbie.ahlalhadeeth.data.UserContent(Db(fc.absolutePath, readOnly = false)); c.init()
        c.importPack(pub1, origin = "shared")
        c.updateChapter(1, "عنوان المشرف الثاني", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", true, "")
        c.importPack(pub2, origin = "shared")
        check(c.chapter(1)!!.title == "عنوان المشرف الثاني" && c.chapter(1)!!.dirty, "local pending edit wins over server ${c.chapter(1)}")
        check(c.stats().second == 1, "server deletion applied at C too ${c.stats()}")
        // C يحذف درسًا مشتركًا ثم يجلب: الشاهد المحلي يمنع عودته حتى يُنشر
        val keyC1 = c.chapter(1)!!.key
        c.deleteChapter(1)
        c.importPack(pub2, origin = "shared")
        check(c.stats().second == 0 && c.removedKeys() == listOf(keyC1), "local tombstone blocks re-import ${c.stats()} ${c.removedKeys()}")
        val pub3 = c.exportPack(null, "المحتوى المشترك", listOf(keyC2), mapOf("id" to "shared-content", "pack_version" to 3))
        val removed3 = org.json.JSONObject(pub3).getJSONArray("removed")
        check(removed3.length() == 2 && (0 until 2).map { removed3.getString(it) }.toSet() == setOf(keyC1, keyC2), "removed union exported")
        b.importPack(pub3, origin = "shared")
        check(b.stats().second == 0, "user removed after C publishes ${b.stats()}")
        // ---------- ترقية درس يوتيوب محلي (غير منشور) إلى نسخته المنقولة إلى الخادم ----------
        val fd = File(scratch, "jvm_shared_d.db"); fd.delete()
        val d = org.murabbie.ahlalhadeeth.data.UserContent(Db(fd.absolutePath, readOnly = false)); d.init()
        val dsid = d.addSheekh("الشيخ صالح الفوزان"); val dbid = d.addBook(dsid, "شرح الواسطية")
        val dc = d.addChapter(dsid, dbid, "الدرس ١ (يوتيوب)", "https://youtu.be/dQw4w9WgXcQ", true)
        d.addSegment(dc, "موضع محلي", 5_000L, null, false, 0)
        check(d.chapter(dc)!!.isYouTube && d.chapter(dc)!!.captionSourceId == "dQw4w9WgXcQ" && !d.chapter(dc)!!.isOnServer, "local youtube chapter")
        val nasPack = """{"format":"ahl-alhadeeth-pack","version":1,"id":"shared-content","sheekhs":[{"name":"الشيخ صالح الفوزان","key":"s-x","series":[{"name":"شرح الواسطية","key":"b-x","tapes":[
            {"title":"الدرس ١","key":"t-yt-dQw4w9WgXcQ","media_url":"https://files.murabbie.org/fsdownload/AbCdEfGhI/001.m4a","kind":"audio","source_url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","segments":[]},
            {"title":"الدرس ٢","key":"t-yt-O5xeyoRL95U","media_url":"https://files.murabbie.org/fsdownload/ZzZzZzZzZ/002.m4a","kind":"audio","source_url":"https://www.youtube.com/watch?v=O5xeyoRL95U","segments":[{"start":"0:00:10","line":"مقدمة","write":"نص"}]}]}]}]}"""
        val rd = d.importPack(nasPack, origin = "shared")
        val up = d.chapter(dc)!!
        check(rd.chapters == 1 && up.isOnServer && !up.isYouTube && up.captionSourceId == "dQw4w9WgXcQ" && up.key == "t-yt-dQw4w9WgXcQ" && !up.dirty && up.origin == "shared", "local youtube chapter upgraded in place: $up")
        check(d.segments(dc).size == 1 && d.segments(dc)[0].line == "موضع محلي", "local index kept when server has none")
        check(d.stats().second == 2 && d.chapters(dsid, dbid).size == 2, "no duplicate chapter ${d.stats()}")
        val exp = d.exportPack(null, "x")
        check(exp.contains("\"source_url\": \"https://www.youtube.com/watch?v=dQw4w9WgXcQ\""), "source_url exported")
        println("ALL SHARED MERGE TESTS PASSED")

        // ---------- NAS: دخول مشرف ورفع ملف بالطريقة نفسها التي يستعملها التطبيق ----------
        val user = args.getOrElse(2) { "" }
        val pass = args.getOrElse(3) { "" }
        if (user.isNotBlank()) {
            val api = "https://files.murabbie.org/webapi"
            val client = NasHttp.client
            fun jsonGet(url: okhttp3.HttpUrl): org.json.JSONObject = client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "AhlAlhadeeth-Android/1.0").build()).execute().use { org.json.JSONObject(it.body?.string() ?: "{}") }
            val loginUrl = api.toHttpUrl().newBuilder().addPathSegment("auth.cgi")
                .addQueryParameter("api", "SYNO.API.Auth").addQueryParameter("version", "3").addQueryParameter("method", "login")
                .addQueryParameter("account", user).addQueryParameter("passwd", pass).addQueryParameter("session", "FileStation").addQueryParameter("format", "sid").build()
            val lo = jsonGet(loginUrl)
            check(lo.optBoolean("success"), "nas login $lo")
            val sidNas = lo.getJSONObject("data").getString("sid")
            val bad = jsonGet(api.toHttpUrl().newBuilder().addPathSegment("auth.cgi").addQueryParameter("api", "SYNO.API.Auth").addQueryParameter("version", "3").addQueryParameter("method", "login").addQueryParameter("account", user).addQueryParameter("passwd", pass + "x").addQueryParameter("session", "FileStation").addQueryParameter("format", "sid").build())
            check(!bad.optBoolean("success") && bad.getJSONObject("error").getInt("code") == 400, "wrong password rejected with 400: $bad")
            val listUrl = api.toHttpUrl().newBuilder().addPathSegment("entry.cgi").addQueryParameter("api", "SYNO.FileStation.List").addQueryParameter("version", "2").addQueryParameter("method", "list").addQueryParameter("folder_path", "/downloads/ahl-alhadeeth").addQueryParameter("_sid", sidNas).build()
            val li = jsonGet(listUrl)
            check(li.optBoolean("success"), "folder list ok")
            val names = li.getJSONObject("data").getJSONArray("files").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("name") } }
            check("shared-content.json" in names, "shared-content.json exists on NAS: $names")
            // رفع ملف اختبار (بالاسم shared-test.json) ثم التحقق ثم حذفه
            val payload = pub3.toByteArray(Charsets.UTF_8)
            val upUrl = api.toHttpUrl().newBuilder().addPathSegment("entry.cgi").addQueryParameter("api", "SYNO.FileStation.Upload").addQueryParameter("version", "2").addQueryParameter("method", "upload").addQueryParameter("_sid", sidNas).build()
            // OkHttp MultipartBody يضيف Content-Length لكل جزء فيرفضه DSM بالخطأ 401 — نتحقق من ذلك ثم نستعمل البناء اليدوي
            val okBody = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("path", "/downloads/ahl-alhadeeth").addFormDataPart("create_parents", "true").addFormDataPart("overwrite", "true")
                .addFormDataPart("file", "shared-test.json", payload.toRequestBody("application/octet-stream".toMediaType())).build()
            val upOk = client.newCall(okhttp3.Request.Builder().url(upUrl).post(okBody).build()).execute().use { org.json.JSONObject(it.body?.string() ?: "{}") }
            println("okhttp MultipartBody -> $upOk (expected 401)")
            val (boundary, raw) = NasHttp.multipart(listOf("path" to "/downloads/ahl-alhadeeth", "create_parents" to "true", "overwrite" to "true"), "file", "shared-test.json", payload)
            val body = raw.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())
            val up = client.newCall(okhttp3.Request.Builder().url(upUrl).post(body).build()).execute().use { org.json.JSONObject(it.body?.string() ?: "{}") }
            check(up.optBoolean("success"), "upload via manual multipart $up")
            val up2 = client.newCall(okhttp3.Request.Builder().url(upUrl).post(body).build()).execute().use { org.json.JSONObject(it.body?.string() ?: "{}") }
            check(up2.optBoolean("success"), "overwrite upload $up2")
            val info = jsonGet(api.toHttpUrl().newBuilder().addPathSegment("entry.cgi").addQueryParameter("api", "SYNO.FileStation.List").addQueryParameter("version", "2").addQueryParameter("method", "getinfo").addQueryParameter("path", "[\"/downloads/ahl-alhadeeth/shared-test.json\"]").addQueryParameter("additional", "[\"size\"]").addQueryParameter("_sid", sidNas).build())
            val size = info.getJSONObject("data").getJSONArray("files").getJSONObject(0).getJSONObject("additional").getLong("size")
            check(size == payload.size.toLong(), "uploaded size $size == ${payload.size}")
            val del = jsonGet(api.toHttpUrl().newBuilder().addPathSegment("entry.cgi").addQueryParameter("api", "SYNO.FileStation.Delete").addQueryParameter("version", "2").addQueryParameter("method", "delete").addQueryParameter("path", "/downloads/ahl-alhadeeth/shared-test.json").addQueryParameter("_sid", sidNas).build())
            check(del.optBoolean("success"), "test file deleted $del")
            println("ALL NAS ADMIN TESTS PASSED")
        }
    }
}
