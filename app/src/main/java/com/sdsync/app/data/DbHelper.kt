package com.sdsync.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Lightweight SQLite store for transfer history and pending (interrupted) operations.
 * No ORM on purpose — keeps the build and runtime lean.
 */
class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "sdsync.db"
        // v3: repairs DBs where the pending table was never created (a broken
        // CREATE TABLE statement in an earlier build left a half-created DB).
        const val DB_VERSION = 3
        private const val T_TRANSFERS = "transfers"
        private const val T_PENDING = "pending"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $T_TRANSFERS (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "file_name TEXT NOT NULL," +
                "dest_path TEXT NOT NULL," +
                "size_bytes INTEGER NOT NULL," +
                "ts INTEGER NOT NULL," +
                "status TEXT NOT NULL)"
        )
        createPendingTable(db)
    }

    private fun createPendingTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $T_PENDING (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "source_path TEXT NOT NULL," +
                "rel_path TEXT NOT NULL," +
                "size_bytes INTEGER NOT NULL," +
                "attempts INTEGER NOT NULL DEFAULT 0," +
                "dest_name TEXT NOT NULL DEFAULT ''" +
                ")"
        )
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean {
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(name)
        ).use { c -> return c.moveToFirst() }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Self-heal: on broken DBs the pending table was never created.
        if (!tableExists(db, T_PENDING)) {
            createPendingTable(db)
            return
        }
        if (oldVersion < 2) {
            // v1 → v2: remember the chosen destination file name per pending op
            // so a retry overwrites the exact file instead of making a duplicate.
            db.execSQL("ALTER TABLE $T_PENDING ADD COLUMN dest_name TEXT NOT NULL DEFAULT ''")
        }
    }

    // ---------- transfers (history) ----------

    fun insertTransfer(fileName: String, destPath: String, sizeBytes: Long, status: String): Long {
        val cv = ContentValues().apply {
            put("file_name", fileName)
            put("dest_path", destPath)
            put("size_bytes", sizeBytes)
            put("ts", System.currentTimeMillis())
            put("status", status)
        }
        val id = writableDatabase.insert(T_TRANSFERS, null, cv)
        // Keep the history table bounded (last 500 rows) so it never grows forever.
        writableDatabase.execSQL(
            "DELETE FROM $T_TRANSFERS WHERE id NOT IN " +
                "(SELECT id FROM $T_TRANSFERS ORDER BY id DESC LIMIT 500)"
        )
        return id
    }

    fun recentTransfers(limit: Int = 50): List<TransferRecord> {
        val out = mutableListOf<TransferRecord>()
        readableDatabase.query(
            T_TRANSFERS, null, null, null, null, null, "id DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TransferRecord(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        fileName = c.getString(c.getColumnIndexOrThrow("file_name")),
                        destPath = c.getString(c.getColumnIndexOrThrow("dest_path")),
                        sizeBytes = c.getLong(c.getColumnIndexOrThrow("size_bytes")),
                        timestamp = c.getLong(c.getColumnIndexOrThrow("ts")),
                        status = c.getString(c.getColumnIndexOrThrow("status"))
                    )
                )
            }
        }
        return out
    }

    // ---------- pending ops ----------

    fun insertPending(sourcePath: String, relPath: String, sizeBytes: Long): Long {
        val cv = ContentValues().apply {
            put("source_path", sourcePath)
            put("rel_path", relPath)
            put("size_bytes", sizeBytes)
            put("attempts", 0)
            put("dest_name", "")
        }
        return writableDatabase.insert(T_PENDING, null, cv)
    }

    /** Record the actual destination file name chosen for a pending op. */
    fun setPendingDestName(id: Long, destName: String) {
        val cv = ContentValues().apply { put("dest_name", destName) }
        writableDatabase.update(T_PENDING, cv, "id=?", arrayOf(id.toString()))
    }

    fun deletePending(id: Long) {
        writableDatabase.delete(T_PENDING, "id=?", arrayOf(id.toString()))
    }

    fun getPending(): List<PendingOp> {
        val out = mutableListOf<PendingOp>()
        readableDatabase.query(T_PENDING, null, null, null, null, null, "id ASC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    PendingOp(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        sourcePath = c.getString(c.getColumnIndexOrThrow("source_path")),
                        relPath = c.getString(c.getColumnIndexOrThrow("rel_path")),
                        sizeBytes = c.getLong(c.getColumnIndexOrThrow("size_bytes")),
                        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
                        destName = c.getString(c.getColumnIndexOrThrow("dest_name")) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun bumpAttempts(id: Long, attempts: Int) {
        val cv = ContentValues().apply { put("attempts", attempts) }
        writableDatabase.update(T_PENDING, cv, "id=?", arrayOf(id.toString()))
    }

    /** Return the pending row id for [sourcePath], or null if none is recorded. */
    fun findPendingId(sourcePath: String): Long? {
        readableDatabase.query(
            T_PENDING, arrayOf("id"), "source_path=?", arrayOf(sourcePath),
            null, null, null, "1"
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    /** Return the full pending row for [sourcePath], or null if none is recorded. */
    fun findPending(sourcePath: String): PendingOp? {
        readableDatabase.query(
            T_PENDING, null, "source_path=?", arrayOf(sourcePath),
            null, null, null, "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            return PendingOp(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                sourcePath = c.getString(c.getColumnIndexOrThrow("source_path")),
                relPath = c.getString(c.getColumnIndexOrThrow("rel_path")),
                sizeBytes = c.getLong(c.getColumnIndexOrThrow("size_bytes")),
                attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
                destName = c.getString(c.getColumnIndexOrThrow("dest_name")) ?: ""
            )
        }
    }
}
