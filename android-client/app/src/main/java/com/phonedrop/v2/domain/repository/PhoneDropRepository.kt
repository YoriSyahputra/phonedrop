package com.phonedrop.v2.domain.repository

import android.net.Uri
import com.phonedrop.v2.domain.model.ConnectionState
import com.phonedrop.v2.domain.model.MirrorMode
import com.phonedrop.v2.domain.model.TransferState
import kotlinx.coroutines.flow.StateFlow

interface PhoneDropRepository {
    val connectionState: StateFlow<ConnectionState>
    val transfers: StateFlow<Map<String, TransferState>>
    val latestClipboard: StateFlow<String>
    val mirrorMode: StateFlow<MirrorMode>

    suspend fun connectAuto(manualHost: String? = null, port: Int = 28200)
    suspend fun disconnect()
    suspend fun sendFile(uri: Uri)
    suspend fun sendClipboard(text: String)
    fun setMirrorMode(mode: MirrorMode)
}
