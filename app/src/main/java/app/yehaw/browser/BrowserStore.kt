package app.yehaw.browser

import android.content.ContentValues
import android.content.Context
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
            tabs.take(4).forEachIndexed { index, tab ->
                if (isWebUrl(tab.url)) {
                    db.insert("session_tabs", null, ContentValues().apply {
                        put("position", index)
                        put("url", tab.url.take(4096))
                        put("title", tab.title.take(160))
                        put("scroll_y", tab.scrollY.coerceAtLeast(0))
                        put("is_active", if (index == activeIndex) 1 else 0)
                    })
                }
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
        val clipped = content.take(MAX_OFFLINE_TEXT_CHARS)
        val db = writableDatabase
        val id = db.insertWithOnConflict("offline_pages", null, ContentValues().apply {
            put("url", url.take(4096))
            put("title", title.take(512).ifBlank { url })
            put("content", clipped)
            put("saved_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("DELETE FROM offline_pages WHERE id NOT IN (SELECT id FROM offline_pages ORDER BY saved_at DESC LIMIT $MAX_OFFLINE_PAGES)")
        return id
    }

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

    private fun isWebUrl(url: String): Boolean =
        url.startsWith("https://", true) || url.startsWith("http://", true)

    companion object {
        const val MAX_OFFLINE_PAGES = 100
        const val MAX_OFFLINE_TEXT_CHARS = 250_000
    }
}
