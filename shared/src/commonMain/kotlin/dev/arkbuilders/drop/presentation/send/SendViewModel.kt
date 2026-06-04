@file:OptIn(ExperimentalTime::class)

package dev.arkbuilders.drop.presentation.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import dev.arkbuilders.drop.data.helper.NetworkStatus
import dev.arkbuilders.drop.data.helper.ResourcesHelper
import dev.arkbuilders.drop.domain.model.SendSession
import dev.arkbuilders.drop.domain.repository.SendSessionRepo
import dev.arkbuilders.drop.instrumentation.AnalyticsEvents
import dev.arkbuilders.drop.instrumentation.AnalyticsReporter
import dev.arkbuilders.drop.instrumentation.FirebaseReporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.viewmodel.container
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class SendViewModel(
    private val resourcesHelper: ResourcesHelper,
    private val networkStatus: NetworkStatus,
    private val sendSessionRepo: SendSessionRepo,
    private val firebaseReporter: FirebaseReporter,
    private val analyticsReporter: AnalyticsReporter,
) : ViewModel(), ContainerHost<SendScreenState, SendScreenEffect> {
    override val container: Container<SendScreenState, SendScreenEffect> =
        container(SendScreenState.FileSelection())

    private var sendStartedAtMs: Long? = null
    private var sendReceiverConnectedLogged = false

    init {
        firebaseReporter.log("SendViewModel: initialized")
    }

    fun onAddFiles() =
        intent {
            firebaseReporter.log("SendViewModel: user tapped add files")
            analyticsReporter.logEvent(AnalyticsEvents.SEND_FILE_PICKER_OPENED)
            postSideEffect(SendScreenEffect.LaunchFilePicker)
        }

    fun onFilesAdded(newFiles: List<String>) =
        intent {
            val s = state
            if (s is SendScreenState.FileSelection) {
                firebaseReporter.log(
                    "SendViewModel: files added - new: ${newFiles.size}, current: ${s.files.size}",
                )

                val validated = resourcesHelper.validateUris(newFiles)
                val allFiles = s.files + validated.first
                val canStartTransfer = allFiles.isNotEmpty() && networkStatus.isOnline()

                val size = allFiles.sumOf { resourcesHelper.getFileSize(it) }

                analyticsReporter.logEvent(
                    AnalyticsEvents.SEND_FILES_SELECTED,
                    mapOf(
                        AnalyticsEvents.PARAM_FILE_COUNT to allFiles.size,
                        AnalyticsEvents.PARAM_SKIPPED_COUNT to validated.second,
                        AnalyticsEvents.PARAM_TOTAL_BYTES to size,
                        AnalyticsEvents.PARAM_TOTAL_SIZE_BUCKET to AnalyticsEvents.sizeBucket(size),
                    ),
                )

                firebaseReporter.log(
                    "SendViewModel: files validated - " +
                        "valid: ${validated.first.size}, " +
                        "skipped: ${validated.second}, " +
                        "total: ${allFiles.size}, " +
                        "totalSize: $size bytes, " +
                        "canStart: $canStartTransfer",
                )

                reduce {
                    s.copy(files = allFiles, size = size, canStartTransfer = canStartTransfer)
                }
            }
        }

    fun onFileRemove(file: String) =
        intent {
            val s = state
            if (s is SendScreenState.FileSelection) {
                val newFiles = s.files - file
                val size = newFiles.sumOf { resourcesHelper.getFileSize(it) }
                analyticsReporter.logEvent(
                    AnalyticsEvents.SEND_FILE_REMOVED,
                    mapOf(
                        AnalyticsEvents.PARAM_REMAINING_FILE_COUNT to newFiles.size,
                        AnalyticsEvents.PARAM_TOTAL_BYTES to size,
                        AnalyticsEvents.PARAM_TOTAL_SIZE_BUCKET to AnalyticsEvents.sizeBucket(size),
                    ),
                )
                firebaseReporter.log(
                    "SendViewModel: file removed - " +
                        "remaining: ${newFiles.size}, " +
                        "remainingSize: $size bytes",
                )
                reduce {
                    s.copy(files = newFiles, size = size)
                }
            }
        }

    fun onStartTransfer() =
        intent {
            val s = state
            if (s !is SendScreenState.FileSelection) {
                firebaseReporter.log(
                    "SendViewModel: start transfer ignored - wrong state: ${s::class.simpleName}",
                )
                return@intent
            }

            firebaseReporter.setCustomKey("send_file_count", s.files.size.toString())
            firebaseReporter.setCustomKey("send_total_bytes", s.size.toString())
            firebaseReporter.log(
                "SendViewModel: starting transfer - " +
                    "files: ${s.files.size}, " +
                    "totalSize: ${s.size} bytes, " +
                    "online: ${networkStatus.isOnline()}",
            )
            sendStartedAtMs = Clock.System.now().toEpochMilliseconds()
            sendReceiverConnectedLogged = false
            analyticsReporter.logEvent(
                AnalyticsEvents.SEND_STARTED,
                mapOf(
                    AnalyticsEvents.PARAM_FILE_COUNT to s.files.size,
                    AnalyticsEvents.PARAM_TOTAL_BYTES to s.size,
                    AnalyticsEvents.PARAM_TOTAL_SIZE_BUCKET to AnalyticsEvents.sizeBucket(s.size),
                ),
            )

            reduce {
                SendScreenState.GeneratingQR(s.files)
            }

            val session = sendSessionRepo.sendFiles(s.files)
            if (session == null) {
                firebaseReporter.recordError(
                    "SendViewModel: session creation failed - could not initialize transfer",
                )
                analyticsReporter.logEvent(
                    AnalyticsEvents.SEND_FAILED,
                    mapOf(
                        AnalyticsEvents.PARAM_PHASE to "initialization",
                        AnalyticsEvents.PARAM_ERROR_TYPE to "session_creation_failed",
                    ),
                )
                reduce {
                    SendScreenState.Error(
                        files = s.files,
                        error = SendException.TransferInitializationFailed,
                    )
                }
                return@intent
            }
            val ticket = session.bubble.getTicket()
            val confirmation = session.bubble.getConfirmation()
            firebaseReporter.log("SendViewModel: session created")

            if (ticket.isEmpty()) {
                firebaseReporter.recordError(
                    "SendViewModel: empty transfer code received from bridge",
                )
                analyticsReporter.logEvent(
                    AnalyticsEvents.SEND_FAILED,
                    mapOf(
                        AnalyticsEvents.PARAM_PHASE to "initialization",
                        AnalyticsEvents.PARAM_ERROR_TYPE to "empty_ticket",
                    ),
                )
                reduce {
                    SendScreenState.Error(
                        session = session,
                        files = s.files,
                        error = SendException.TransferInitializationFailed,
                    )
                }
                return@intent
            }
            val copyString = "${session.bubble.getTicket()} ${session.bubble.getConfirmation()}"

            firebaseReporter.log("SendViewModel: generating QR code")
            val qrBitmap = resourcesHelper.generateQRCode(ticket, confirmation)
            if (qrBitmap == null) {
                firebaseReporter.recordError("SendViewModel: QR generation failed")
                analyticsReporter.logEvent(
                    AnalyticsEvents.SEND_FAILED,
                    mapOf(
                        AnalyticsEvents.PARAM_PHASE to "qr_generation",
                        AnalyticsEvents.PARAM_ERROR_TYPE to "qr_generation_failed",
                    ),
                )
                reduce {
                    SendScreenState.Error(
                        session = session,
                        files = s.files,
                        error = SendException.QRGenerationFailed,
                    )
                }
                return@intent
            }
            firebaseReporter.log(
                "SendViewModel: QR code generated successfully - size: ${qrBitmap.size} bytes",
            )
            analyticsReporter.logEvent(
                AnalyticsEvents.SEND_QR_READY,
                mapOf(
                    AnalyticsEvents.PARAM_FILE_COUNT to s.files.size,
                    AnalyticsEvents.PARAM_TOTAL_BYTES to s.size,
                    AnalyticsEvents.PARAM_TOTAL_SIZE_BUCKET to AnalyticsEvents.sizeBucket(s.size),
                ),
            )

            listenToSendProgress(session)
            monitorTransferCompletion(session)
            firebaseReporter.log("SendViewModel: waiting for receiver")
            reduce {
                SendScreenState.WaitingForReceiver(
                    session = session,
                    files = s.files,
                    qrBitmap = qrBitmap,
                    copyString = copyString,
                )
            }
        }

    fun onCancelTransfer() =
        intent {
            val s = state
            var cancelPhase: String? = null
            val session =
                when (s) {
                    is SendScreenState.WaitingForReceiver -> {
                        cancelPhase = "waiting_for_receiver"
                        firebaseReporter.log("SendViewModel: user cancelled while waiting")
                        s.session
                    }

                    is SendScreenState.Transfer -> {
                        cancelPhase = "transfer"
                        firebaseReporter.log("SendViewModel: user cancelled during transfer")
                        s.session
                    }

                    else -> {
                        firebaseReporter.log(
                            "SendViewModel: cancel ignored - state: ${s::class.simpleName}",
                        )
                        null
                    }
                }
            cancelPhase?.let {
                analyticsReporter.logEvent(
                    AnalyticsEvents.SEND_CANCELLED,
                    mapOf(AnalyticsEvents.PARAM_PHASE to it),
                )
            }
            session?.let { sendSessionRepo.cancelSend(it) }
            firebaseReporter.setCustomKey("send_file_count", "0")
            firebaseReporter.setCustomKey("send_total_bytes", "0")
            postSideEffect(SendScreenEffect.NavigateBack)
        }

    fun onCancelQrGeneration() =
        intent {
            val s = state
            val files =
                if (s is SendScreenState.GeneratingQR) {
                    firebaseReporter.log("SendViewModel: user cancelled QR generation")
                    analyticsReporter.logEvent(
                        AnalyticsEvents.SEND_CANCELLED,
                        mapOf(AnalyticsEvents.PARAM_PHASE to "qr_generation"),
                    )
                    s.files
                } else {
                    emptyList()
                }

            reduce {
                SendScreenState.FileSelection(files)
            }
        }

    fun onComplete() =
        intent {
            val s = state
            if (s is SendScreenState.Transfer) {
                val progress = s.session.subscriber.progress.value
                firebaseReporter.log(
                    "SendViewModel: transfer completed - " +
                        "files: ${s.files.size}, " +
                        "sent: ${progress.sent}, " +
                        "remaining: ${progress.remaining}",
                )
                firebaseReporter.setCustomKey(
                    "send_completed_at",
                    Clock.System.now().toEpochMilliseconds().toString(),
                )
                analyticsReporter.logEvent(
                    AnalyticsEvents.SEND_COMPLETED,
                    mapOf(
                        AnalyticsEvents.PARAM_FILE_COUNT to s.files.size,
                        AnalyticsEvents.PARAM_TOTAL_BYTES to s.totalBytes,
                        AnalyticsEvents.PARAM_TOTAL_SIZE_BUCKET to
                            AnalyticsEvents.sizeBucket(s.totalBytes),
                        AnalyticsEvents.PARAM_DURATION_MS to
                            AnalyticsEvents.durationSince(
                                sendStartedAtMs,
                                Clock.System.now().toEpochMilliseconds(),
                            ),
                    ),
                )

                sendSessionRepo.recordSendCompletion(
                    s.files,
                    s.session,
                )
                reduce {
                    SendScreenState.Complete(session = s.session, files = s.files)
                }
            }
        }

    fun onDone() =
        intent {
            val s = state
            if (s is SendScreenState.Complete) {
                firebaseReporter.log("SendViewModel: user done - ${s.files.size} files sent")
                sendSessionRepo.cancelSend(s.session)
            }
            firebaseReporter.setCustomKey("send_file_count", "0")
            firebaseReporter.setCustomKey("send_total_bytes", "0")
            postSideEffect(SendScreenEffect.NavigateBack)
        }

    fun onSendMore() =
        intent {
            val s = state
            if (s is SendScreenState.Complete) {
                firebaseReporter.log("SendViewModel: user wants to send more files")
                analyticsReporter.logEvent(AnalyticsEvents.SEND_MORE_SELECTED)
                sendSessionRepo.cancelSend(s.session)
            }
            firebaseReporter.setCustomKey("send_file_count", "0")
            firebaseReporter.setCustomKey("send_total_bytes", "0")
            reduce {
                SendScreenState.FileSelection()
            }
        }

    fun onErrorRetry() =
        intent {
            val s = state
            firebaseReporter.log(
                "SendViewModel: user retrying after error - " +
                    "errorType: ${(s as? SendScreenState.Error)?.error}",
            )

            if (s is SendScreenState.Error) {
                s.session?.let {
                    sendSessionRepo.cancelSend(it)
                }
            }
            val files =
                when (s) {
                    is SendScreenState.Error -> s.files
                    else -> emptyList()
                } ?: emptyList()

            val validated = resourcesHelper.validateUris(files).first
            val canStartTransfer = validated.isNotEmpty() && networkStatus.isOnline()
            val size = validated.sumOf { resourcesHelper.getFileSize(it) }

            firebaseReporter.log(
                "SendViewModel: retry with " +
                    "${validated.size} valid files, " +
                    "canStart: $canStartTransfer",
            )

            reduce {
                SendScreenState.FileSelection(
                    files = validated,
                    size = size,
                    canStartTransfer = canStartTransfer,
                )
            }
        }

    fun onErrorDismiss() =
        intent {
            val s = state
            if (s is SendScreenState.Error) {
                firebaseReporter.log("SendViewModel: user dismissed error - errorType: ${s.error}")
                s.session?.let {
                    sendSessionRepo.cancelSend(it)
                }
            }
            firebaseReporter.setCustomKey("send_file_count", "0")
            firebaseReporter.setCustomKey("send_total_bytes", "0")
            postSideEffect(SendScreenEffect.NavigateBack)
        }

    private fun listenToSendProgress(session: SendSession) {
        val chunkBytes = 10L * 1024L * 1024L
        var lastSent = -1L
        var lastLoggedChunk = -1L

        session.subscriber.progress.onEach { progress ->
            intent {
                val s = state
                val files =
                    when (s) {
                        is SendScreenState.Transfer -> s.files
                        is SendScreenState.WaitingForReceiver -> s.files
                        else -> return@intent
                    }

                try {
                    val wasConnected = (s as? SendScreenState.Transfer)?.isConnected ?: false
                    val justConnected = progress.isConnected && !wasConnected

                    if (progress.isConnected.not())
                        return@intent

                    if (justConnected) {
                        val totalBytes = progress.sent.toLong() + progress.remaining.toLong()
                        firebaseReporter.log(
                            "SendViewModel: receiver connected - " +
                                "fileCount: ${files.size}",
                        )
                        if (!sendReceiverConnectedLogged) {
                            sendReceiverConnectedLogged = true
                            analyticsReporter.logEvent(
                                AnalyticsEvents.SEND_RECEIVER_CONNECTED,
                                mapOf(
                                    AnalyticsEvents.PARAM_FILE_COUNT to files.size,
                                    AnalyticsEvents.PARAM_TOTAL_BYTES to totalBytes,
                                ),
                            )
                        }
                    }

                    val sent = progress.sent.toLong()
                    val fileReset = sent < lastSent
                    lastSent = sent

                    val currentChunk = sent / chunkBytes
                    if (fileReset || currentChunk > lastLoggedChunk) {
                        lastLoggedChunk = currentChunk
                        firebaseReporter.log(
                            "SendViewModel: transfer progress - " +
                                "sent: ${progress.sent}, " +
                                "remaining: ${progress.remaining}",
                        )
                    }

                    val transfer =
                        SendScreenState.Transfer(
                            session = session,
                            files = files,
                            isConnected = progress.isConnected,
                            receiverName = progress.receiverName,
                            receiverAvatar = progress.receiverAvatar,
                            currentFileName = progress.fileName,
                            bytesTransferred = progress.sent.toLong(),
                            totalBytes = (progress.sent + progress.remaining).toLong(),
                            transferSpeedBps = 0L,
                            estimatedTimeRemaining = 0L,
                        )

                    reduce {
                        transfer
                    }
                } catch (e: Throwable) {
                    firebaseReporter.recordError(
                        "SendViewModel: transfer progress error - " +
                            "${e::class.simpleName}: ${e.message}",
                        e,
                    )
                    analyticsReporter.logEvent(
                        AnalyticsEvents.SEND_FAILED,
                        mapOf(
                            AnalyticsEvents.PARAM_PHASE to "transfer",
                            AnalyticsEvents.PARAM_ERROR_TYPE to
                                (e::class.simpleName ?: "unknown_error"),
                        ),
                    )
                    Logger.e("Transfer interrupted: ${e::class.simpleName} ${e.message}")
                    reduce {
                        SendScreenState.Error(
                            session = session,
                            files = files,
                            error = SendException.TransferInterrupted,
                        )
                    }
                }
            }
        }.launchIn(viewModelScope)
    }

    private fun monitorTransferCompletion(session: SendSession) {
        viewModelScope.launch {
            firebaseReporter.log("SendViewModel: monitoring transfer completion")

            while (coroutineContext.isActive) {
                val isFinished = session.bubble.isFinished()
                if (isFinished) {
                    firebaseReporter.log("SendViewModel: transfer finished signal detected")
                    onComplete()
                    break
                }
                delay(500)
            }
        }
    }
}
