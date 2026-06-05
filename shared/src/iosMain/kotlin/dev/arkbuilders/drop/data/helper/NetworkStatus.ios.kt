@file:OptIn(ExperimentalForeignApi::class)

package dev.arkbuilders.drop.data.helper

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.SystemConfiguration.SCNetworkReachabilityContext
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityFlags
import platform.SystemConfiguration.SCNetworkReachabilityFlagsVar
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.SCNetworkReachabilityRef
import platform.SystemConfiguration.SCNetworkReachabilitySetCallback
import platform.SystemConfiguration.SCNetworkReachabilitySetDispatchQueue
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsConnectionRequired
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsReachable
import platform.darwin.dispatch_queue_create

actual class NetworkStatus {
    private val kReachable: UInt = kSCNetworkReachabilityFlagsReachable
    private val kConnectionRequired: UInt = kSCNetworkReachabilityFlagsConnectionRequired

    private val reachability: SCNetworkReachabilityRef? =
        SCNetworkReachabilityCreateWithName(null, "www.google.com")

    private val _onlineStatus = MutableStateFlow(checkIsOnline())
    actual val onlineStatus: StateFlow<Boolean> = _onlineStatus.asStateFlow()

    private companion object {
        var currentInstance: NetworkStatus? = null
    }

    init {
        currentInstance = this

        val callback =
            staticCFunction<
                SCNetworkReachabilityRef?,
                SCNetworkReachabilityFlags,
                COpaquePointer?,
                Unit,
                > { _, flags, _ ->
                val reachable = (flags and kSCNetworkReachabilityFlagsReachable) != 0u
                val noConnectionRequired =
                    (flags and kSCNetworkReachabilityFlagsConnectionRequired) == 0u

                val isOnline = reachable && noConnectionRequired
                currentInstance?._onlineStatus?.tryEmit(isOnline)
            }

        memScoped {
            val context =
                alloc<SCNetworkReachabilityContext> {
                    version = 0
                    info = null
                    retain = null
                    release = null
                    copyDescription = null
                }

            SCNetworkReachabilitySetCallback(
                reachability,
                callback,
                context.ptr,
            )

            val queue =
                dispatch_queue_create(
                    "NetworkStatusQueue",
                    null,
                )

            SCNetworkReachabilitySetDispatchQueue(
                reachability,
                queue,
            )
        }
    }

    actual fun isOnline(): Boolean = onlineStatus.value

    private fun checkIsOnline(): Boolean =
        memScoped {
            val flags = alloc<SCNetworkReachabilityFlagsVar>()

            if (
                reachability != null &&
                SCNetworkReachabilityGetFlags(reachability, flags.ptr)
            ) {
                val reachable =
                    (flags.value and kSCNetworkReachabilityFlagsReachable) != 0u
                val noConnectionRequired =
                    (flags.value and kSCNetworkReachabilityFlagsConnectionRequired) == 0u

                reachable && noConnectionRequired
            } else {
                false
            }
        }
}
