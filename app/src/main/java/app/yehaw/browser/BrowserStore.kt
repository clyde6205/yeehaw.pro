package app.yehaw.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Bounded local browser history, bookmarks, session state and user-saved offline reading snapshots. */
class BrowserStore(context: Context) : SQLiteOpenHelper(context, "yehaw-browser.db", null, 2) {
    data class Entry(val url: String, val title: String)
    data class SessionTab(val url: String, val title: String, val scrollY: Int)
    data class SessionSnapshot(val tabs: List<SessionTab>, val activeIndex: Int)
    data class OfflinePage(val id: Long, val url: String, val title: String, val content: String, val savedAt: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, title TEXT NOT NULL, visited_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_time_idx ON history(visited_at DESC)")
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL, saved_at INTEGER NOT NULL)")
        createContinuityTables(db)
    }

    private fun createContinuityTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS session_tabs (position INTEGER PRIMARY KEY, url TEXT NOT NULL, title TEXT NOT NULL, scroll_y INTEGER NOT NULL, is_active INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS offline_pages (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL, content TEXT NOT NULL, saved_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS offline_pages_saved_idx ON offline_pages(saved_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createContinuityTables(db)
    }

    fun addHistory(url: String, title: String) {
        if (!isWebUrl(url)) return
        writableDatabase.insert("history", null, ContentValues().apply {
            put("url", url.take(4096)); put("title", title.take(512)); put("visited_at", System.currentTimeMillis())
        })
        writableDatabase.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visited_at DESC LIMIT 1000)")
    }

    fun addBookmark(url: String, title: String) {
        if (!isWebUrl(url)) return
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("url", url.take(4096)); put("title", title.take(512)); put("saved_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun saveSession(tabs: List<SessionTab>, activeIndex: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("session_tabs", null, null)
            // Filter invalid entries before applying the four-tab limit. Otherwise a rejected
            // file:// tab near the front could consume a slot and silently discard a real tab.
            val validTabs = tabs.mapIndexedNotNull { originalIndex, tab ->
                if (isWebUrl(tab.url)) originalIndex to tab else null
            }.take(4)
            val activeStoredIndex = validTabs.indexOfFirst { it.first == activeIndex }
            validTabs.forEachIndexed { storedIndex, (_, tab) ->
                db.insert("session_tabs", null, ContentValues().apply {
                    put("position", storedIndex)
                    put("url", tab.url.take(4096))
                    put("title", tab.title.take(160))
                    put("scroll_y", tab.scrollY.coerceAtLeast(0))
                    put("is_active", if (storedIndex == activeStoredIndex) 1 else 0)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadSession(): SessionSnapshot? {
        val tabs = mutableListOf<SessionTab>()
        var activeIndex = 0
        readableDatabase.query("session_tabs", arrayOf("position", "url", "title", "scroll_y", "is_active"),
            null, null, null, null, "position ASC", "4").use { cursor ->
            while (cursor.moveToNext()) {
                val url = cursor.getString(1)
                if (!isWebUrl(url)) continue
                val position = tabs.size
                tabs.add(SessionTab(url, cursor.getString(2), cursor.getInt(3).coerceAtLeast(0)))
                if (cursor.getInt(4) == 1) activeIndex = position
            }
        }
        return if (tabs.isEmpty()) null else SessionSnapshot(tabs, activeIndex.coerceIn(0, tabs.lastIndex))
    }

    fun clearSession() {
        writableDatabase.delete("session_tabs", null, null)
    }

    fun saveOfflinePage(url: String, title: String, content: String): Long {
        require(isWebUrl(url)) { "Only HTTP/HTTPS pages can be saved offline." }
        require(content.isNotBlank()) { "This page has no readable text to save." }
        val clipped = truncateUtf8ToBytes(content, MAX_OFFLINE_TEXT_BYTES_PER_PAGE)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertWithOnConflict("offline_pages", null, ContentValues().apply {
                put("url", url.take(4096))
                put("title", title.take(512).ifBlank { url })
                put("content", clipped)
                put("saved_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            require(id != -1L) { "Could not save this offline page." }

            // Enforce both a page-count ceiling and a byte budget. Keep the newest copies first.
            db.execSQL("DELETE FROM offline_pages WHERE id NOT IN (SELECT id FROM offline_pages ORDER BY saved_at DESC LIMIT $MAX_OFFLINE_PAGES)")
            val removeIds = mutableListOf<Long>()
            var retainedBytes = 0L
            db.rawQuery(
                "SELECT id, length(CAST(content AS BLOB)) FROM offline_pages ORDER BY saved_at DESC, id DESC",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val rowId = cursor.getLong(0)
                    val rowBytes = cursor.getLong(1)
                    if (retainedBytes + rowBytes > MAX_OFFLINE_TOTAL_BYTES) {
                        removeIds.add(rowId)
                    } else {
                        retainedBytes += rowBytes
                    }
                }
            }
            removeIds.forEach { db.delete("offline_pages", "id=?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    /** Actual UTF-8 payload bytes currently held by the offline reading library. */
    fun offlineStorageBytes(): Long =
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(length(CAST(content AS BLOB))), 0) FROM offline_pages",
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    fun offlinePages(): List<OfflinePage> {
        val rows = mutableListOf<OfflinePage>()
        readableDatabase.query("offline_pages", arrayOf("id", "url", "title", "content", "saved_at"),
            null, null, null, null, "saved_at DESC", MAX_OFFLINE_PAGES.toString()).use { cursor ->
            while (cursor.moveToNext()) rows.add(OfflinePage(
                cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4)
            ))
        }
        return rows
    }

    fun offlinePage(id: Long): OfflinePage? =
        readableDatabase.query("offline_pages", arrayOf("id", "url", "title", "content", "saved_at"),
            "id=?", arrayOf(id.toString()), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst()) null else OfflinePage(
                cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4)
            )
        }

    fun deleteOfflinePage(id: Long) {
        writableDatabase.delete("offline_pages", "id=?", arrayOf(id.toString()))
    }

    fun clearOfflinePages() {
        writableDatabase.delete("offline_pages", null, null)
    }

    fun history(): List<Entry> = read("history", "visited_at")
    fun bookmarks(): List<Entry> = read("bookmarks", "saved_at")

    private fun read(table: String, order: String): List<Entry> {
        val rows = mutableListOf<Entry>()
        readableDatabase.query(table, arrayOf("url", "title"), null, null, null, null, "$order DESC", "200").use { cursor ->
            while (cursor.moveToNext()) rows.add(Entry(cursor.getString(0), cursor.getString(1)))
        }
        return rows
    }

    fun clearHistory() { writableDatabase.delete("history", null, null) }
    fun clearBookmarks() { writableDatabase.delete("bookmarks", null, null) }

    private fun isWebUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return (uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)) && !uri.host.isNullOrBlank()
    }

    companion object {
        const val MAX_OFFLINE_PAGES = 100
        const val MAX_OFFLINE_TEXT_CHARS = 250_000
        const val MAX_OFFLINE_TEXT_BYTES_PER_PAGE = 256 * 1024
        const val MAX_OFFLINE_TOTAL_BYTES = 8 * 1024 * 1024

        /** Exact UTF-8 bytes in the text copy delivered locally from offline storage. */
        fun utf8ByteCount(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

        /** Truncate at a Unicode code-point boundary so stored UTF-8 never exceeds the byte budget. */
        fun truncateUtf8ToBytes(text: String, maxBytes: Int): String {
            require(maxBytes >= 0)
            val result = StringBuilder()
            var index = 0
            var usedBytes = 0
            while (index < text.length) {
                val codePoint = Character.codePointAt(text, index)
                val codePointChars = Character.charCount(codePoint)
                val codePointBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
                if (usedBytes + codePointBytes > maxBytes) break
                result.appendCodePoint(codePoint)
                usedBytes += codePointBytes
                index += codePointChars
            }
            return result.toString()
        }
    }
}

/**
 * Opens the store; if the database file is damaged it is moved aside (never deleted) and a fresh
 * store is created, so the browser always starts. The moved file stays on disk for recovery.
 */
object BrowserStoreRepair {
    fun open(context: Context): BrowserStore {
        var store = BrowserStore(context)
        try {
            store.readableDatabase.rawQuery("PRAGMA quick_check", null).use { c ->
                if (c.moveToFirst() && c.getString(0) != "ok") throw android.database.sqlite.SQLiteException("quick_check failed")
            }
            return store
        } catch (_: Exception) {
            try { store.close() } catch (_: Exception) {}
            val file = context.getDatabasePath("yehaw-browser.db")
            val aside = java.io.File(file.parentFile, "yehaw-browser.db.corrupt-" + System.currentTimeMillis())
            if (file.exists()) file.renameTo(aside)
            java.io.File(file.path + "-wal").delete(); java.io.File(file.path + "-shm").delete(); java.io.File(file.path + "-journal").delete()
            app.yehaw.browser.core.Guardian.noteRepair(context)
            store = BrowserStore(context)
            return store
        }
    }
}
