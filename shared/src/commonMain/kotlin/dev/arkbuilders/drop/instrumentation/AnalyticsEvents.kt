package dev.arkbuilders.drop.instrumentation

object AnalyticsEvents {
    const val APP_START = "app_start"
    const val SCREEN_VIEW = "screen_view"

    const val SEND_FILES_SELECTED = "send_files_selected"
    const val SEND_STARTED = "send_started"
    const val SEND_QR_READY = "send_qr_ready"
    const val SEND_RECEIVER_CONNECTED = "send_receiver_connected"
    const val SEND_COMPLETED = "send_completed"
    const val SEND_CANCELLED = "send_cancelled"
    const val SEND_FAILED = "send_failed"

    const val RECEIVE_SCAN_STARTED = "receive_scan_started"
    const val RECEIVE_CODE_ENTERED = "receive_code_entered"
    const val RECEIVE_STARTED = "receive_started"
    const val RECEIVE_SENDER_CONNECTED = "receive_sender_connected"
    const val RECEIVE_COMPLETED = "receive_completed"
    const val RECEIVE_CANCELLED = "receive_cancelled"
    const val RECEIVE_FAILED = "receive_failed"
    const val CAMERA_PERMISSION_RESULT = "camera_permission_result"
    const val PROFILE_SAVED = "profile_saved"
    const val HISTORY_CLEARED = "history_cleared"
    const val HISTORY_ITEM_DELETED = "history_item_deleted"
    const val SEND_CODE_COPIED = "send_code_copied"
    const val RECEIVE_DEEP_LINK_OPENED = "receive_deep_link_opened"
    const val SEND_FILE_PICKER_OPENED = "send_file_picker_opened"
    const val SEND_FILE_REMOVED = "send_file_removed"
    const val SEND_MORE_SELECTED = "send_more_selected"
    const val RECEIVE_MANUAL_INPUT_STARTED = "receive_manual_input_started"
    const val RECEIVE_CLIPBOARD_PASTED = "receive_clipboard_pasted"
    const val RECEIVE_MORE_SELECTED = "receive_more_selected"
    const val PROFILE_IMAGE_PICKER_OPENED = "profile_image_picker_opened"

    const val PARAM_FILE_COUNT = "file_count"
    const val PARAM_SKIPPED_COUNT = "skipped_count"
    const val PARAM_TOTAL_BYTES = "total_bytes"
    const val PARAM_TOTAL_SIZE_BUCKET = "total_size_bucket"
    const val PARAM_DURATION_MS = "duration_ms"
    const val PARAM_PHASE = "phase"
    const val PARAM_ERROR_TYPE = "error_type"
    const val PARAM_SOURCE = "source"
    const val PARAM_GRANTED = "granted"
    const val PARAM_PLATFORM = "platform"
    const val PARAM_SCREEN_NAME = "screen_name"
    const val PARAM_SCREEN_CLASS = "screen_class"
    const val PARAM_CHANGED_NAME = "changed_name"
    const val PARAM_CHANGED_AVATAR = "changed_avatar"
    const val PARAM_ITEM_COUNT = "item_count"
    const val PARAM_HAS_TEXT = "has_text"
    const val PARAM_REMAINING_FILE_COUNT = "remaining_file_count"

    const val SOURCE_QR = "qr"
    const val SOURCE_MANUAL = "manual"
    const val SOURCE_UNKNOWN = "unknown"

    fun sizeBucket(bytes: Long): String =
        when {
            bytes < 1L * 1024L * 1024L -> "0_1mb"
            bytes < 10L * 1024L * 1024L -> "1_10mb"
            bytes < 100L * 1024L * 1024L -> "10_100mb"
            bytes < 1L * 1024L * 1024L * 1024L -> "100mb_1gb"
            else -> "1gb_plus"
        }

    fun durationSince(
        startedAtMs: Long?,
        nowMs: Long,
    ): Long = startedAtMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: -1L
}
