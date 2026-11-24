package com.phonedrop.v2.domain.model

/**
 * Represents the active transport state and link mode of the PhoneDrop v2 TCP tunnel.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connecting(val endpoint: String) : ConnectionState()
    data class UsbConnected(val endpoint: String = "127.0.0.1:28200") : ConnectionState()
    data class WifiConnected(val endpoint: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    val isConnected: Boolean
        get() = this is UsbConnected || this is WifiConnected
}
