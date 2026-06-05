@file:OptIn(ExperimentalForeignApi::class)

package dev.arkbuilders.drop.domain.libwrapper.send

import dev.arkbuilders.drop.bridge.crashlytics_log
import dev.arkbuilders.drop.bridge.crashlytics_recordError
import dev.arkbuilders.drop.domain.libwrapper.send.request.DropSenderFileData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSInputStream
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLFileSizeKey
import platform.Foundation.inputStreamWithURL

class SenderFileDataImpl(
    private val uri: String,
) : DropSenderFileData {
    companion object {
        private const val TAG = "SenderFileDataImpl"
    }

    private var inputStream: NSInputStream? = null
    private var totalLength: ULong = 0UL
    private var isInitialized = false

    private fun initialize() {
        if (isInitialized) return

        println("SenderFileDataImpl: Initializing file")
        crashlytics_log("SenderFileDataImpl: initializing file")

        try {
            // Try as file path first, then as URL string
            val url =
                NSURL.fileURLWithPath(uri)
                    ?: NSURL.URLWithString(uri)
                    ?: run {
                        println("SenderFileDataImpl: Failed to create URL")
                        crashlytics_recordError("SenderFileDataImpl: failed to create URL", null)
                        return
                    }

            println("SenderFileDataImpl: Created URL")

            // Get file size
            val resourceValues = url.resourceValuesForKeys(listOf(NSURLFileSizeKey), null)
            resourceValues?.get(NSURLFileSizeKey)?.let {
                totalLength = ((it as? NSNumber)?.longValue ?: 0L).toULong()
                println("SenderFileDataImpl: File size: $totalLength bytes")
                crashlytics_log("SenderFileDataImpl: file size=$totalLength bytes")
            } ?: println("SenderFileDataImpl: Could not get file size").also {
                crashlytics_log("SenderFileDataImpl: could not get file size")
            }

            // Open input stream
            inputStream = NSInputStream.inputStreamWithURL(url)
            inputStream?.open()

            val status = inputStream?.streamStatus
            println("SenderFileDataImpl: Stream status: $status")

            if (inputStream?.streamError != null) {
                val errorDesc = inputStream?.streamError?.localizedDescription ?: "unknown"
                println("SenderFileDataImpl: Stream error")
                crashlytics_recordError(
                    "SenderFileDataImpl: stream error - $errorDesc",
                    null,
                )
                return
            }

            isInitialized = true
            println("SenderFileDataImpl: Successfully initialized")
            crashlytics_log("SenderFileDataImpl: successfully initialized")
        } catch (e: Exception) {
            println("SenderFileDataImpl: Failed to initialize file")
            crashlytics_recordError(
                "SenderFileDataImpl: failed to initialize",
                e.message,
            )
        }
    }

    override fun len(): ULong {
        initialize()
        crashlytics_log("SenderFileDataImpl: len() returning=$totalLength")
        return totalLength
    }

    override fun read(): UByte? {
        initialize()
        if (!isInitialized) {
            println("SenderFileDataImpl.read() - not initialized")
            crashlytics_recordError("SenderFileDataImpl.read() - not initialized", null)
            return null
        }
        return try {
            val buffer = UByteArray(1)
            val bytesRead =
                buffer.usePinned { pinned ->
                    inputStream?.read(
                        pinned.addressOf(0).reinterpret(),
                        maxLength = 1u,
                    ) ?: 0L
                }
            if (bytesRead == 0L) {
                inputStream?.close()
                crashlytics_log("SenderFileDataImpl: read() reached end of file, stream closed")
                null
            } else {
                buffer[0]
            }
        } catch (e: Exception) {
            println("SenderFileDataImpl.read() error")
            crashlytics_recordError("SenderFileDataImpl.read() error", e.message)
            null
        }
    }

    override fun readChunk(size: Int): ByteArray {
        initialize()
        if (!isInitialized) {
            println("SenderFileDataImpl.readChunk() - not initialized")
            crashlytics_recordError("SenderFileDataImpl.readChunk() - not initialized", null)
            return ByteArray(0)
        }
        return try {
            val buffer = UByteArray(size)
            val bytesRead =
                buffer.usePinned { pinned ->
                    inputStream?.read(
                        pinned.addressOf(0).reinterpret(),
                        maxLength = size.toULong(),
                    ) ?: 0L
                }
            if (bytesRead == 0L) {
                inputStream?.close()
                crashlytics_log("SenderFileDataImpl: readChunk() reached end of file")
                ByteArray(0)
            } else {
                val result = buffer.asByteArray().copyOf(bytesRead.toInt())
                crashlytics_log(
                    "SenderFileDataImpl: readChunk() - requested: $size, " +
                        "read: $bytesRead bytes",
                )
                result
            }
        } catch (e: Exception) {
            println("SenderFileDataImpl.readChunk() error")
            crashlytics_recordError(
                "SenderFileDataImpl.readChunk() error size=$size",
                e.message,
            )
            ByteArray(0)
        }
    }
}
