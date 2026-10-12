package com.opnavigation.app.data

import android.content.Context
import android.location.Geocoder
import java.io.IOException
import java.util.Locale

data class ResolvedPlace(
    val latitude: Double,
    val longitude: Double,
    val label: String,
)

class PlaceException(message: String) : Exception(message)

object Places {
    fun resolve(context: Context, raw: String): ResolvedPlace {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw PlaceException("Enter an address or latitude, longitude.")
        }
        parseLatLon(trimmed)?.let { (latitude, longitude) ->
            return ResolvedPlace(latitude, longitude, formatLatLon(latitude, longitude))
        }
        if (!Geocoder.isPresent()) {
            throw PlaceException("This phone has no geocoder. Paste latitude, longitude instead.")
        }
        val hits = try {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocationName(trimmed, 1)
        } catch (e: IOException) {
            throw PlaceException("Couldn't look up that address. Check your connection, or paste latitude, longitude.")
        }
        val hit = hits?.firstOrNull()
            ?: throw PlaceException("No match for that address. Try a fuller address, or paste latitude, longitude.")
        val latitude = hit.latitude
        val longitude = hit.longitude
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            throw PlaceException("Geocoder returned coordinates openpilot would reject.")
        }
        val label = hit.getAddressLine(0)?.takeIf { it.isNotBlank() } ?: trimmed
        return ResolvedPlace(latitude, longitude, label)
    }
}
