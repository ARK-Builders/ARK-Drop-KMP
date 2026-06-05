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
import dev.arkbuilders.drop.instrumentation.FirebaseReporter
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
    private val firebaseReporter: FirebaseReporter,
) : SendSessionRepo {
    // Keep references to active sessions here so file transfers continue even if the ViewModel dies
    private val activeSessions = mutableListOf<SendSession>()
    private val activeSessionsMutex = Mutex()
    private val cancelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun sendFiles(fileUris: List<String>): SendSession? =
        withContext(Dispatchers.IO) {
            val cleaned = cleanupFinishedSessions()
            firebaseReporter.log(
                "SendSessionRepo: sendFiles called - " +
                    "files: ${fileUris.size}, cleaned sessions: $cleaned",
            )

            sendUseCase.invoke(fileUris).fold(
                onSuccess = { bubble ->
                    val subscriber =
                        getDropApi().createSendSubscriber().also { subscriber ->
                            bubble.subscribe(subscriber)
                        }

                    val session = SendSession(bubble, subscriber)
                    firebaseReporter.log(
                        "SendSessionRepo: session created - " +
                            "activeSessions: ${activeSessions.size + 1}",
                    )

                    activeSessionsMutex.withLock {
                        activeSessions.add(session)
                    }
                    return@withContext session
                },
                onFailure = { error ->
                    firebaseReporter.recordError(
                        "SendSessionRepo: sendFiles failed - ${error.message}",
                        error,
                    )
                    return@withContext null
                },
            )
        }

    override suspend fun recordSendCompletion(
        fileUris: List<String>,
        session: SendSession,
    ) {
        try {
            firebaseReporter.log(
                "SendSessionRepo: recording send completion for ${fileUris.size} files",
            )
            cleanupFinishedSessions()
            val progress = session.subscriber.progress.value
            val receiverName = progress.receiverName
            val receiverAvatar = progress.receiverAvatar
            val totalSent = progress.sent
            val totalRemaining = progress.remaining

            firebaseReporter.log(
                "SendSessionRepo: transfer completed - " +
                    "sent: $totalSent, remaining: $totalRemaining",
            )

            val filesInfo =
                fileUris.map {
                    val name = resourcesHelper.getFileName(it) ?: ""
                    val size = resourcesHelper.getFileSize(it)
                    DropFileInfo(
                        name = name,
                        size = size,
                    )
                }

            transferSessionRepository.addSentTransfer(
                files = filesInfo,
                peerName = receiverName,
                peerAvatar = receiverAvatar,
                status = TransferStatus.COMPLETED,
            )

            firebaseReporter.log("SendSessionRepo: send completion recorded successfully")
        } catch (e: Exception) {
            firebaseReporter.recordError(
                "SendSessionRepo: error recording send completion - ${e.message}",
                e,
            )
            Logger.e("Error recording send completion ${e.message}")
        }
    }

    override fun cancelSend(session: SendSession) {
        firebaseReporter.log("SendSessionRepo: cancelling send")

        cancelScope.launch {
            try {
                activeSessionsMutex.withLock {
                    activeSessions.remove(session)
                }
                session.bubble.unsubscribe(session.subscriber)
                session.bubble.cancel()
                firebaseReporter.log("SendSessionRepo: send cancelled successfully")
            } catch (e: Throwable) {
                firebaseReporter.recordError(
                    "SendSessionRepo: error during cancel - ${e::class.simpleName}",
                    e,
                )
                Logger.e("Error cancelling send ${e.message}")
            }
        }
    }

    private suspend fun cleanupFinishedSessions() =
        activeSessionsMutex.withLock {
            val before = activeSessions.size
            activeSessions.removeAll { it.bubble.isFinished() }
            val removed = before - activeSessions.size
            if (removed > 0) {
                firebaseReporter.log(
                    "SendSessionRepo: cleaned $removed finished sessions, " +
                        "${activeSessions.size} remaining",
                )
            }
            removed
        }
}
