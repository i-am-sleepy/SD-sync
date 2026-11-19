package com.sdsync.app.data

/** A configured source folder on internal storage. */
data class FolderConfig(
    val path: String,
    val enabled: Boolean = true
)

/** One completed/failed transfer, kept for the dashboard history. */
data class TransferRecord(
    val id: Long,
    val fileName: String,
    val destPath: String,
    val sizeBytes: Long,
    val timestamp: Long,
    val status: String
)

/** An interrupted (or failed) transfer persisted for later retry. */
data class PendingOp(
    val id: Long,
    val sourcePath: String,
    val relPath: String,
    val sizeBytes: Long,
    val attempts: Int
)
