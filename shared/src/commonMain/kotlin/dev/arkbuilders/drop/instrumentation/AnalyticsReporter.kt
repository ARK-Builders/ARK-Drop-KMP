package dev.arkbuilders.drop.instrumentation

expect class AnalyticsReporter() {
    fun logEvent(
        name: String,
        params: Map<String, Any> = emptyMap(),
    )
}
