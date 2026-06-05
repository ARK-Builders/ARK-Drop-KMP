@file:OptIn(ExperimentalTime::class)

package dev.arkbuilders.drop.presentation.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import dev.arkbuilders.drop.data.helper.PermissionsHelper
import dev.arkbuilders.drop.domain.model.ReceiveSession
import dev.arkbuilders.drop.domain.repository.ReceiveSessionRepo
import dev.arkbuilders.drop.instrumentation.AnalyticsEvents
import dev.arkbuilders.drop.instrumentation.AnalyticsReporter
import dev.arkbuilders.drop.instrumentation.FirebaseReporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.blockingIntent
import org.orbitmvi.orbit.viewmodel.container
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class ReceiveViewModel(
    private val receiveSessionRepo: ReceiveSessionRepo,
    private val permissionsHelper: PermissionsHelper,
    private val firebaseReporter: FirebaseReporter,
    private val analyticsReporter: AnalyticsReporter,
) : ViewModel(), ContainerHost<ReceiveScreenState, ReceiveScreenEffect> {
    override val container: Container<ReceiveScreenState, ReceiveScreenEffect> =
        container(ReceiveScreenState.Initial(false))

    private var receiveStartedAtMs: Long? = null
    private var receiveSenderConnectedLogged = false
    private var receiveSource = AnalyticsEvents.SOURCE_UNKNOWN

    init {
        firebaseReporter.log("ReceiveViewModel: initialized")
        intent {
            val granted = permissionsHelper.isCameraGranted()
            firebaseReporter.log("ReceiveViewModel: initial camera permission granted=$granted")
            reduce {
                ReceiveScreenState.Initial(granted)
            }
        }
    }

    fun onRequestCameraPermission() =
        intent {
            firebaseReporter.log("ReceiveViewModel: requesting camera permission")
            reduce {
                ReceiveScreenState.RequestingPermission
            }
            postSideEffect(ReceiveScreenEffect.RequestCameraPermission)
        }

    fun onEnterManually() =
        intent {
            firebaseReporter.log("ReceiveViewModel: entering manual input mode")
            receiveSource = AnalyticsEvents.SOURCE_MANUAL
            analyticsReporter.logEvent(AnalyticsEvents.RECEIVE_MANUAL_INPUT_STARTED)
            reduce {
                ReceiveScreenState.ManualInput(inputText = "", inputError = null)
            }
        }

    fun onStartScanning() =
        intent {
            firebaseReporter.log("ReceiveViewModel: starting QR scanner")
            receiveSource = AnalyticsEvents.SOURCE_QR
            analyticsReporter.logEvent(AnalyticsEvents.RECEIVE_SCAN_STARTED)
            reduce {
                ReceiveScreenState.Scanning
            }
        }

    fun onStopScanning() =
        intent {
            firebaseReporter.log("ReceiveViewModel: stopping QR scanner")
            reduce {
                ReceiveScreenState.Initial(permissionsHelper.isCameraGranted())
            }
        }

    fun onError(error: ReceiveError) =
        intent {
            firebaseReporter.recordError("ReceiveViewModel: error state error=${error.name}", null)
            analyticsReporter.logEvent(
                AnalyticsEvents.RECEIVE_FAILED,
                mapOf(
                    AnalyticsEvents.PARAM_PHASE to "ui",
                    AnalyticsEvents.PARAM_ERROR_TYPE to error.name.lowercase(),
                ),
            )
            reduce {
                ReceiveScreenState.Error(error = error)
            }
        }

    fun onAccept() =
        intent {
            try {
                val s = state
                if (s !is ReceiveScreenState.QRCodeScanned) {
                    firebaseReporter.log(
                        "ReceiveViewModel: onAccept ignored - not in QRCodeScanned state",
                    )
                    return@intent
                }
                val ticket = s.ticket
                val confirmation = s.confirmation

                firebaseReporter.log("ReceiveViewModel: accept triggered")
                receiveStartedAtMs = Clock.System.now().toEpochMilliseconds()
                receiveSenderConnectedLogged = false
                analyticsReporter.logEvent(
                    AnalyticsEvents.RECEIVE_STARTED,
                    mapOf(AnalyticsEvents.PARAM_SOURCE to receiveSource),
                )

                reduce {
                    ReceiveScreenState.Connecting
                }

                firebaseReporter.log("ReceiveViewModel: calling receiveFiles")
                val session =
                    receiveSessionRepo.receiveFiles(ticket, confirmation)
                if (session != null) {
                    firebaseReporter.log(
                        "ReceiveViewModel: " +
                            "session created successfully, transitioning to Receiving",
                    )
                    reduce {
                        ReceiveScreenState.Receiving(
                            session,
                            session.subscriber.progress.value,
                        )
                    }
                    listenToProgress(session)
                } else {
                    firebaseReporter.recordError(
                        "ReceiveViewModel: receiveFiles returned null session",
                        null,
                    )
                    analyticsReporter.logEvent(
                        AnalyticsEvents.RECEIVE_FAILED,
                        mapOf(
                            AnalyticsEvents.PARAM_PHASE to "connection",
                            AnalyticsEvents.PARAM_ERROR_TYPE to "session_creation_failed",
                        ),
                    )
                    reduce {
                        ReceiveScreenState.Error(error = ReceiveError.ConnectionFailed)
                    }
                }
            } catch (e: Exception) {
                firebaseReporter.recordError("ReceiveViewModel: onAccept exception", e)
                analyticsReporter.logEvent(
                    AnalyticsEvents.RECEIVE_FAILED,
                    mapOf(
                        AnalyticsEvents.PARAM_PHASE to "connection",
                        AnalyticsEvents.PARAM_ERROR_TYPE to
                            (e::class.simpleName ?: "unknown_error"),
                    ),
                )
                val error =
                    when {
                        e.message?.contains(
                            "network",
                            ignoreCase = true,
                        ) == true -> ReceiveError.NetworkError

                        else -> ReceiveError.ConnectionFailed
                    }

                reduce {
                    ReceiveScreenState.Error(error = error)
                }
            }
        }

    fun onCameraPermissionGranted(isGranted: Boolean) =
        intent {
            firebaseReporter.log("ReceiveViewModel: camera permission result granted=$isGranted")
            analyticsReporter.logEvent(
                AnalyticsEvents.CAMERA_PERMISSION_RESULT,
                mapOf(AnalyticsEvents.PARAM_GRANTED to isGranted),
            )
            val state =
                if (isGranted) {
                    ReceiveScreenState.Scanning
                } else {
                    ReceiveScreenState.Error(error = ReceiveError.CameraPermissionDenied)
                }
            reduce {
                state
            }
        }

    fun onScanAgain() =
        intent {
            firebaseReporter.log("ReceiveViewModel: scan again")
            val state =
                if (permissionsHelper.isCameraGranted()) {
                    ReceiveScreenState.Scanning
                } else {
                    ReceiveScreenState.ManualInput(inputText = "", inputError = null)
                }
            reduce {
                state
            }
        }

    fun onReceiveMore() =
        intent {
            firebaseReporter.log("ReceiveViewModel: receive more")
            val s = state
            if (s is ReceiveScreenState.Success) {
                analyticsReporter.logEvent(AnalyticsEvents.RECEIVE_MORE_SELECTED)
                receiveSessionRepo.cancelReceive(s.session)
            }
            reduce {
                ReceiveScreenState.Initial(permissionsHelper.isCameraGranted())
            }
        }

    fun onDone() =
        intent {
            firebaseReporter.log("ReceiveViewModel: done - navigating back")
            val s = state
            if (s is ReceiveScreenState.Success) {
                receiveSessionRepo.cancelReceive(s.session)
            }
            postSideEffect(ReceiveScreenEffect.NavigateBack)
        }

    fun onPasteFromClipboard(clipText: String?) =
        intent {
            val s = state
            if (s !is ReceiveScreenState.ManualInput)
                return@intent

            if (!clipText.isNullOrEmpty()) {
                firebaseReporter.log(
                    "ReceiveViewModel: pasting from clipboard length=${clipText.length}",
                )
                analyticsReporter.logEvent(
                    AnalyticsEvents.RECEIVE_CLIPBOARD_PASTED,
                    mapOf(AnalyticsEvents.PARAM_HAS_TEXT to true),
                )
                reduce {
                    s.copy(
                        inputText = clipText,
                        inputError = null,
                    )
                }
            }
            if (clipText.isNullOrEmpty()) {
                analyticsReporter.logEvent(
                    AnalyticsEvents.RECEIVE_CLIPBOARD_PASTED,
                    mapOf(AnalyticsEvents.PARAM_HAS_TEXT to false),
                )
            }
        }

    fun onErrorRetry() =
        intent {
            firebaseReporter.log("ReceiveViewModel: error retry")
            reduce {
                ReceiveScreenState.Initial(permissionsHelper.isCameraGranted())
            }
        }

    fun onErrorDismiss() =
        intent {
            val s = state
            if (s is ReceiveScreenState.Error) {
                firebaseReporter.log("ReceiveViewModel: error dismissed error=${s.error.name}")
                s.session?.let {
                    receiveSessionRepo.cancelReceive(it)
                }
            }
            postSideEffect(ReceiveScreenEffect.NavigateBack)
        }

    fun onQrCodeScanned(
        ticket: String,
        confirmation: UByte,
    ) = intent {
        firebaseReporter.log("ReceiveViewModel: QR code scanned")
        receiveSource = AnalyticsEvents.SOURCE_QR
        analyticsReporter.logEvent(
            AnalyticsEvents.RECEIVE_CODE_ENTERED,
            mapOf(AnalyticsEvents.PARAM_SOURCE to AnalyticsEvents.SOURCE_QR),
        )
        reduce {
            ReceiveScreenState.QRCodeScanned(ticket, confirmation)
        }
    }

    fun onManualInputChanged(input: String) =
        blockingIntent {
            val s = state
            if (s !is ReceiveScreenState.ManualInput)
                return@blockingIntent

            reduce {
                s.copy(
                    inputText = input,
                    inputError = null,
                )
            }
        }

    fun onCancelReceiving() =
        intent {
            firebaseReporter.log("ReceiveViewModel: cancelling receiving")
            val s = state
            if (s is ReceiveScreenState.Receiving) {
                analyticsReporter.logEvent(
                    AnalyticsEvents.RECEIVE_CANCELLED,
                    mapOf(AnalyticsEvents.PARAM_PHASE to "receiving"),
                )
                receiveSessionRepo.cancelReceive(s.session)
            }

            reduce {
                ReceiveScreenState.Initial(permissionsHelper.isCameraGranted())
            }
        }

    fun onCancelManualInput() =
        intent {
            firebaseReporter.log("ReceiveViewModel: cancelling manual input")
            analyticsReporter.logEvent(
                AnalyticsEvents.RECEIVE_CANCELLED,
                mapOf(AnalyticsEvents.PARAM_PHASE to "manual_input"),
            )
            reduce {
                ReceiveScreenState.Initial(permissionsHelper.isCameraGranted())
            }
            postSideEffect(ReceiveScreenEffect.HideKeyboard)
        }

    fun handleManualInputSubmit() =
        intent {
            val s = state
            if (s !is ReceiveScreenState.ManualInput)
                return@intent

            firebaseReporter.log("ReceiveViewModel: manual input submitted")
            val parsed = parseManualInput(s.inputText)
            if (parsed != null) {
                firebaseReporter.log("ReceiveViewModel: manual input parsed successfully")
                receiveSource = AnalyticsEvents.SOURCE_MANUAL
                analyticsReporter.logEvent(
                    AnalyticsEvents.RECEIVE_CODE_ENTERED,
                    mapOf(AnalyticsEvents.PARAM_SOURCE to AnalyticsEvents.SOURCE_MANUAL),
                )
                reduce {
                    ReceiveScreenState.QRCodeScanned(
                        ticket = parsed.first,
                        confirmation = parsed.second,
                    )
                }
                postSideEffect(ReceiveScreenEffect.HideKeyboard)
            } else {
                firebaseReporter.log("ReceiveViewModel: manual input parse failed")
                analyticsReporter.logEvent(
                    AnalyticsEvents.RECEIVE_FAILED,
                    mapOf(
                        AnalyticsEvents.PARAM_PHASE to "manual_input",
                        AnalyticsEvents.PARAM_ERROR_TYPE to "invalid_code",
                    ),
                )
                reduce {
                    s.copy(
                        inputError = "Invalid format. Please enter: ticket confirmation",
                    )
                }
            }
        }

    private fun listenToProgress(session: ReceiveSession) {
        session.subscriber.progress.onEach { progress ->
            intent {
                val s = state
                if (s !is ReceiveScreenState.Receiving)
                    return@intent

                reduce {
                    s.copy(
                        progress = progress,
                    )
                }

                if (progress.isConnected && progress.files.isNotEmpty()) {
                    if (!receiveSenderConnectedLogged) {
                        receiveSenderConnectedLogged = true
                        val totalBytes = progress.files.sumOf { it.size.toLong() }
                        analyticsReporter.logEvent(
                            AnalyticsEvents.RECEIVE_SENDER_CONNECTED,
                            mapOf(
                                AnalyticsEvents.PARAM_FILE_COUNT to progress.files.size,
                                AnalyticsEvents.PARAM_TOTAL_BYTES to totalBytes,
                                AnalyticsEvents.PARAM_TOTAL_SIZE_BUCKET to
                                    AnalyticsEvents.sizeBucket(totalBytes),
                            ),
                        )
                    }

                    // Check if all files are complete
                    val completedCount =
                        progress.files.count { file ->
                            progress.fileProgress[file.id]?.isComplete == true
                        }
                    firebaseReporter.log(
                        "ReceiveViewModel: progress " +
                            "connected=${progress.isConnected} " +
                            "files=${progress.files.size} " +
                            "completed=$completedCount",
                    )

                    val allFilesComplete =
                        progress.files.all { file ->
                            val fileProgress = progress.fileProgress[file.id]
                            fileProgress?.isComplete == true
                        }

                    if (allFilesComplete) {
                        // Small delay to ensure UI updates are visible
                        firebaseReporter.log(
                            "ReceiveViewModel: " +
                                "all ${progress.files.size} files complete, saving...",
                        )
                        delay(1000)
                        try {
                            val savedFiles = receiveSessionRepo.saveReceivedFiles(session)
                            if (savedFiles.isNotEmpty()) {
                                val totalBytes = progress.files.sumOf { it.size.toLong() }
                                firebaseReporter.log(
                                    "ReceiveViewModel: saved ${savedFiles.size} files successfully",
                                )
                                analyticsReporter.logEvent(
                                    AnalyticsEvents.RECEIVE_COMPLETED,
                                    mapOf(
                                        AnalyticsEvents.PARAM_FILE_COUNT to
                                            savedFiles.size,
                                        AnalyticsEvents.PARAM_TOTAL_BYTES to totalBytes,
                                        AnalyticsEvents.PARAM_TOTAL_SIZE_BUCKET to
                                            AnalyticsEvents.sizeBucket(totalBytes),
                                        AnalyticsEvents.PARAM_DURATION_MS to
                                            AnalyticsEvents.durationSince(
                                                receiveStartedAtMs,
                                                Clock.System.now().toEpochMilliseconds(),
                                            ),
                                    ),
                                )
                                reduce {
                                    ReceiveScreenState.Success(
                                        session = session,
                                        receivedFiles = savedFiles,
                                    )
                                }
                            } else {
                                firebaseReporter.recordError(
                                    "ReceiveViewModel: no files received",
                                    null,
                                )
                                analyticsReporter.logEvent(
                                    AnalyticsEvents.RECEIVE_FAILED,
                                    mapOf(
                                        AnalyticsEvents.PARAM_PHASE to "saving",
                                        AnalyticsEvents.PARAM_ERROR_TYPE to "no_files_received",
                                    ),
                                )
                                reduce {
                                    ReceiveScreenState.Error(
                                        session = session,
                                        error = ReceiveError.NoFilesReceived,
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Logger.w("Save failed: ${e::class.simpleName} ${e.message}")
                            firebaseReporter.recordError("ReceiveViewModel: save failed", e)
                            analyticsReporter.logEvent(
                                AnalyticsEvents.RECEIVE_FAILED,
                                mapOf(
                                    AnalyticsEvents.PARAM_PHASE to "saving",
                                    AnalyticsEvents.PARAM_ERROR_TYPE to
                                        (e::class.simpleName ?: "unknown_error"),
                                ),
                            )
                            val error =
                                when {
                                    e.message?.contains("storage", ignoreCase = true) == true ->
                                        ReceiveError.StorageError

                                    e.message?.contains("network", ignoreCase = true) == true ->
                                        ReceiveError.NetworkError

                                    else -> ReceiveError.UnknownError
                                }
                            reduce {
                                ReceiveScreenState.Error(
                                    session = session,
                                    error = error,
                                )
                            }
                        }
                    }
                } else if (progress.isConnected) {
                    firebaseReporter.log(
                        "ReceiveViewModel: connected to sender waiting for files",
                    )
                }
            }
        }.launchIn(viewModelScope)
    }

    private fun parseManualInput(input: String): Pair<String, UByte>? {
        return try {
            val trimmed = input.trim()
            val parts = trimmed.split(" ")

            if (parts.size == 2) {
                val ticket = parts[0].trim()
                val confirmation = parts[1].trim().toUByte()

                if (ticket.isNotEmpty()) {
                    Pair(ticket, confirmation)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
