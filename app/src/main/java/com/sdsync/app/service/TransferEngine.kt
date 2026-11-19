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
        private const val MAX_ATTEMPTS = 5
    }

    sealed class Result {
        object Success : Result()
        object Skipped : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Copy [source] into [sdRoot] mirroring [relPath], verify, then delete the original. */
    fun transferFile(source: File, relPath: String, sdRoot: DocumentFile): Result {
        if (!source.exists() || !source.isFile) return Result.Skipped
        val parts = relPath.split("/").filter { it.isNotBlank() }
        if (parts.isEmpty()) return Result.Skipped

        var current = sdRoot
        for (i in 0 until parts.size - 1) {
            current = SdUtils.getOrCreateDir(current, parts[i])
                ?: return Result.Failed("cannot create dir: ${parts[i]}")
        }

        val destFile = uniqueDestination(current, parts.last())
        val free = SdUtils.sdFreeBytes()
        if (free >= 0 && source.length() > free) {
            return Result.Failed("insufficient space on SD")
        }

        return try {
            val written = copyWithVerify(source, destFile.uri)
            if (written != source.length()) {
                deleteQuietly(destFile)
                Result.Failed("verify mismatch: $written/${source.length()} bytes")
            } else {
                source.delete()
                Result.Success
            }
        } catch (e: Exception) {
            deleteQuietly(destFile)
            Result.Failed(e.message ?: "IO error")
        }
    }

    /** Attempt to recover interrupted transfers recorded in the pending table. */
    fun retryPending(sdRoot: DocumentFile) {
        for (op in db.getPending()) {
            if (op.attempts >= MAX_ATTEMPTS) {
                db.deletePending(op.id)
                continue
            }
            val source = File(op.sourcePath)
            when (val result = transferFile(source, op.relPath, sdRoot)) {
                is Result.Success -> {
                    db.deletePending(op.id)
                    db.insertTransfer(source.name, op.relPath, op.sizeBytes, "done")
                }
                is Result.Failed -> db.bumpAttempts(op.id, op.attempts + 1)
                Result.Skipped -> db.deletePending(op.id)
            }
        }
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
