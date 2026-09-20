package org.murabbie.ahlalhadeeth.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * غلاف بسيط حول اتصال SQLite المدمج (androidx.sqlite bundled) يسلسل الوصول عبر Mutex.
 */
class Db(path: String, readOnly: Boolean) : AutoCloseable {

    private val driver = BundledSQLiteDriver()
    private val conn: SQLiteConnection = driver.open(path)
    private val mutex = Mutex()

    init {
        if (readOnly) {
            runCatching { conn.execSQL("PRAGMA query_only = 1") }
        }
        runCatching { conn.execSQL("PRAGMA cache_size = -16000") }
        runCatching { conn.execSQL("PRAGMA temp_store = MEMORY") }
    }

    /** تنفيذ استعلام وقراءة النتائج على Dispatchers.IO. */
    suspend fun <T> query(sql: String, vararg args: Any?, mapper: (Row) -> T): List<T> = withContext(Dispatchers.IO) {
        mutex.withLock {
            conn.prepare(sql).use { st ->
                bind(st, args)
                val out = ArrayList<T>()
                val row = Row(st)
                while (st.step()) out.add(mapper(row))
                out
            }
        }
    }

    suspend fun <T> queryOne(sql: String, vararg args: Any?, mapper: (Row) -> T): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            conn.prepare(sql).use { st ->
                bind(st, args)
                val row = Row(st)
                if (st.step()) mapper(row) else null
            }
        }
    }

    suspend fun count(sql: String, vararg args: Any?): Long = queryOne(sql, *args) { it.long(0) } ?: 0L

    suspend fun exec(sql: String, vararg args: Any?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            conn.prepare(sql).use { st ->
                bind(st, args)
                while (st.step()) { /* لا شيء */ }
            }
        }
    }

    private fun bind(st: SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> st.bindNull(idx)
                is Int -> st.bindLong(idx, a.toLong())
                is Long -> st.bindLong(idx, a)
                is Boolean -> st.bindLong(idx, if (a) 1 else 0)
                is Double -> st.bindDouble(idx, a)
                is Float -> st.bindDouble(idx, a.toDouble())
                is ByteArray -> st.bindBlob(idx, a)
                else -> st.bindText(idx, a.toString())
            }
        }
    }

    override fun close() {
        runCatching { conn.close() }
    }

    class Row(private val st: SQLiteStatement) {
        fun long(i: Int): Long = if (st.isNull(i)) 0L else st.getLong(i)
        fun int(i: Int): Int = long(i).toInt()
        fun text(i: Int): String = if (st.isNull(i)) "" else st.getText(i)
        fun textOrNull(i: Int): String? = if (st.isNull(i)) null else st.getText(i)
        fun bool(i: Int): Boolean = long(i) != 0L
        fun isNull(i: Int): Boolean = st.isNull(i)
    }
}
