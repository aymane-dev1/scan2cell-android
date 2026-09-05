package com.scan2cell.app

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LocalBridgeClient(private val context: Context) {
    data class ServerEndpoint(
        val baseUrl: String,
        val name: String,
        val serverId: String,
        val excelConnected: Boolean
    )

    data class PairResult(
        val token: String,
        val serverName: String,
        val serverId: String,
        val excelConnected: Boolean,
        val baseUrl: String
    )

    data class SendResult(
        val address: String
    )

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    fun discover(lastBaseUrl: String?, manualIp: String?): ServerEndpoint {
        normalizeManualAddress(manualIp)?.let { address ->
            return probe(address)
        }

        if (!lastBaseUrl.isNullOrBlank()) {
            try {
                return probe(lastBaseUrl)
            } catch (_: Exception) {
                // Continue with local discovery in case the PC IP changed.
            }
        }

        return discoverWithUdp()
    }

    fun probe(baseUrl: String): ServerEndpoint {
        val normalized = baseUrl.trimEnd('/')
        val request = Request.Builder()
            .url("$normalized/api/info")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("PC bridge returned HTTP ${response.code}.")
            }
            val json = JSONObject(text)
            if (!json.optBoolean("ok")) {
                throw IllegalStateException(json.optString("error", "PC bridge is unavailable."))
            }
            return ServerEndpoint(
                baseUrl = normalized,
                name = json.optString("name", "Excel PC"),
                serverId = json.optString("serverId"),
                excelConnected = json.optBoolean("excelConnected")
            )
        }
    }

    fun pair(
        endpoint: ServerEndpoint,
        code: String,
        deviceId: String,
        deviceName: String
    ): PairResult {
        val payload = JSONObject()
            .put("code", code)
            .put("deviceId", deviceId)
            .put("deviceName", deviceName)
            .toString()

        val request = Request.Builder()
            .url("${endpoint.baseUrl}/api/pair")
            .post(payload.toRequestBody(jsonType))
            .header("Cache-Control", "no-cache")
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful || !json.optBoolean("ok")) {
                throw IllegalStateException(
                    json.optString("error", "Pairing failed with HTTP ${response.code}.")
                )
            }
            return PairResult(
                token = json.getString("token"),
                serverName = json.optString("serverName", endpoint.name),
                serverId = json.optString("serverId", endpoint.serverId),
                excelConnected = json.optBoolean("excelConnected"),
                baseUrl = endpoint.baseUrl
            )
        }
    }


    /**
     * Pair by six-digit code across ALL Scan2Cell PCs visible on the LAN.
     *
     * This is important in an office where several coworkers run Scan2Cell:
     * UDP discovery may receive multiple replies, and the first reply is not
     * necessarily the PC whose code is visible in Excel.
     *
     * We therefore try the code against each discovered bridge until one
     * accepts it. A wrong-code response does not modify that PC's state.
     */
    fun pairByCode(
        lastBaseUrl: String?,
        manualIp: String?,
        code: String,
        deviceId: String,
        deviceName: String
    ): PairResult {
        // Manual IP always means "use exactly this PC".
        normalizeManualAddress(manualIp)?.let { address ->
            return pair(
                endpoint = probe(address),
                code = code,
                deviceId = deviceId,
                deviceName = deviceName
            )
        }

        val candidates = linkedMapOf<String, ServerEndpoint>()

        // Keep the previously paired PC as one candidate, but do NOT stop there.
        if (!lastBaseUrl.isNullOrBlank()) {
            runCatching { probe(lastBaseUrl) }
                .getOrNull()
                ?.let { endpoint ->
                    candidates[endpoint.serverId.ifBlank { endpoint.baseUrl }] = endpoint
                }
        }

        // Collect every Scan2Cell PC replying on the current Wi-Fi.
        for (endpoint in discoverAllWithUdp()) {
            candidates[endpoint.serverId.ifBlank { endpoint.baseUrl }] = endpoint
        }

        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "No Scan2Cell PC was found. Keep the Excel pane open, use the same Wi-Fi, and allow Scan2Cell through Windows Firewall."
            )
        }

        var lastNetworkError: Exception? = null

        for (endpoint in candidates.values) {
            try {
                return pair(
                    endpoint = endpoint,
                    code = code,
                    deviceId = deviceId,
                    deviceName = deviceName
                )
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                // Expected when this is another coworker's PC. Keep trying.
                if (message.contains("Wrong or expired pairing code", ignoreCase = true)) {
                    continue
                }
                lastNetworkError = error
            }
        }

        if (lastNetworkError != null && candidates.size == 1) {
            throw lastNetworkError
        }

        throw IllegalStateException(
            "No Scan2Cell PC accepted this code. Use the fresh six-digit code shown on the PC you want to connect to."
        )
    }

    fun send(
        baseUrl: String,
        token: String,
        deviceId: String,
        text: String
    ): SendResult {
        val payload = JSONObject()
            .put("text", text)
            .put("requestId", UUID.randomUUID().toString())
            .toString()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/send")
            .post(payload.toRequestBody(jsonType))
            .header("Authorization", "Bearer $token")
            .header("X-Device-Id", deviceId)
            .header("Cache-Control", "no-cache")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            if (!response.isSuccessful || !json.optBoolean("ok")) {
                throw IllegalStateException(
                    json.optString("error", "Send failed with HTTP ${response.code}.")
                )
            }
            return SendResult(address = json.optString("address", "selected cell"))
        }
    }


    fun sendReceipt(
        baseUrl: String,
        token: String,
        deviceId: String,
        receipt: ReceiptData
    ): SendResult {
        val contract = receipt.contractNumber.trim()
        val tier = receipt.tierReference.trim()
        val combinedReferences = when {
            contract.isNotBlank() && tier.isNotBlank() -> "$contract|$tier"
            contract.isNotBlank() -> contract
            tier.isNotBlank() -> "|$tier"
            else -> ""
        }

        val payload = JSONObject()
            .put("treasuryNumber", receipt.treasuryNumber)
            .put("clientName", receipt.clientName)
            // Compatibility with the already-working PC add-in:
            // Reçus column D receives "contract|tier" as ONE text value.
            .put("contractNumber", combinedReferences)
            .put("tierReference", receipt.tierReference)
            .put("amount", receipt.amount)
            .put("requestId", UUID.randomUUID().toString())
            .toString()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/receipt")
            .post(payload.toRequestBody(jsonType))
            .header("Authorization", "Bearer $token")
            .header("X-Device-Id", deviceId)
            .header("Cache-Control", "no-cache")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            if (!response.isSuccessful || !json.optBoolean("ok")) {
                throw IllegalStateException(
                    json.optString("error", "Receipt send failed with HTTP ${response.code}.")
                )
            }
            return SendResult(address = json.optString("address", "Reçus sheet"))
        }
    }

    private fun discoverWithUdp(): ServerEndpoint {
        val message = "SCAN2CELL_DISCOVER_V1".toByteArray(StandardCharsets.UTF_8)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 1100

            val targets = linkedSetOf(InetAddress.getByName("255.255.255.255"))
            wifiBroadcastAddress()?.let { address -> targets.add(address) }

            repeat(4) {
                for (target in targets) {
                    runCatching {
                        socket.send(DatagramPacket(message, message.size, target, DISCOVERY_PORT))
                    }
                }

                val roundDeadline = System.currentTimeMillis() + 1100
                while (System.currentTimeMillis() < roundDeadline) {
                    try {
                        val buffer = ByteArray(2048)
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val response = String(
                            packet.data,
                            packet.offset,
                            packet.length,
                            StandardCharsets.UTF_8
                        )
                        val json = JSONObject(response)
                        if (json.optString("type") != "SCAN2CELL_REPLY_V1") continue
                        val port = json.optInt("port", PHONE_PORT)
                        val baseUrl = "http://${packet.address.hostAddress}:$port"
                        return probe(baseUrl)
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (_: Exception) {
                        // Ignore malformed replies and keep searching.
                    }
                }
            }
        }

        throw IllegalStateException(
            "No Scan2Cell PC was found. Keep the Excel pane open, use the same Wi-Fi, and allow the Windows Firewall prompt."
        )
    }


    private fun discoverAllWithUdp(): List<ServerEndpoint> {
        val message = "SCAN2CELL_DISCOVER_V1".toByteArray(StandardCharsets.UTF_8)
        val found = linkedMapOf<String, ServerEndpoint>()

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 450

            val targets = linkedSetOf(InetAddress.getByName("255.255.255.255"))
            wifiBroadcastAddress()?.let { address -> targets.add(address) }

            repeat(4) {
                for (target in targets) {
                    runCatching {
                        socket.send(DatagramPacket(message, message.size, target, DISCOVERY_PORT))
                    }
                }

                val deadline = System.currentTimeMillis() + 500L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val buffer = ByteArray(2048)
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)

                        val response = String(
                            packet.data,
                            packet.offset,
                            packet.length,
                            StandardCharsets.UTF_8
                        )
                        val json = JSONObject(response)
                        if (json.optString("type") != "SCAN2CELL_REPLY_V1") continue

                        val port = json.optInt("port", PHONE_PORT)
                        val serverId = json.optString("serverId")
                        val baseUrl = "http://${packet.address.hostAddress}:$port"

                        val endpoint = ServerEndpoint(
                            baseUrl = baseUrl,
                            name = json.optString("name", "Excel PC"),
                            serverId = serverId,
                            excelConnected = false
                        )

                        found[serverId.ifBlank { baseUrl }] = endpoint
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (_: Exception) {
                        // Ignore malformed/duplicate replies and keep collecting.
                    }
                }
            }
        }

        return found.values.toList()
    }

    private fun normalizeManualAddress(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        val withoutScheme = raw
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .trim()
        if (withoutScheme.isBlank()) return null
        return "http://$withoutScheme:$PHONE_PORT"
    }

    @Suppress("DEPRECATION")
    private fun wifiBroadcastAddress(): InetAddress? {
        return runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifi.dhcpInfo
                ?: return@runCatching null
            val broadcast =
                (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
            val bytes = ByteArray(4) { index ->
                ((broadcast shr (index * 8)) and 0xFF).toByte()
            }
            InetAddress.getByAddress(bytes)
        }.getOrNull()
    }

    companion object {
        private const val PHONE_PORT = 38473
        private const val DISCOVERY_PORT = 38474
    }
}
