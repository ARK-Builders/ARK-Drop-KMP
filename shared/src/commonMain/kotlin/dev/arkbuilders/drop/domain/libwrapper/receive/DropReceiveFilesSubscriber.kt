package dev.arkbuilders.drop.domain.libwrapper.receive

import kotlinx.coroutines.flow.StateFlow

interface DropReceiveFilesSubscriber {
    val progress: StateFlow<DropReceivingProgress>
}