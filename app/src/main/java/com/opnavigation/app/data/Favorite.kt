package com.opnavigation.app.data

data class Favorite(
    val name: String = "",
    val query: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val isSet: Boolean get() = latitude != null && longitude != null

    fun label(index: Int): String = name.trim().ifBlank { "Favorite ${index + 1}" }
}

const val FAVORITE_COUNT = 5

fun emptyFavorites(): List<Favorite> = List(FAVORITE_COUNT) { Favorite() }
