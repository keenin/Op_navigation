package com.opnavigation.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

data class CommaDevice(
    val dongleId: String,
    val alias: String,
    val lastAthenaPingSeconds: Long?,
)

data class AthenaResult(val ok: Boolean, val message: String)

/**
 * openpilot nav alpha (athenad.py, commit 4c362ca) registers
 * `setNavDestination(latitude, longitude)` and `clearNavDestination()`.
 * api.comma.ai documents the transport as POST https://athena.comma.ai/:dongle_id
 * with `Authorization: JWT <token>`. The published OpenAPI schema types the
 * params as the older NavigationDestination object; the device method accepts
 * only latitude and longitude, so that is what we send.
 */
object CommaClient {
    private const val ATHENA_HOST = "https://athena.comma.ai"
    private const val API_HOST = "https://api.commadotai.com"

    fun normalizeToken(raw: String): String {
        var token = raw.trim()
        if (token.startsWith("JWT ", ignoreCase = true)) {
            token = token.substring(4).trim()
        }
        if (token.length >= 2 &&
            ((token.startsWith("\"") && token.endsWith("\"")) ||
                (token.startsWith("'") && token.endsWith("'")))
        ) {
            token = token.substring(1, token.length - 1).trim()
        }
        return token
    }

    fun normalizeDongle(raw: String): String = raw.trim().lowercase()

    fun setDestination(token: String, dongleId: String, latitude: Double, longitude: Double): AthenaResult {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return AthenaResult(false, "Those coordinates are outside the range openpilot accepts.")
        }
        val body = JSONObject()
            .put("method", "setNavDestination")
            .put(
                "params",
                JSONObject()
                    .put("latitude", latitude)
                    .put("longitude", longitude),
            )
            .put("jsonrpc", "2.0")
            .put("id", 0)
        return call(token, dongleId, body, "Destination sent.")
    }

    fun clearDestination(token: String, dongleId: String): AthenaResult {
        val body = JSONObject()
            .put("method", "clearNavDestination")
            .put("jsonrpc", "2.0")
            .put("id", 0)
        return call(token, dongleId, body, "Destination cleared.")
    }

    fun listDevices(token: String): List<CommaDevice> {
        val (status, text) = try {
            request("GET", "$API_HOST/v1/me/devices/", token, null)
        } catch (e: Exception) {
            throw IllegalStateException(networkMessage(e), e)
        }
        if (status == 401 || status == 403) {
            throw IllegalStateException(jwtRejected())
        }
        val array = try {
            JSONArray(text)
        } catch (_: Exception) {
            val message = jsonErrorMessage(text.toJsonObjectOrNull())
            throw IllegalStateException(message ?: "Couldn't list devices (HTTP $status).")
        }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val dongleId = item.optString("dongle_id").trim().lowercase()
                if (dongleId.isEmpty()) continue
                val ping = if (item.has("last_athena_ping") && !item.isNull("last_athena_ping")) {
                    val raw = item.optLong("last_athena_ping")
                    when {
                        raw <= 0L -> null
                        raw > 10_000_000_000L -> raw / 1000
                        else -> raw
                    }
                } else {
                    null
                }
                add(
                    CommaDevice(
                        dongleId = dongleId,
                        alias = item.optString("alias").trim(),
                        lastAthenaPingSeconds = ping,
                    )
                )
            }
        }
    }

    private fun call(token: String, dongleId: String, body: JSONObject, okMessage: String): AthenaResult {
        val (status, text) = try {
            request("POST", "$ATHENA_HOST/$dongleId", token, body.toString())
        } catch (e: Exception) {
            return AthenaResult(false, networkMessage(e))
        }
        return interpretAthena(status, text, okMessage)
    }

    private fun request(method: String, url: String, token: String, body: String?): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("Authorization", "JWT $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OpenpilotTools")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return status to text
        } finally {
            connection.disconnect()
        }
    }
}

internal fun interpretAthena(statusCode: Int, body: String, okMessage: String): AthenaResult {
    val text = body.trim()
    val json = text.toJsonObjectOrNull()
    val result = if (json != null && json.has("result") && !json.isNull("result")) json.get("result") else null
    if (statusCode in 200..299 && result is JSONObject && isSuccessFlag(result.opt("success"))) {
        return AthenaResult(true, okMessage)
    }
    if (statusCode == 401 || statusCode == 403) {
        return AthenaResult(false, jwtRejected())
    }
    if (statusCode == 404) {
        return AthenaResult(false, "comma doesn't recognize that dongle ID. Check it in Settings.")
    }

    val errorMessage = jsonErrorMessage(json)
    val resultText = when (result) {
        is String -> result
        is JSONObject -> result.optString("error")
        else -> ""
    }
    val haystack = "$text ${errorMessage.orEmpty()} $resultText"
    if (isOffline(statusCode, haystack)) {
        val queued = haystack.contains("queued", ignoreCase = true)
        val message = if (queued) {
            "Device is offline. comma queued this and will deliver it when the device connects."
        } else {
            "Device is offline. Turn the car on so openpilot is connected, then try again."
        }
        return AthenaResult(false, message)
    }
    if (errorMessage != null && errorMessage.contains("method not found", ignoreCase = true)) {
        return AthenaResult(
            false,
            "This openpilot build has no nav alpha. Update the device to a build with setNavDestination.",
        )
    }
    if (result is JSONObject) {
        val err = result.optString("error").takeIf { it.isNotBlank() }
        if (err != null) return AthenaResult(false, err)
        if (!isSuccessFlag(result.opt("success"))) {
            return AthenaResult(false, "The device rejected the request.")
        }
    }
    if (!errorMessage.isNullOrBlank()) return AthenaResult(false, errorMessage)
    if (result is String && result.isNotBlank()) return AthenaResult(false, result)
    if (statusCode !in 200..299) {
        val detail = text.take(140)
        val suffix = if (detail.isBlank()) "" else ": $detail"
        return AthenaResult(false, "comma returned HTTP $statusCode$suffix")
    }
    return AthenaResult(false, "Unexpected response from comma.")
}

private fun isSuccessFlag(value: Any?): Boolean = when (value) {
    is Boolean -> value
    is Number -> value.toInt() == 1
    is String -> value == "1" || value.equals("true", ignoreCase = true)
    else -> false
}

private fun isOffline(statusCode: Int, text: String): Boolean {
    if (statusCode == 502 || statusCode == 504) return true
    val lowered = text.lowercase()
    return "offline" in lowered ||
        "not connected" in lowered ||
        "not online" in lowered ||
        "no responders" in lowered
}

private fun jsonErrorMessage(json: JSONObject?): String? {
    if (json == null) return null
    val description = json.optString("description").takeIf { it.isNotBlank() }
    if (!json.has("error") || json.isNull("error")) return description
    val parsed = when (val err = json.get("error")) {
        is JSONObject -> {
            err.optString("message").ifBlank { err.optString("description") }.ifBlank { err.toString() }
        }
        is String -> err
        else -> err.toString()
    }.takeIf { it.isNotBlank() && it != "null" }
    return description ?: parsed
}

private fun jwtRejected(): String =
    "JWT was rejected. Open jwt.comma.ai, sign in with the account you use for Connect, and paste a new token."

private fun networkMessage(error: Exception): String = when (error) {
    is SocketTimeoutException ->
        "The device didn't answer. It is probably offline — turn the car on so openpilot is connected, then try again."
    is UnknownHostException, is ConnectException ->
        "Couldn't reach comma. Check this phone's connection and try again."
    else -> error.message?.takeIf { it.isNotBlank() } ?: "Network error."
}

private fun String.toJsonObjectOrNull(): JSONObject? =
    try {
        if (startsWith("{")) JSONObject(this) else null
    } catch (_: Exception) {
        null
    }
