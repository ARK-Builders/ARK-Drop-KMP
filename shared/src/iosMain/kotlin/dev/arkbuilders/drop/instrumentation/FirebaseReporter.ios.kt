@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.arkbuilders.drop.instrumentation

import dev.arkbuilders.drop.bridge.crashlytics_log
import dev.arkbuilders.drop.bridge.crashlytics_recordError
import dev.arkbuilders.drop.bridge.crashlytics_setCustomKey

actual class FirebaseReporter {
    actual fun recordError(
        message: String,
        throwable: Throwable?,
    ) {
        val stackTrace = throwable?.stackTraceToString()
        crashlytics_recordError(message, stackTrace)
    }

    actual fun log(message: String) {
        crashlytics_log(message)
    }

    actual fun setCustomKey(
        key: String,
        value: String,
    ) {
        crashlytics_setCustomKey(key, value)
    }
}
