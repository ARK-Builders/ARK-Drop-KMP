@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.arkbuilders.drop.domain.libwrapper.receive

import dev.arkbuilders.drop.bridge.crashlytics_log
import dev.arkbuilders.drop.bridge.crashlytics_recordError
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSMutableData
import platform.Foundation.NSUUID
import platform.Foundation.appendBytes
import platform.Foundation.dataWithCapacity
import platform.Foundation.getBytes

class DropReceiveFilesSubscriberImpl(
    val native: ReceiveFilesSubscriberImpl,
) : DropReceiveFilesSubscriber {
    override val progress: StateFlow<DropReceivingProgress> = native.progress

    override fun getCompleteFiles() = native.getCompleteFiles()
}

class ReceiveFilesSubscriberImpl {
    companion object {
        private const val TAG = "ReceiveFilesSubscriber"
    }

    private val id = NSUUID().UUIDString

    // Thread-safe storage for received data using NSMutableData for efficient appending
    private val receivedDataMap = mutableMapOf<String, NSMutableData>()

    internal val progressMutable = MutableStateFlow(DropReceivingProgress())
    val progress: StateFlow<DropReceivingProgress> = progressMutable.asStateFlow()

    fun getId(): String = id

    fun log(message: String) {
        // On iOS, we can use NSLog or a logging framework
        crashlytics_log("ReceiveFilesSubscriber: $message")
    }

    fun reset() {
        // Clear all data streams
        crashlytics_log("ReceiveFilesSubscriber: reset called")
        receivedDataMap.clear()
        progressMutable.value = DropReceivingProgress()
        crashlytics_log("ReceiveFilesSubscriber: reset completed")
    }

    /**
     * Get files that have been completely received
     */
    fun getCompleteFiles(): List<Pair<ReceiveFileInfo, ByteArray>> {
        val currentProgress = progressMutable.value
        crashlytics_log(
            "ReceiveFilesSubscriber: getCompleteFiles totalFiles=${currentProgress.files.size}",
        )

        val result =
            currentProgress.files.mapNotNull { fileInfo ->
                val progressInfo = currentProgress.fileProgress[fileInfo.id]
                if (progressInfo?.isComplete == true) {
                    val data = receivedDataMap[fileInfo.id]
                    if (data != null) {
                        val length = data.length.toInt()
                        crashlytics_log(
                            "ReceiveFilesSubscriber: complete " +
                                "bytes=$length",
                        )
                        val bytes = ByteArray(length)
                        bytes.usePinned { pinned ->
                            data.getBytes(
                                pinned.addressOf(0),
                                length = length.toULong(),
                            )
                        }
                        Pair(fileInfo, bytes)
                    } else {
                        crashlytics_recordError(
                            "ReceiveFilesSubscriber: " +
                                "complete file missing data",
                            null,
                        )
                        null
                    }
                } else {
                    null
                }
            }

        crashlytics_log("ReceiveFilesSubscriber: getCompleteFiles returning ${result.size} files")
        return result
    }

    /**
     * Helper method to append received data directly (for bridge use)
     */
    fun appendReceivedData(
        fileId: String,
        data: ByteArray,
    ) {
        crashlytics_log(
            "ReceiveFilesSubscriber: appendReceivedData chunkSize=${data.size}",
        )

        val existingData =
            receivedDataMap.getOrPut(fileId) {
                NSMutableData.dataWithCapacity(0u) as NSMutableData
            }
        data.usePinned { pinned ->
            existingData.appendBytes(pinned.addressOf(0), length = data.size.toULong())
        }

        // Update progress
        val currentProgress = progressMutable.value
        val fileInfo = currentProgress.files.find { it.id == fileId }

        if (fileInfo != null) {
            val receivedBytes = existingData.length.toLong()
            val isComplete = receivedBytes.toULong() >= fileInfo.size
            val totalSize = fileInfo.size

            crashlytics_log(
                "ReceiveFilesSubscriber: file progress " +
                    "received=$receivedBytes " +
                    "total=$totalSize " +
                    "complete=$isComplete",
            )

            val updatedFileProgress = currentProgress.fileProgress.toMutableMap()
            updatedFileProgress[fileId] =
                FileProgressInfo(
                    receivedBytes = receivedBytes,
                    isComplete = isComplete,
                )

            progressMutable.value =
                currentProgress.copy(
                    fileProgress = updatedFileProgress.toMap(),
                )
        } else {
            crashlytics_log(
                "ReceiveFilesSubscriber: " +
                    "file not in expected file list, buffering data size=${data.size}",
            )
        }
    }
}
