package com.bydmate.app.data.remote

import android.util.Log
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlicePollingManager @Inject constructor(
    private val httpClient: OkHttpClient,
    private val controlClient: DiParsControlClient,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "AlicePolling"
        private const val POLL_INTERVAL_MS = 1000L
    }

    private var scope: CoroutineScope? = null
    private var pollingJob: Job? = null

    fun start() {
        if (pollingJob?.isActive == true) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pollingJob = scope?.launch {
            Log.i(TAG, "Polling started")
            while (true) {
                try {
                    poll()
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        scope?.cancel()
        scope = null
        Log.i(TAG, "Polling stopped")
    }

    val isRunning: Boolean get() = pollingJob?.isActive == true

    private suspend fun poll() {
        val endpoint = settingsRepository.getString(SettingsRepository.KEY_ALICE_ENDPOINT, "")
        val apiKey = settingsRepository.getString(SettingsRepository.KEY_ALICE_API_KEY, "")
        if (endpoint.isBlank() || apiKey.isBlank()) return

        val request = Request.Builder()
            .url("$endpoint/api/poll")
            .header("X-Api-Key", apiKey)
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: return
        val json = JSONObject(body)
        val commands = json.optJSONArray("commands") ?: return

        if (commands.length() == 0) return
        Log.i(TAG, "Received ${commands.length()} command(s)")

        val ackIds = mutableListOf<String>()
        for (i in 0 until commands.length()) {
            val cmd = commands.getJSONObject(i)
            val id = cmd.getString("id")
            val command = cmd.getString("command")
            Log.i(TAG, "Executing: '$command' (id=$id)")
            val success = controlClient.sendCommand(command)
            Log.i(TAG, "Result: $command → ${if (success) "OK" else "FAIL"}")
            ackIds.add(id)
        }

        if (ackIds.isNotEmpty()) {
            ack(endpoint, apiKey, ackIds)
        }
    }

    private fun ack(endpoint: String, apiKey: String, ids: List<String>) {
        try {
            val json = JSONObject().apply {
                put("ids", JSONArray(ids))
            }
            val request = Request.Builder()
                .url("$endpoint/api/ack")
                .header("X-Api-Key", apiKey)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute()
            Log.i(TAG, "Acked ${ids.size} command(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Ack failed: ${e.message}")
        }
    }
}
