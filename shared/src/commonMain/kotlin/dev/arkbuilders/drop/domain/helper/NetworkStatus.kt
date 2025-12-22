package dev.arkbuilders.drop.domain.helper

import kotlinx.coroutines.flow.StateFlow

interface NetworkStatus {
    fun isOnline() = onlineStatus.value

    val onlineStatus: StateFlow<Boolean>
}