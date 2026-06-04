@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.arkbuilders.drop.instrumentation

import dev.arkbuilders.drop.bridge.analytics_logEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

actual class AnalyticsReporter actual constructor() {
    actual fun logEvent(
        name: String,
        params: Map<String, Any>,
    ) {
        val jsonParams =
            buildJsonObject {
                params.forEach { (key, value) ->
                    put(key, value.toJsonPrimitive())
                }
            }
        analytics_logEvent(name, jsonParams.toString())
    }

    private fun Any.toJsonPrimitive(): JsonPrimitive =
        when (this) {
            is Boolean -> JsonPrimitive(toString())
            is Byte -> JsonPrimitive(this)
            is Short -> JsonPrimitive(this)
            is Int -> JsonPrimitive(this)
            is Long -> JsonPrimitive(this)
            is Float -> JsonPrimitive(this.toDouble())
            is Double -> JsonPrimitive(this)
            else -> JsonPrimitive(toString())
        }
}
