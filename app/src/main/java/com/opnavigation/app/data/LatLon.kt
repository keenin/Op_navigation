package com.opnavigation.app.data

/**
 * Accepts "lat, lon", "lat lon", "(lat, lon)", and Google Maps links that embed
 * `@lat,lon` or `q=lat,lon`.
 */
fun parseLatLon(raw: String): Pair<Double, Double>? {
    val text = raw.trim().replace("%2C", ",", ignoreCase = true)
    if (text.isEmpty()) return null

    val patterns = listOf(
        Regex("""@([+-]?\d{1,3}(?:\.\d+)?),([+-]?\d{1,3}(?:\.\d+)?)"""),
        Regex("""[?&]q=([+-]?\d{1,3}(?:\.\d+)?),([+-]?\d{1,3}(?:\.\d+)?)"""),
        Regex("""^[(\s]*([+-]?\d{1,3}(?:\.\d+)?)[\s,]+([+-]?\d{1,3}(?:\.\d+)?)[)\s]*$"""),
    )
    for (pattern in patterns) {
        val match = pattern.find(text) ?: continue
        val lat = match.groupValues[1].toDoubleOrNull() ?: continue
        val lon = match.groupValues[2].toDoubleOrNull() ?: continue
        if (lat in -90.0..90.0 && lon in -180.0..180.0) return lat to lon
    }
    return null
}

fun formatLatLon(latitude: Double, longitude: Double): String =
    "%.5f, %.5f".format(java.util.Locale.US, latitude, longitude)
