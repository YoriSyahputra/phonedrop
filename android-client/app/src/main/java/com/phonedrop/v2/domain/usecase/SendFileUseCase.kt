package com.phonedrop.v2.domain.usecase

import android.net.Uri
import com.phonedrop.v2.domain.repository.PhoneDropRepository

class SendFileUseCase(private val repository: PhoneDropRepository) {
    suspend operator fun invoke(uri: Uri) {
        repository.sendFile(uri)
    }
}
