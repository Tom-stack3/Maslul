package com.maslul.app

import com.maslul.app.data.AddressSearch
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressSearchTest {
    private val q = AddressSearch.parse("המגינים 14 פתח תקווה")

    @Test
    fun splitsHouseNumber() {
        assertEquals("14", q.number)
        assertEquals("המגינים פתח תקווה", q.rest)
        assertEquals("14א", AddressSearch.parse("הרצל 14א, ראשון לציון").number)
        assertNull(AddressSearch.parse("עזריאלי").number)
        assertNull(AddressSearch.parse("480").number)
        assertEquals(AddressSearch.norm("פתח תקווה"), AddressSearch.norm("פתח-תקוה"))
    }

    // What the geocoders actually return for this query: other streets' no. 14, and the street.
    private val results = listOf(
        Place("גן המגינים", 32.17, 34.9, "כפר סבא", PlaceKind.POI),
        Place("האורנים 14", 32.09, 34.88, "פתח תקווה", PlaceKind.ADDRESS),
        Place("המגינים 14", 32.79, 35.53, "טבריה", PlaceKind.ADDRESS),
        Place("המגינים", 32.18, 34.87, "רעננה", PlaceKind.POI),
        Place("המגינים", 32.0884, 34.8964, "פתח תקווה", PlaceKind.POI),
    )

    @Test
    fun fallsBackToTheStreetInTheRightCity() {
        val out = AddressSearch.resolve(q, results)!!
        val top = out.first()
        assertEquals("המגינים 14", top.name)
        assertEquals(32.0884, top.lat, 1e-6)
        assertTrue(top.subtitle!!.contains("not on the map"))
    }

    @Test
    fun prefersExactAddress() {
        val exact = Place("המגינים 14", 32.0880, 34.8960, "פתח תקוה", PlaceKind.ADDRESS)
        val out = AddressSearch.resolve(q, results + exact)!!
        assertEquals(exact, out.first())
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(AddressSearch.resolve(q, results.filter { it.subtitle != "פתח תקווה" || it.kind == PlaceKind.ADDRESS }))
    }
}
