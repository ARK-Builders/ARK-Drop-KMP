package dev.arkbuilders.drop.domain.helper

interface PermissionsHelper {
    fun isCameraGranted(): Boolean

    fun isWritePermissionGranted(): Boolean
}