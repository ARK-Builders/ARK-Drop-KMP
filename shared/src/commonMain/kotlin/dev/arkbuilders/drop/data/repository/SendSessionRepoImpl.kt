package dev.arkbuilders.drop.data.repository

import co.touchlab.kermit.Logger
import dev.arkbuilders.drop.data.helper.ResourcesHelper
import dev.arkbuilders.drop.domain.libwrapper.getDropApi
import dev.arkbuilders.drop.domain.model.DropFileInfo
import dev.arkbuilders.drop.domain.model.SendSession
import dev.arkbuilders.drop.domain.model.TransferStatus
import dev.arkbuilders.drop.domain.repository.SendSessionRepo
import dev.arkbuilders.drop.domain.repository.TransferSessionRepo
import dev.arkbuilders.drop.domain.usecase.SendFilesUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SendSessionRepoImpl(
    private val sendUseCase: SendFilesUseCase,
    private val resourcesHelper: ResourcesHelper,
    private val transferSessionRepository: TransferSessionRepo,
) : SendSessionRepo {
    // Keep references to active sessions here so file transfers continue even if the ViewModel dies
    private val activeSessions = mutableListOf<SendSession>()
    private val activeSessionsMutex = Mutex()
    private val cancelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun sendFiles(fileUris: List<String>): SendSession? =
        withContext(Dispatchers.IO) {
            cleanupFinishedSessions()

            sendUseCase.invoke(fileUris).fold(
                onSuccess = { bubble ->
                    val ticket = bubble.getTicket()
                    val confirmation = bubble.getConfirmation()
                    Logger.d("SendSessionRepo: Bubble received, ticket=$ticket, confirmation=$confirmation")

                    val subscriber =
                        getDropApi().createSendSubscriber().also { subscriber ->
                            Logger.d("SendSessionRepo: Subscribing to bubble")
                            bubble.subscribe(subscriber)
                        }

                    val session = SendSession(bubble, subscriber)
                    activeSessionsMutex.withLock {
                        activeSessions.add(session)
                        Logger.d("SendSessionRepo: Session created, isConnected=${bubble.isConnected()}, isFinished=${bubble.isFinished()}, activeSessions.size=${activeSessions.size}")
                    }
                    return@withContext session
                },
                onFailure = {
                    Logger.e("SendSessionRepo: Failed to create send bubble")
                    return@withContext null
                },
            )
        }

    override suspend fun recordSendCompletion(
        fileUris: List<String>,
        session: SendSession,
    ) {
        try {
            cleanupFinishedSessions()
            val progress = session.subscriber.progress.value
            val receiverName = progress.receiverName
            val receiverAvatar = progress.receiverAvatar

            val filesInfo =
                fileUris.map {
                    DropFileInfo(
                        name = resourcesHelper.getFileName(it) ?: "",
                        size = resourcesHelper.getFileSize(it),
                    )
                }

            transferSessionRepository.addSentTransfer(
                files = filesInfo,
                peerName = receiverName,
                peerAvatar = receiverAvatar,
                status = TransferStatus.COMPLETED,
            )
        } catch (e: Exception) {
            Logger.e("Error recording send completion ${e.message}")
        }
    }

    override fun cancelSend(session: SendSession) {
        cancelScope.launch {
            try {
                activeSessionsMutex.withLock {
                    val removed = activeSessions.remove(session)
                    Logger.d("SendSessionRepo: cancelSend - session removed=$removed, activeSessions.size=${activeSessions.size}")
                }
                session.bubble.unsubscribe(session.subscriber)
                Logger.d("SendSessionRepo: cancelSend - about to call bubble.cancel()")
                session.bubble.cancel()
                Logger.d("SendSessionRepo: cancelSend - bubble.cancel() completed")
            } catch (e: Throwable) {
                Logger.e("SendSessionRepo: Error cancelling send ${e.message}")
            }
        }
    }

    private suspend fun cleanupFinishedSessions() =
        activeSessionsMutex.withLock {
            val before = activeSessions.size
            activeSessions.removeAll { session ->
                val finished = session.bubble.isFinished()
                Logger.d("SendSessionRepo: cleanupFinishedSessions - isFinished=$finished, ticket=${session.bubble.getTicket()}")
                finished
            }
            val removed = before - activeSessions.size
            if (removed > 0) Logger.d("SendSessionRepo: cleanupFinishedSessions - removed $removed finished sessions")
        }
}
