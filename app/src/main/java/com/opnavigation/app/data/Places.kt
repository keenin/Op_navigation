package com.opnavigation.app.data

import android.content.Context
import android.location.Geocoder
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class ResolvedPlace(
    val latitude: Double,
    val longitude: Double,
    val label: String,
)

class PlaceException(message: String) : Exception(message)

object Places {
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
    private const val USER_AGENT = "OPNav/1.0 (https://github.com/keenin/Op_navigation)"

    fun resolve(context: Context, raw: String): ResolvedPlace {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw PlaceException("Enter an address or latitude, longitude.")
        }
        parseLatLon(trimmed)?.let { (latitude, longitude) ->
            return ResolvedPlace(latitude, longitude, formatLatLon(latitude, longitude))
        }

        val geocoderError = try {
            geocodeWithAndroid(context, trimmed)?.let { return it }
            "Android Geocoder returned no results"
        } catch (e: Exception) {
            describeFailure(e)
        }

        return try {
            geocodeWithNominatim(trimmed)
        } catch (e: Exception) {
            val fallback = if (e is PlaceException) e.message ?: describeFailure(e) else describeFailure(e)
            throw PlaceException("Couldn't look up that address. Geocoder: $geocoderError. Nominatim: $fallback")
        }
    }

    private fun geocodeWithAndroid(context: Context, query: String): ResolvedPlace? {
        if (!Geocoder.isPresent()) {
            throw PlaceException("Android Geocoder is not present on this phone")
        }
        @Suppress("DEPRECATION")
        val hits = Geocoder(context, Locale.getDefault()).getFromLocationName(query, 1)
        val hit = hits?.firstOrNull() ?: return null
        val latitude = hit.latitude
        val longitude = hit.longitude
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            throw PlaceException("Android Geocoder returned coordinates openpilot would reject")
        }
        val label = hit.getAddressLine(0)?.takeIf { it.isNotBlank() } ?: query
        return ResolvedPlace(latitude, longitude, label)
    }

    private fun geocodeWithNominatim(query: String): ResolvedPlace {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val connection = (URL("$NOMINATIM?q=$encoded&format=jsonv2&limit=1").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw PlaceException(httpFailure("Nominatim lookup failed", status, text))
            }
            val array = try {
                JSONArray(text)
            } catch (e: Exception) {
                throw PlaceException("Nominatim returned unreadable JSON (${describeFailure(e)})")
            }
            if (array.length() == 0) {
                throw PlaceException("Nominatim found no match")
            }
            val item = array.getJSONObject(0)
            val latitude = item.optString("lat").toDoubleOrNull()
            val longitude = item.optString("lon").toDoubleOrNull()
            if (latitude == null || longitude == null || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
                throw PlaceException("Nominatim returned coordinates openpilot would reject")
            }
            val label = item.optString("display_name").ifBlank { query }
            return ResolvedPlace(latitude, longitude, label)
        } finally {
            connection.disconnect()
        }
    }
}
