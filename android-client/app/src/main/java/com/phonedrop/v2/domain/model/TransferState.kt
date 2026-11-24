package com.phonedrop.v2.domain.model

enum class TransferDirection { SEND, RECEIVE }

enum class TransferStatus { IN_PROGRESS, VERIFIED, FAILED }

data class TransferState(
    val transferIdHex: String,
    val fileName: String,
    val direction: TransferDirection,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val percent: Float,
    val speedMBps: Double,
    val chunkIndex: Int,
    val totalChunks: Int,
    val status: TransferStatus,
    val sha256Hex: String = "",
    val savedUri: String? = null,
    val errorMessage: String? = null
)

data class LocalReceivedFile(
    val uriString: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val modifiedMs: Long
)
