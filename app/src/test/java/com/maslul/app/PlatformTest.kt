package com.maslul.app

import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitousApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformTest {
    @Test
    fun readsPlatformFromStopDescription() {
        // Real stop_desc values from Be'er Sheva central station.
        assertEquals("13", TransitousApi.platformOf("רחוב: יהושע חנקין עיר: באר שבע רציף: 13  קומה:"))
        assertEquals("7", TransitousApi.platformOf("רחוב:   עיר: באר שבע רציף: 7   קומה:"))
        assertEquals("3א", TransitousApi.platformOf("רחוב: הגדוד העברי עיר: תל אביב יפו רציף: 3א קומה: 6"))
    }

    @Test
    fun noPlatformWhenEmpty() {
        assertNull(TransitousApi.platformOf("רחוב: יצחק בן צבי 2 עיר: באר שבע רציף:  קומה:"))
        assertNull(TransitousApi.platformOf("רחוב: יצחק בן צבי 2 עיר: באר שבע רציף:"))
        assertNull(TransitousApi.platformOf(null))
    }

    @Test
    fun readsFloorButNotGroundLevel() {
        assertEquals("6", TransitousApi.floorOf("רחוב: הגדוד העברי עיר: תל אביב יפו רציף: 3 קומה: 6"))
        assertNull(TransitousApi.floorOf("רחוב: הרצל עיר: חולון רציף: 2 קומה: 0"))
        assertNull(TransitousApi.floorOf("רחוב: יהושע חנקין עיר: באר שבע רציף: 13  קומה:"))
    }

    @Test
    fun label() {
        fun stop(track: String?, floor: String?) = StopCall("s", "n", null, 0.0, 0.0, null, null, track, floor)
        assertEquals("Platform 13", stop("13", null).platformLabel)
        assertEquals("Platform 3, floor 6", stop("3", "6").platformLabel)
        assertEquals("Floor 6", stop(null, "6").platformLabel)
        assertNull(stop(null, null).platformLabel)
    }
}
