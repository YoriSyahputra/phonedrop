package com.phonedrop.v2.domain.usecase

import com.phonedrop.v2.domain.repository.PhoneDropRepository

class SyncClipboardUseCase(private val repository: PhoneDropRepository) {
    suspend operator fun invoke(text: String) {
        if (text.isNotEmpty()) {
            repository.sendClipboard(text)
        }
    }
}
