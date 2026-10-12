package com.opnavigation.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SecureStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = open(appContext)

    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    var dongleId: String
        get() = prefs.getString(KEY_DONGLE, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DONGLE, value).apply()
        }

    fun loadFavorites(): List<Favorite> {
        val raw = prefs.getString(KEY_FAVORITES, null) ?: return emptyFavorites()
        return try {
            val array = JSONArray(raw)
            val loaded = buildList {
                for (i in 0 until array.length().coerceAtMost(FAVORITE_COUNT)) {
                    val item = array.optJSONObject(i) ?: JSONObject()
                    val latitude = item.optDoubleOrNull("latitude")
                    val longitude = item.optDoubleOrNull("longitude")
                    add(
                        Favorite(
                            name = item.optString("name"),
                            query = item.optString("query"),
                            latitude = latitude,
                            longitude = longitude,
                        )
                    )
                }
            }
            List(FAVORITE_COUNT) { index -> loaded.getOrElse(index) { Favorite() } }
        } catch (_: Exception) {
            emptyFavorites()
        }
    }

    fun saveFavorites(favorites: List<Favorite>) {
        val array = JSONArray()
        favorites.take(FAVORITE_COUNT).forEach { favorite ->
            array.put(
                JSONObject().apply {
                    put("name", favorite.name)
                    put("query", favorite.query)
                    if (favorite.latitude != null && favorite.longitude != null) {
                        put("latitude", favorite.latitude)
                        put("longitude", favorite.longitude)
                    }
                }
            )
        }
        prefs.edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    private companion object {
        const val FILE_NAME = "op_nav_secure"
        const val KEY_TOKEN = "jwt"
        const val KEY_DONGLE = "dongle_id"
        const val KEY_FAVORITES = "favorites"

        fun open(context: Context): SharedPreferences {
            try {
                return create(context)
            } catch (_: Exception) {
                File(context.applicationInfo.dataDir, "shared_prefs/$FILE_NAME.xml").delete()
                return create(context)
            }
        }

        fun create(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val value = optDouble(name, Double.NaN)
    return value.takeIf { it.isFinite() }
}
