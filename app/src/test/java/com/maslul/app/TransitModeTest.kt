package com.maslul.app

import com.maslul.app.data.Leg
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TransitModeTest {
    private fun ride(mode: TransitMode): Leg {
        val s = StopCall("s", "S", null, 32.0, 34.8, null, null)
        return Leg(mode, s, s, Instant.EPOCH, Instant.EPOCH, null, null, "1", null, null, null, null, null, null, emptyList(), emptyList())
    }

    @Test
    fun hurryLabelNamesTheVehicle() {
        // "Hurry · train is close", not "bus is close", when the ride is a train or the light rail.
        assertEquals("bus", TransitMode.BUS.vehicle)
        assertEquals("train", TransitMode.TRAIN.vehicle)
        assertEquals("train", TransitMode.LIGHT_RAIL.vehicle)
        assertEquals("ferries", TransitMode.FERRY.vehicles)
    }

    @Test
    fun ridesOfOneModeUseItsWord() {
        assertEquals("trains", TransitMode.vehiclesOf(listOf(ride(TransitMode.LIGHT_RAIL), ride(TransitMode.LIGHT_RAIL))))
        assertEquals("buses", TransitMode.vehiclesOf(listOf(ride(TransitMode.BUS))))
        assertEquals("rides", TransitMode.vehiclesOf(listOf(ride(TransitMode.BUS), ride(TransitMode.TRAIN))))
    }
}
