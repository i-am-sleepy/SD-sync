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
        const val DB_VERSION = 1
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
        db.execSQL(
            "CREATE TABLE $T_PENDING (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "source_path TEXT NOT NULL," +
                "rel_path TEXT NOT NULL," +
                "size_bytes INTEGER NOT NULL," +
                "attempts INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- transfers (history) ----------

    fun insertTransfer(fileName: String, destPath: String, sizeBytes: Long, status: String): Long {
        val cv = ContentValues().apply {
            put("file_name", fileName)
            put("dest_path", destPath)
            put("size_bytes", sizeBytes)
            put("ts", System.currentTimeMillis())
            put("status", status)
        }
        return writableDatabase.insert(T_TRANSFERS, null, cv)
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
        }
        return writableDatabase.insert(T_PENDING, null, cv)
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
                        attempts = c.getInt(c.getColumnIndexOrThrow("attempts"))
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
}
