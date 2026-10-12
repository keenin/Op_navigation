package com.opnavigation.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

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
        if (token.length >= 2 &&
            ((token.startsWith("\"") && token.endsWith("\"")) ||
                (token.startsWith("'") && token.endsWith("'")))
        ) {
            token = token.substring(1, token.length - 1).trim()
        }
        val bearer = token.length > 3 &&
            token.regionMatches(0, "JWT", 0, 3, ignoreCase = true) &&
            token[3].isWhitespace()
        if (bearer) {
            token = token.substring(3).trim()
        }
        return token.replace(Regex("\\s+"), "")
    }

    fun normalizeDongle(raw: String): String = raw.trim().trim('/').lowercase()

    fun athenaUrl(dongleId: String): String = "$ATHENA_HOST/${normalizeDongle(dongleId)}"

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
            request(
                method = "GET",
                url = "$API_HOST/v1/me/devices/",
                token = token,
                body = null,
                connectTimeoutMs = 15_000,
                readTimeoutMs = 20_000,
            )
        } catch (e: Exception) {
            throw IllegalStateException("Device list failed: ${describeFailure(e)}", e)
        }
        if (status == 401 || status == 403) {
            throw IllegalStateException(httpFailure(jwtRejected(), status, text))
        }
        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            val message = jsonErrorMessage(text.toJsonObjectOrNull())
            val summary = message ?: "Couldn't list devices (${describeFailure(e)})"
            throw IllegalStateException(httpFailure(summary, status, text))
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
        val url = athenaUrl(dongleId)
        val (status, text) = try {
            request(
                method = "POST",
                url = url,
                token = token,
                body = body.toString(),
                connectTimeoutMs = 20_000,
                // Athena holds the HTTP call open while it waits for the device.
                readTimeoutMs = 60_000,
            )
        } catch (e: Exception) {
            val hint = if (e is SocketTimeoutException) {
                "Timed out waiting for comma or the device. "
            } else {
                ""
            }
            return AthenaResult(false, "${hint}Athena $url failed: ${describeFailure(e)}")
        }
        return interpretAthena(status, text, okMessage)
    }

    private fun request(
        method: String,
        url: String,
        token: String,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Pair<Int, String> {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection)
        try {
            // A 301/302 would turn this POST into a GET and drop the body.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "JWT $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "OpenpilotTools")
            if (bytes != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val location = connection.getHeaderField("Location").orEmpty()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status in 300..399 && text.isBlank()) {
                val where = location.ifBlank { "(no Location header)" }
                return status to "redirect to $where"
            }
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
    fun fail(summary: String) = AthenaResult(false, httpFailure(summary, statusCode, text))

    if (statusCode == 401 || statusCode == 403) {
        return fail(jwtRejected())
    }
    if (statusCode == 404) {
        return fail("comma doesn't recognize that dongle ID. Check it in Settings.")
    }
    if (statusCode in 300..399) {
        return fail("Athena redirected this POST. The request URL should be https://athena.comma.ai/<dongle_id> with no trailing slash.")
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
        return fail(message)
    }
    if (errorMessage != null && errorMessage.contains("method not found", ignoreCase = true)) {
        return fail("This openpilot build has no nav alpha. Update the device to a build with setNavDestination.")
    }
    if (result is JSONObject) {
        val err = result.optString("error").takeIf { it.isNotBlank() }
        if (err != null) return fail(err)
        if (!isSuccessFlag(result.opt("success"))) {
            return fail("The device rejected the request.")
        }
    }
    if (!errorMessage.isNullOrBlank()) return fail(errorMessage)
    if (result is String && result.isNotBlank()) return fail(result)
    if (statusCode !in 200..299) {
        return fail("comma rejected the request")
    }
    return fail("Unexpected response from comma")
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

private fun String.toJsonObjectOrNull(): JSONObject? =
    try {
        if (startsWith("{")) JSONObject(this) else null
    } catch (_: Exception) {
        null
    }
