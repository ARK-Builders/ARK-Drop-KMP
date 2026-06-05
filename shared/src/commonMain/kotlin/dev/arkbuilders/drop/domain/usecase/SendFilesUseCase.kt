package dev.arkbuilders.drop.domain.usecase

import co.touchlab.kermit.Logger
import dev.arkbuilders.drop.data.helper.ResourcesHelper
import dev.arkbuilders.drop.domain.libwrapper.getDropApi
import dev.arkbuilders.drop.domain.libwrapper.send.DropSendFilesBubble
import dev.arkbuilders.drop.domain.libwrapper.send.request.DropSendFilesRequest
import dev.arkbuilders.drop.domain.libwrapper.send.request.DropSenderConfig
import dev.arkbuilders.drop.domain.libwrapper.send.request.DropSenderFile
import dev.arkbuilders.drop.domain.libwrapper.send.request.DropSenderProfile
import dev.arkbuilders.drop.domain.repository.ProfileRepo
import dev.arkbuilders.drop.instrumentation.FirebaseReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class SendFilesUseCase(
    private val profileRepo: ProfileRepo,
    private val resourcesHelper: ResourcesHelper,
    private val firebaseReporter: FirebaseReporter,
) {
    suspend operator fun invoke(fileUris: List<String>): Result<DropSendFilesBubble> =
        withContext(Dispatchers.IO) {
            runCatching {
                Logger.d("Starting file send for ${fileUris.size} files")
                firebaseReporter.setCustomKey("send_file_count", fileUris.size.toString())
                firebaseReporter.log(
                    "SendFilesUseCase: starting file send fileCount=${fileUris.size}",
                )

                val profile = profileRepo.profile.first()
                val senderProfile =
                    DropSenderProfile(
                        name = profile.name.ifEmpty { "Anonymous" },
                        avatarB64 = profile.avatar.base64.takeIf { it.isNotEmpty() },
                    )
                firebaseReporter.log(
                    "SendFilesUseCase: sender profile loaded - " +
                        "hasAvatar: ${senderProfile.avatarB64 != null}",
                )

                var skippedCount = 0
                var totalBytes = 0L
                val senderFiles =
                    fileUris.mapNotNull { uri ->
                        val fileName = resourcesHelper.getFileName(uri)
                        if (fileName != null) {
                            val fileData = resourcesHelper.mapToSenderFileData(uri)
                            val fileSize = resourcesHelper.getFileSize(uri)
                            totalBytes += fileSize.coerceAtLeast(0L)
                            DropSenderFile(
                                name = fileName,
                                data = fileData,
                            )
                        } else {
                            Logger.w("Could not get filename for selected file")
                            firebaseReporter.log(
                                "SendFilesUseCase: file skipped because filename was unavailable",
                            )
                            skippedCount++
                            null
                        }
                    }

                if (senderFiles.isEmpty()) {
                    firebaseReporter.recordError(
                        "SendFilesUseCase: no valid files to send after processing " +
                            "${fileUris.size} URIs, skipped: $skippedCount",
                    )
                    Logger.e("No valid files to send")
                    error("No valid files to send")
                }

                if (skippedCount > 0) {
                    firebaseReporter.log(
                        "SendFilesUseCase: $skippedCount files skipped, " +
                            "${senderFiles.size} files will be sent",
                    )
                }
                firebaseReporter.setCustomKey("send_total_bytes", totalBytes.toString())

                // Using UInt values, converted to ULong for the config
                val chunkSize = 1024u * 512u // UInt
                val parallelStreams = 4u // UInt

                val request =
                    DropSendFilesRequest(
                        profile = senderProfile,
                        files = senderFiles,
                        config =
                            DropSenderConfig(
                                chunkSize = chunkSize.toULong(),
                                parallelStreams = parallelStreams.toULong(),
                            ),
                    )
                firebaseReporter.log(
                    "SendFilesUseCase: request built - " +
                        "files: ${senderFiles.size}, " +
                        "totalBytes: $totalBytes, " +
                        "chunkSize: ${request.config?.chunkSize}, " +
                        "parallelStreams: ${request.config?.parallelStreams}",
                )

                val bubble: DropSendFilesBubble = getDropApi().sendFiles(request)

                firebaseReporter.log(
                    "SendFilesUseCase: bubble created - " +
                        "createdAt: ${bubble.getCreatedAt()}",
                )

                Logger.d("Send bubble created")
                bubble
            }.onFailure { error ->
                firebaseReporter.recordError("SendFilesUseCase: failed - ${error.message}", error)
                Logger.e("Error starting file send ${error.message}", error)
            }
        }
}
