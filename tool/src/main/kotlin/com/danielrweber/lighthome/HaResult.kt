package com.danielrweber.lighthome

internal sealed interface HaCallResult<out T> {
    data class Ok<T>(val value: T) : HaCallResult<T>
    data class Http(val status: Int) : HaCallResult<Nothing>
    data class Redirect(val status: Int) : HaCallResult<Nothing>
    data object NotHomeAssistant : HaCallResult<Nothing>
    data object Unreadable : HaCallResult<Nothing>
    data class Transport(val kind: TransportKind) : HaCallResult<Nothing>
}

internal enum class TransportKind {
    Unreachable,
    Tls,
}
