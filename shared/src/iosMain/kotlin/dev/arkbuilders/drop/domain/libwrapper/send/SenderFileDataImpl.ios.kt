
@file:OptIn(ExperimentalForeignApi::class)
package dev.arkbuilders.drop.domain.libwrapper.send

import dev.arkbuilders.drop.domain.libwrapper.send.request.DropSenderFileData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import platform.Foundation.*

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

        try {
            print("[SenderFileData] Initializing file: $uri")
            
            // Try as file path first, then as URL string
            val url = NSURL.fileURLWithPath(uri) 
                ?: NSURL.URLWithString(uri) 
                ?: run {
                    print("[SenderFileData] Failed to create URL from: $uri")
                    return
                }

            print("[SenderFileData] Created URL: ${url.absoluteString}")

            // Get file size
            val resourceValues = url.resourceValuesForKeys(listOf(NSURLFileSizeKey), null)
            resourceValues?.get(NSURLFileSizeKey)?.let {
                totalLength = ((it as? NSNumber)?.longValue ?: 0L).toULong()
                print("[SenderFileData] File size: $totalLength bytes")
            } ?: print("[SenderFileData] Could not get file size")

            // Open input stream
            inputStream = NSInputStream.inputStreamWithURL(url)
            inputStream?.open()
            
            val status = inputStream?.streamStatus
            print("[SenderFileData] Stream status: $status")
            
            if (inputStream?.streamError != null) {
                print("[SenderFileData] Stream error: ${inputStream?.streamError?.localizedDescription}")
                return
            }
            
            isInitialized = true
            print("[SenderFileData] Successfully initialized")
        } catch (e: Exception) {
            print("[SenderFileData] Failed to initialize file: $uri, error: $e")
        }
    }

    override fun len(): ULong {
        initialize()
        return totalLength
    }

    override fun read(): UByte? {
        initialize()
        if (!isInitialized) {
            print("[SenderFileData] read() - not initialized for $uri")
            return null
        }
        return try {
            val buffer = UByteArray(1)
            val bytesRead = buffer.usePinned { pinned ->
                inputStream?.read(pinned.addressOf(0).reinterpret(), maxLength = 1u)?.toLong() ?: 0L
            }
            if (bytesRead == 0L) {
                inputStream?.close()
                null
            } else {
                buffer[0]
            }
        } catch (e: Exception) {
            print("[SenderFileData] read() error for $uri: $e")
            null
        }
    }

    override fun readChunk(size: Int): ByteArray {
        initialize()
        if (!isInitialized) {
            print("[SenderFileData] readChunk() - not initialized for $uri")
            return ByteArray(0)
        }
        return try {
            val buffer = UByteArray(size)
            val bytesRead = buffer.usePinned { pinned ->
                inputStream?.read(pinned.addressOf(0).reinterpret(), maxLength = size.toULong())?.toLong() ?: 0L
            }
            if (bytesRead == 0L) {
                inputStream?.close()
                ByteArray(0)
            } else {
                buffer.asByteArray().copyOf(bytesRead.toInt())
            }
        } catch (e: Exception) {
            print("[SenderFileData] readChunk() error for $uri: $e")
            ByteArray(0)
        }
    }
}
