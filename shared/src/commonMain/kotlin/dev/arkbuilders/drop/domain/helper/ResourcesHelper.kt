package dev.arkbuilders.drop.domain.helper

import dev.arkbuilders.drop.domain.libwrapper.send.request.DropSenderFileData

interface ResourcesHelper {
    fun getFileName(uri: String): String?

    fun validateUris(uris: List<String>): Pair<List<String>, Int>

    fun getFileSize(uri: String): Long

    fun saveFileToDownloads(
        fileName: String,
        data: ByteArray,
    ): String?

    fun mapToSenderFileData(uri: String): DropSenderFileData
}