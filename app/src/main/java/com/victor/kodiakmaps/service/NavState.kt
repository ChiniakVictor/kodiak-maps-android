package com.victor.kodiakmaps.service

import kotlinx.coroutines.flow.MutableStateFlow

/** Navigate-to-pin state (one target at a time). In-app only: no background work. */
object NavState {
    val pin = MutableStateFlow<String?>(null)
    @Volatile var arrived = false
    fun start(id: String) { pin.value = id; arrived = false; AppLog.log("Navigate to pin $id") }
    fun stop() { pin.value = null; arrived = false }
}
