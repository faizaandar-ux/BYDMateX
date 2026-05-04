package com.bydmate.app.data.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the most recent moment a default network became VALIDATED — Android's
 * confirmation that the connection actually has working internet (passed the
 * captive-portal probe and DNS round-trip), not just an attached interface.
 *
 * The AutomationEngine reads [lastAvailableAt] each poll tick and compares it
 * against the rule's `lastTriggeredAt` to detect "internet just appeared" edges.
 * VALIDATED arrives via a separate [NetworkCapabilities] event ~1-3s after the
 * raw `onAvailable` callback, so we listen on `onCapabilitiesChanged` to catch
 * the precise moment Android decided the connection works.
 */
@Singleton
class NetworkAvailableMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile private var _lastAvailableAt: Long = 0L
    // Tracks which network is currently validated. Prevents repeated capabilities
    // callbacks for the SAME network from advancing _lastAvailableAt — without
    // this, every signal-strength tick on a stable WiFi would re-arm the trigger.
    @Volatile private var validatedNetworkId: Long = -1L

    val lastAvailableAt: Long get() = _lastAvailableAt

    private val cm: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val nid = network.networkHandle
            if (hasInternet && validated) {
                if (validatedNetworkId != nid) {
                    validatedNetworkId = nid
                    _lastAvailableAt = System.currentTimeMillis()
                    Log.i(TAG, "validated network edge at $_lastAvailableAt (nid=$nid)")
                }
            } else if (validatedNetworkId == nid) {
                validatedNetworkId = -1L
                Log.i(TAG, "validation lost on tracked network (nid=$nid)")
            }
        }

        override fun onLost(network: Network) {
            if (validatedNetworkId == network.networkHandle) {
                validatedNetworkId = -1L
                Log.i(TAG, "tracked network lost (nid=${network.networkHandle})")
            }
        }
    }

    fun start() {
        if (registered) return
        runCatching {
            // Initial-state guard. ConnectivityManager replays the current network
            // state to a freshly-registered callback — without this, every
            // TrackingService restart on a stable WiFi would surface a brand-new
            // VALIDATED edge and re-fire `network_available` rules. We seed
            // validatedNetworkId with the already-validated network so the
            // imminent replay callback is treated as a no-op (same nid).
            try {
                val active = cm.activeNetwork
                val caps = active?.let { cm.getNetworkCapabilities(it) }
                if (active != null && caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    validatedNetworkId = active.networkHandle
                    Log.i(TAG, "start: already validated (nid=${active.networkHandle}), suppressing replay edge")
                }
            } catch (e: Exception) {
                Log.w(TAG, "initial-state probe failed: ${e.message}")
            }
            cm.registerDefaultNetworkCallback(callback)
            registered = true
            Log.i(TAG, "started")
        }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
    }

    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        Log.i(TAG, "stopped")
    }

    private companion object {
        const val TAG = "NetworkAvailMon"
    }
}
