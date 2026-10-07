package com.echo.livetranslate.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HistorySession(
    val id: Long,
    val startedAt: Long,
    val mode: String,
    val lineCount: Int,
    val preview: String
)

data class HistoryLine(
    val id: Long,
    val timestamp: Long,
    val original: String,
    val translated: String
)

/**
 * 字幕历史。每次开启字幕算一个会话，每句定稿的字幕存一行。
 *
 * 只存 final、不存中间结果——partial 每几百毫秒就变一次，存下来全是半句话。
 * 直接用 SQLite 而不是 Room：表就两张，省掉一整套注解处理器。
 */
class HistoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                mode TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE lines (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                ts INTEGER NOT NULL,
                original TEXT NOT NULL,
                translated TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_lines_session ON lines(session_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS lines")
        db.execSQL("DROP TABLE IF EXISTS sessions")
        onCreate(db)
    }

    // ---------- 写入（服务线程调用，同步即可，单条 insert 很快） ----------

    fun beginSession(mode: EngineMode): Long {
        val values = ContentValues().apply {
            put("started_at", System.currentTimeMillis())
            put("mode", mode.name)
        }
        return writableDatabase.insert("sessions", null, values)
    }

    fun appendLine(sessionId: Long, original: String, translated: String) {
        if (sessionId <= 0 || original.isBlank()) return
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("ts", System.currentTimeMillis())
            put("original", original)
            put("translated", translated)
        }
        runCatching { writableDatabase.insert("lines", null, values) }
    }

    /** 收尾时把一句字幕都没有的空会话删掉，历史列表里不留噪音。 */
    fun dropIfEmpty(sessionId: Long) {
        if (sessionId <= 0) return
        runCatching {
            writableDatabase.execSQL(
                "DELETE FROM sessions WHERE id = ? AND NOT EXISTS " +
                    "(SELECT 1 FROM lines WHERE session_id = ?)",
                arrayOf(sessionId, sessionId)
            )
        }
    }

    // ---------- 读取 ----------

    suspend fun sessions(): List<HistorySession> = withContext(Dispatchers.IO) {
        val sql = """
            SELECT s.id, s.started_at, s.mode,
                   (SELECT COUNT(*) FROM lines WHERE session_id = s.id) AS n,
                   (SELECT original FROM lines WHERE session_id = s.id ORDER BY id LIMIT 1) AS preview
            FROM sessions s
            ORDER BY s.id DESC
        """.trimIndent()
        readableDatabase.rawQuery(sql, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        HistorySession(
                            id = c.getLong(0),
                            startedAt = c.getLong(1),
                            mode = c.getString(2),
                            lineCount = c.getInt(3),
                            preview = c.getString(4) ?: ""
                        )
                    )
                }
            }
        }
    }

    suspend fun lines(sessionId: Long): List<HistoryLine> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT id, ts, original, translated FROM lines WHERE session_id = ? ORDER BY id",
            arrayOf(sessionId.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(HistoryLine(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3)))
                }
            }
        }
    }

    suspend fun search(keyword: String): List<HistoryLine> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val like = "%${keyword.trim()}%"
        readableDatabase.rawQuery(
            "SELECT id, ts, original, translated FROM lines " +
                "WHERE original LIKE ? OR translated LIKE ? ORDER BY id DESC LIMIT 300",
            arrayOf(like, like)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(HistoryLine(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3)))
                }
            }
        }
    }

    suspend fun deleteSession(sessionId: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete("lines", "session_id = ?", arrayOf(sessionId.toString()))
        writableDatabase.delete("sessions", "id = ?", arrayOf(sessionId.toString()))
        Unit
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        writableDatabase.delete("lines", null, null)
        writableDatabase.delete("sessions", null, null)
        Unit
    }

    companion object {
        private const val DB_NAME = "captions.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) {
                instance ?: HistoryStore(context).also { instance = it }
            }
    }
}
