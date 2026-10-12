package com.opnavigation.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opnavigation.app.data.CommaClient
import com.opnavigation.app.data.CommaDevice
import com.opnavigation.app.data.Favorite
import com.opnavigation.app.data.PlaceException
import com.opnavigation.app.data.Places
import com.opnavigation.app.data.SecureStore
import com.opnavigation.app.data.emptyFavorites
import com.opnavigation.app.data.formatLatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NavViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)

    var token by mutableStateOf(store.token)
        private set

    var dongleId by mutableStateOf(store.dongleId)
        private set

    val favorites = mutableStateListOf<Favorite>().apply {
        addAll(store.loadFavorites().ifEmpty { emptyFavorites() })
    }

    var busy by mutableStateOf(false)
        private set

    var devicesLoading by mutableStateOf(false)
        private set

    var devicesError by mutableStateOf<String?>(null)
        private set

    val devices = mutableStateListOf<CommaDevice>()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts = _toasts.asSharedFlow()

    fun updateToken(value: String) {
        token = value
        store.token = value
    }

    fun updateDongleId(value: String) {
        dongleId = value
        store.dongleId = value
    }

    fun selectDevice(device: CommaDevice) {
        updateDongleId(device.dongleId)
        toast("Using ${device.alias.ifBlank { device.dongleId }}")
    }

    fun sendFavorite(index: Int) {
        val favorite = favorites.getOrNull(index) ?: return
        val latitude = favorite.latitude
        val longitude = favorite.longitude
        if (latitude == null || longitude == null) {
            toast("Long-press ${favorite.label(index)} to set an address.")
            return
        }
        val (jwt, dongle) = credentials() ?: return
        launchRequest {
            val result = CommaClient.setDestination(jwt, dongle, latitude, longitude)
            if (result.ok) {
                "Sent ${favorite.label(index)} · ${formatLatLon(latitude, longitude)}"
            } else {
                result.message
            }
        }
    }

    fun sendAddress(raw: String, onSuccess: () -> Unit) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            toast("Enter an address or latitude, longitude.")
            return
        }
        val (jwt, dongle) = credentials() ?: return
        launchRequest {
            val place = try {
                withContext(Dispatchers.IO) { Places.resolve(getApplication(), trimmed) }
            } catch (e: PlaceException) {
                return@launchRequest e.message ?: "Couldn't resolve that place."
            }
            val result = CommaClient.setDestination(jwt, dongle, place.latitude, place.longitude)
            if (result.ok) {
                onSuccess()
                "Sent ${place.label}"
            } else {
                result.message
            }
        }
    }

    fun clearDestination() {
        val (jwt, dongle) = credentials() ?: return
        launchRequest {
            val result = CommaClient.clearDestination(jwt, dongle)
            if (result.ok) "Destination cleared." else result.message
        }
    }

    suspend fun saveFavorite(index: Int, name: String, query: String): String? {
        val current = favorites.getOrNull(index) ?: return "That favorite is missing."
        val trimmedName = name.trim()
        val trimmedQuery = query.trim()
        val updated = if (trimmedQuery.isEmpty()) {
            Favorite(name = trimmedName, query = "", latitude = null, longitude = null)
        } else if (trimmedQuery == current.query && current.isSet) {
            current.copy(name = trimmedName)
        } else {
            val place = try {
                withContext(Dispatchers.IO) { Places.resolve(getApplication(), trimmedQuery) }
            } catch (e: PlaceException) {
                return e.message
            } catch (e: Exception) {
                return e.message ?: "Couldn't resolve that place."
            }
            Favorite(
                name = trimmedName,
                query = trimmedQuery,
                latitude = place.latitude,
                longitude = place.longitude,
            )
        }
        favorites[index] = updated
        store.saveFavorites(favorites.toList())
        val latitude = updated.latitude
        val longitude = updated.longitude
        if (latitude != null && longitude != null) {
            toast("Saved ${updated.label(index)} · ${formatLatLon(latitude, longitude)}")
        } else {
            toast("Cleared ${updated.label(index)}")
        }
        return null
    }

    fun loadDevices() {
        val jwt = CommaClient.normalizeToken(token)
        if (jwt.isEmpty()) {
            devicesError = "Paste a JWT first. Get one at jwt.comma.ai."
            return
        }
        if (devicesLoading) return
        devicesLoading = true
        devicesError = null
        viewModelScope.launch {
            try {
                val listed = withContext(Dispatchers.IO) { CommaClient.listDevices(jwt) }
                devices.clear()
                devices.addAll(listed)
                if (listed.isEmpty()) {
                    devicesError = "No devices on this account."
                }
            } catch (e: Exception) {
                devicesError = e.message ?: "Couldn't list devices."
            } finally {
                devicesLoading = false
            }
        }
    }

    private fun credentials(): Pair<String, String>? {
        val jwt = CommaClient.normalizeToken(token)
        val dongle = CommaClient.normalizeDongle(dongleId)
        if (jwt.isEmpty()) {
            toast("Add your comma JWT in Settings. Get it at jwt.comma.ai.")
            return null
        }
        if (!DONGLE.matches(dongle)) {
            toast("Add a 16-character dongle ID in Settings.")
            return null
        }
        return jwt to dongle
    }

    private fun launchRequest(block: suspend () -> String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: Exception) {
                e.message ?: "Something went wrong."
            }
            busy = false
            toast(message)
        }
    }

    private fun toast(message: String) {
        _toasts.tryEmit(message)
    }

    private companion object {
        val DONGLE = Regex("^[0-9a-f]{16}$")
    }
}
