package com.opnavigation.app

import com.opnavigation.app.data.parseLatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatLonTest {
    @Test
    fun parsesCommaSeparated() {
        assertEquals(32.875134 to -117.209777, parseLatLon("32.875134, -117.209777"))
        assertEquals(32.875134 to -117.209777, parseLatLon("32.875134,-117.209777"))
    }

    @Test
    fun parsesSpaceSeparatedAndParentheses() {
        assertEquals(32.87 to -117.21, parseLatLon("32.87 -117.21"))
        assertEquals(32.87 to -117.21, parseLatLon("(32.87, -117.21)"))
    }

    @Test
    fun parsesGoogleMapsLinks() {
        assertEquals(
            32.875134 to -117.209777,
            parseLatLon("https://www.google.com/maps/@32.875134,-117.209777,17z"),
        )
        assertEquals(
            32.875134 to -117.209777,
            parseLatLon("https://maps.google.com/?q=32.875134,-117.209777"),
        )
        assertEquals(
            32.875134 to -117.209777,
            parseLatLon("https://maps.google.com/?q=32.875134%2C-117.209777"),
        )
    }

    @Test
    fun rejectsAddressesAndOutOfRange() {
        assertNull(parseLatLon("1600 Amphitheatre Parkway, Mountain View"))
        assertNull(parseLatLon("91, 0"))
        assertNull(parseLatLon("0, 181"))
        assertNull(parseLatLon(""))
        assertNull(parseLatLon("32.7, -117.2 extra"))
    }
}
