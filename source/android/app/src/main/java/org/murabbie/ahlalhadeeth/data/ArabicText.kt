package org.murabbie.ahlalhadeeth.data

/**
 * تطبيع النص العربي — يطابق دالة norm() في سكربت التحويل convert.py تمامًا،
 * لأن فهرس FTS بُني على النص المطبَّع.
 */
object ArabicText {

    private fun isRemovable(c: Char): Boolean {
        val code = c.code
        return (code in 0x0610..0x061A) || (code in 0x064B..0x065F) || code == 0x0670 ||
            (code in 0x06D6..0x06ED) || code == 0x0640 || (code in 0x200B..0x200F) ||
            (code in 0x202A..0x202E) || code == 0xFEFF
    }

    private fun mapChar(c: Char): Char = when (c) {
        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
        'ى', 'ی', 'ئ' -> 'ي'
        'ة' -> 'ه'
        'ؤ' -> 'و'
        'ک', 'گ' -> 'ك'
        in '٠'..'٩' -> ('0' + (c - '٠'))
        in '۰'..'۹' -> ('0' + (c - '۰'))
        else -> c.lowercaseChar()
    }

    fun normalize(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (isRemovable(c)) continue
            sb.append(mapChar(c))
        }
        return sb.toString()
    }

    /** تطبيع مع خريطة من موضع الحرف المطبَّع إلى موضعه في النص الأصلي (للتظليل). */
    fun normalizeWithMap(s: String): Pair<String, IntArray> {
        val sb = StringBuilder(s.length)
        val map = IntArray(s.length)
        var n = 0
        for (i in s.indices) {
            val c = s[i]
            if (isRemovable(c)) continue
            sb.append(mapChar(c))
            map[n++] = i
        }
        return sb.toString() to map.copyOf(n)
    }

    private val wordSplit = Regex("[\\s\\p{Punct}،؛؟«»\"'“”‘’()\\[\\]{}<>:.,!?؟…ـ/\\\\|~*+=_-]+")

    /** كلمات الاستعلام بعد التطبيع. */
    fun queryWords(query: String): List<String> =
        normalize(query).split(wordSplit).map { it.trim() }.filter { it.isNotEmpty() }

    /** الأرقام الهندية للعرض. */
    fun arabicDigits(n: Long): String {
        val s = n.toString()
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') ('٠' + (c - '0')) else c)
        return sb.toString()
    }

    fun arabicDigits(n: Int): String = arabicDigits(n.toLong())

    fun arabicDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') ('٠' + (c - '0')) else c)
        return sb.toString()
    }

    /** تنسيق الوقت بالميلي ثانية إلى س:دد:ثث بالأرقام الهندية. */
    fun formatTime(ms: Long): String {
        val total = if (ms < 0) 0 else ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val text = if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.US, "%d:%02d", m, s)
        return arabicDigits(text)
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) arabicDigits(String.format(java.util.Locale.US, "%.2f", mb / 1024)) + " غ.ب" else arabicDigits(String.format(java.util.Locale.US, "%.1f", mb)) + " م.ب"
    }

    /** السوابق العربية الشائعة التي تُلصق بالكلمة. */
    private val prefixes = listOf(
        "ال", "و", "ف", "ب", "ل", "ك", "س", "وال", "فال", "بال", "كال", "لل", "ول", "فل", "وب", "فب", "وك", "فك", "وس", "فس", "أ", "ا"
    )

    private fun ftsQuote(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    /**
     * بناء استعلام FTS5: لكل كلمة مجموعة (الكلمة أو الكلمة كبادئة أو مع السوابق كبادئة)،
     * وتُربط المجموعات بـ AND أو OR. [column] إما null (كل الأعمدة) أو line أو write.
     */
    fun buildFtsQuery(words: List<String>, matchAll: Boolean, column: String?, exactPhrase: Boolean = false): String? {
        if (words.isEmpty()) return null
        val body = if (exactPhrase) {
            ftsQuote(words.joinToString(" "))
        } else {
            words.joinToString(if (matchAll) " AND " else " OR ") { w ->
                val alts = LinkedHashSet<String>()
                alts.add(ftsQuote(w))
                alts.add(ftsQuote(w) + "*")
                for (p in prefixes) alts.add(ftsQuote(p + w) + "*")
                "(" + alts.joinToString(" OR ") + ")"
            }
        }
        return if (column == null) body else "$column : ($body)"
    }

    /** مواضع تطابق الكلمات في نص أصلي (بداية، نهاية) للتظليل. */
    fun findMatches(text: String, words: List<String>): List<IntRange> {
        if (text.isEmpty() || words.isEmpty()) return emptyList()
        val (norm, map) = normalizeWithMap(text)
        val ranges = ArrayList<IntRange>()
        for (w in words) {
            if (w.isEmpty()) continue
            var idx = norm.indexOf(w)
            var guard = 0
            while (idx >= 0 && guard < 500) {
                val start = map[idx]
                val endN = idx + w.length - 1
                val end = map[minOf(endN, map.size - 1)]
                ranges.add(start..end)
                idx = norm.indexOf(w, idx + w.length)
                guard++
            }
        }
        return ranges.sortedBy { it.first }
    }

    /** مقتطف حول أول تطابق. */
    fun snippet(text: String, words: List<String>, radius: Int = 90): String {
        if (text.length <= radius * 2) return text
        val matches = findMatches(text, words)
        if (matches.isEmpty()) return text.substring(0, radius * 2) + "…"
        val first = matches.first().first
        var start = maxOf(0, first - radius)
        var end = minOf(text.length, first + radius)
        // توسيع لحدود الكلمات
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        while (end < text.length && !text[end].isWhitespace()) end++
        val sb = StringBuilder()
        if (start > 0) sb.append("…")
        sb.append(text, start, end)
        if (end < text.length) sb.append("…")
        return sb.toString()
    }
}
