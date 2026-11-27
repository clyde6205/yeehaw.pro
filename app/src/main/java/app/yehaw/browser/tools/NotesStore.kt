package app.yehaw.browser.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Offline notes in their own small SQLite file (nothing else in the browser reads or writes it). Opened only when the Notes
 * tool is, and never touched by browsing, clearing browsing data, or the private window.
 */
class NotesStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, 1), java.io.Closeable {
    data class Note(val id: Long, val text: String, val updatedAt: Long) {
        /** First non-blank line, used as the list title. */
        val title: String get() = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX notes_updated ON notes(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Saves a note; returns its id. An empty note is never stored (and an existing one that becomes empty is removed). */
    @Synchronized fun save(id: Long?, text: String, now: Long = System.currentTimeMillis()): Long? {
        val body = text.take(MAX_CHARS)
        if (body.isBlank()) { if (id != null) delete(id); return null }
        val db = writableDatabase
        if (id != null) {
            val cv = ContentValues().apply { put("body", body); put("updated_at", now) }
            if (db.update("notes", cv, "id=?", arrayOf(id.toString())) > 0) return id
        }
        return db.insert("notes", null, ContentValues().apply { put("body", body); put("updated_at", now) })
    }

    @Synchronized fun get(id: Long): Note? = readableDatabase.rawQuery("SELECT id, body, updated_at FROM notes WHERE id=?", arrayOf(id.toString())).use {
        if (it.moveToFirst()) Note(it.getLong(0), it.getString(1), it.getLong(2)) else null
    }

    /** Newest first. [query] filters case-insensitively on the body; LIKE wildcards in the query are matched literally. */
    @Synchronized fun list(query: String = ""): List<Note> {
        val q = query.trim()
        val out = ArrayList<Note>()
        val cur = if (q.isEmpty()) readableDatabase.rawQuery("SELECT id, body, updated_at FROM notes ORDER BY updated_at DESC, id DESC LIMIT $MAX_NOTES", null)
        else readableDatabase.rawQuery(
            "SELECT id, body, updated_at FROM notes WHERE body LIKE ? ESCAPE '\\' ORDER BY updated_at DESC, id DESC LIMIT $MAX_NOTES",
            arrayOf("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"))
        cur.use { while (it.moveToNext()) out.add(Note(it.getLong(0), it.getString(1), it.getLong(2))) }
        return out
    }

    @Synchronized fun delete(id: Long) { writableDatabase.delete("notes", "id=?", arrayOf(id.toString())) }

    @Synchronized fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM notes", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    companion object {
        const val DB_NAME = "yeehaw_notes.db"
        const val MAX_CHARS = 50_000
        const val MAX_NOTES = 500
    }
}
