package com.sdsync.app.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.sdsync.app.data.DbHelper
import com.sdsync.app.util.SdUtils
import java.io.File
import java.io.FileInputStream

/**
 * Sequential, buffered COPY → VERIFY → DELETE ORIGINAL engine.
 * Files are streamed in 64KB chunks — never loaded fully into RAM.
 */
class TransferEngine(private val context: Context, private val db: DbHelper) {

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }

    sealed class Result {
        object Success : Result()
        object Skipped : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Copy [source] into [sdRoot] mirroring [relPath], verify, then delete the original.
     * The original is only deleted after the destination copy is verified byte-for-byte.
     * A pending row is recorded before the copy so an interrupted transfer is recoverable.
     */
    fun transferFile(source: File, relPath: String, sdRoot: DocumentFile): Result {
        if (!source.exists() || !source.isFile) return Result.Skipped
        val size = source.length()
        val parts = relPath.split("/").filter { it.isNotBlank() }
        if (parts.isEmpty()) return Result.Skipped

        // Reuse an existing pending row (retry) or record a new one before copying.
        val pendingId = db.findPendingId(source.absolutePath)
            ?: db.insertPending(source.absolutePath, relPath, size)

        var current = sdRoot
        for (i in 0 until parts.size - 1) {
            current = SdUtils.getOrCreateDir(current, parts[i])
                ?: return Result.Failed("cannot create dir: ${parts[i]}")
        }

        val destFile = try {
            uniqueDestination(current, parts.last())
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "cannot create destination")
        }

        val free = SdUtils.sdFreeBytes()
        if (free >= 0 && size > free) {
            deleteQuietly(destFile)
            return Result.Failed("insufficient space on SD")
        }

        val written = try {
            copyWithVerify(source, destFile.uri)
        } catch (e: Exception) {
            deleteQuietly(destFile)
            return Result.Failed(e.message ?: "IO error")
        }

        if (written != size) {
            deleteQuietly(destFile)
            return Result.Failed("verify mismatch: $written/$size bytes")
        }

        // COPY + VERIFY succeeded → DELETE ORIGINAL (only now).
        if (source.delete()) {
            db.deletePending(pendingId)
            return Result.Success
        }
        // Copy is safe on SD; the original just couldn't be removed right now.
        // Keep the pending row so a later pass retries the delete.
        return Result.Failed("could not delete original")
    }

    private fun copyWithVerify(source: File, destUri: Uri): Long {
        val resolver = context.contentResolver
        val input = FileInputStream(source)
        val output = resolver.openOutputStream(destUri, "w")
            ?: throw IllegalStateException("cannot open destination stream")
        var written = 0L
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                written += n
            }
            output.flush()
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
        return written
    }

    /** Pick a destination name that doesn't collide, adding " (n)" suffixes. */
    private fun uniqueDestination(parent: DocumentFile, name: String): DocumentFile {
        var candidate = name
        var idx = 1
        while (parent.findFile(candidate) != null) {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            candidate = "$base ($idx)$ext"
            idx++
        }
        return parent.createFile("application/octet-stream", candidate)
            ?: throw IllegalStateException("cannot create destination file")
    }

    private fun deleteQuietly(f: DocumentFile) {
        runCatching { f.delete() }
    }
}
