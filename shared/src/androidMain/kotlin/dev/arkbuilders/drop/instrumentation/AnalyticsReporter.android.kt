package dev.arkbuilders.drop.instrumentation

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

actual class AnalyticsReporter actual constructor() {
    private val instance: FirebaseAnalytics by lazy {
        FirebaseAnalytics.getInstance(requireNotNull(applicationContext))
    }

    actual fun logEvent(
        name: String,
        params: Map<String, Any>,
    ) {
        val bundle =
            Bundle().apply {
                params.forEach { (key, value) ->
                    when (value) {
                        is Byte -> putLong(key, value.toLong())
                        is Short -> putLong(key, value.toLong())
                        is Int -> putLong(key, value.toLong())
                        is Long -> putLong(key, value)
                        is Float -> putDouble(key, value.toDouble())
                        is Double -> putDouble(key, value)
                        else -> putString(key, value.toString())
                    }
                }
            }
        instance.logEvent(name, bundle)
    }

    companion object {
        private var applicationContext: Context? = null

        fun initialize(context: Context) {
            applicationContext = context.applicationContext
        }
    }
}
