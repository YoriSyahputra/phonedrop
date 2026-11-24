package com.phonedrop.v2.domain.usecase

import com.phonedrop.v2.domain.repository.PhoneDropRepository

class ConnectSessionUseCase(private val repository: PhoneDropRepository) {
    suspend operator fun invoke(manualHost: String? = null, port: Int = 28200) {
        repository.connectAuto(manualHost, port)
    }
}
