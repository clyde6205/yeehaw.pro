package app.yehaw.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Bounded local browser history and bookmark storage. */
class BrowserStore(context: Context) : SQLiteOpenHelper(context, "yehaw-browser.db", null, 1) {
    data class Entry(val url: String, val title: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, title TEXT NOT NULL, visited_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_time_idx ON history(visited_at DESC)")
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL, saved_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }

    fun addHistory(url: String, title: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        writableDatabase.insert("history", null, ContentValues().apply {
            put("url", url.take(4096)); put("title", title.take(512)); put("visited_at", System.currentTimeMillis())
        })
        writableDatabase.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visited_at DESC LIMIT 1000)")
    }
    fun addBookmark(url: String, title: String) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("url", url.take(4096)); put("title", title.take(512)); put("saved_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
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
}
