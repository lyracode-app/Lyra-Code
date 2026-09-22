package com.yukisoffd.lyracode.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class AuditEntry(val id: Long, val createdAt: Long, val kind: String, val title: String, val detail: String)
data class AuditSection(val name: String, val length: Long)

/** Lists contain previews only. Full payloads live in bounded rows, outside CursorWindow limits. */
class AuditLogStore(context: Context, inMemory: Boolean = false) : SQLiteOpenHelper(
    context.applicationContext, if (inMemory) null else "lyra_audit.db", null, 2,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL)")
        createSections(db)
    }

    private fun createSections(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE audit_sections (log_id INTEGER NOT NULL, name TEXT NOT NULL, part INTEGER NOT NULL, content TEXT NOT NULL, PRIMARY KEY(log_id, name, part))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSections(db)
    }

    fun add(kind: String, title: String, detail: String): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("audit_log", null, ContentValues().apply {
                put("created_at", System.currentTimeMillis())
                put("kind", kind.take(48))
                put("title", title.take(256))
                put("detail", detail.take(240))
            })
            writeSection(db, id, "detail", detail, false)
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    fun section(id: Long, name: String, text: String, append: Boolean = false) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // A deleted in-flight request must never recreate its logs.
            if (db.rawQuery("SELECT id FROM audit_log WHERE id=?", arrayOf(id.toString())).use { it.moveToFirst() }) {
                writeSection(db, id, name, text, append)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun writeSection(db: SQLiteDatabase, id: Long, name: String, text: String, append: Boolean) {
        val args = arrayOf(id.toString(), name)
        if (!append) db.delete("audit_sections", "log_id=? AND name=?", args)
        var part = db.rawQuery("SELECT COALESCE(MAX(part), -1)+1 FROM audit_sections WHERE log_id=? AND name=?", args)
            .use { it.moveToFirst(); it.getInt(0) }
        var offset = 0
        do {
            var end = minOf(offset + 16_384, text.length)
            if (end < text.length && end > offset && text[end - 1].isHighSurrogate()) end--
            db.insertOrThrow("audit_sections", null, ContentValues().apply {
                put("log_id", id); put("name", name); put("part", part++)
                put("content", text.substring(offset, end))
            })
            offset = end
        } while (offset < text.length)
    }

    fun recent(limit: Int = 100, kind: String? = null, query: String = ""): List<AuditEntry> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (kind != null) { clauses += "kind=?"; args += kind }
        if (query.isNotBlank()) {
            clauses += "(instr(lower(title), lower(?))>0 OR instr(lower(detail), lower(?))>0 OR EXISTS (SELECT 1 FROM audit_sections s WHERE s.log_id=audit_log.id AND instr(lower(s.content), lower(?))>0))"
            repeat(3) { args += query.trim() }
        }
        return readableDatabase.query("audit_log", arrayOf("id", "created_at", "kind", "title", "substr(detail,1,240)"),
            clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "), args.toTypedArray(), null, null, "id DESC", limit.coerceAtLeast(1).toString())
            .use { cursor -> buildList {
                while (cursor.moveToNext()) add(AuditEntry(cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3), cursor.getString(4)))
            } }
    }

    fun kinds(): List<String> = readableDatabase.rawQuery("SELECT DISTINCT kind FROM audit_log ORDER BY kind", null)
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun sections(id: Long): List<AuditSection> = readableDatabase.rawQuery(
        "SELECT name, SUM(length(content)) FROM audit_sections WHERE log_id=? GROUP BY name ORDER BY name", arrayOf(id.toString()),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(AuditSection(cursor.getString(0), cursor.getLong(1)))
        if (isEmpty()) add(AuditSection("detail", 0))
    } }

    fun readSection(id: Long, name: String): String {
        val text = readableDatabase.rawQuery("SELECT content FROM audit_sections WHERE log_id=? AND name=? ORDER BY part", arrayOf(id.toString(), name))
            .use { cursor -> buildString { while (cursor.moveToNext()) append(cursor.getString(0)) } }
        if (text.isNotEmpty() || name != "detail") return text
        return readableDatabase.rawQuery("SELECT detail FROM audit_log WHERE id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getString(0) else "" }
    }

    fun delete(id: Long) = deleteWhere(id)
    fun clear() = deleteWhere(null)
    private fun deleteWhere(id: Long?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val args = id?.let { arrayOf(it.toString()) }
            db.delete("audit_sections", id?.let { "log_id=?" }, args)
            db.delete("audit_log", id?.let { "id=?" }, args)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
