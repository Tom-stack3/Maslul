package com.maslul.app

import com.maslul.app.data.AppLanguage
import com.maslul.app.data.TransitMode
import com.maslul.app.i18n.L10n
import org.junit.Assert.assertEquals
import org.junit.Test

class TransitModeTest {
    private val en = L10n.of(AppLanguage.EN)
    private val he = L10n.of(AppLanguage.HE)
    private val ru = L10n.of(AppLanguage.RU)

    @Test
    fun hurryLabelNamesTheVehicle() {
        // "Hurry · train is close", not "bus is close", when the ride is a train or the light rail.
        assertEquals("bus is close", en.vehicleClose(TransitMode.BUS))
        assertEquals("train is close", en.vehicleClose(TransitMode.TRAIN))
        assertEquals("train is close", en.vehicleClose(TransitMode.LIGHT_RAIL))
        // A bus is masculine in Hebrew, a train feminine.
        assertEquals("האוטובוס קרוב", he.vehicleClose(TransitMode.BUS))
        assertEquals("הרכבת קרובה", he.vehicleClose(TransitMode.TRAIN))
        assertEquals("трамвай близко", ru.vehicleClose(TransitMode.LIGHT_RAIL))
    }

    @Test
    fun aTrainWithoutANumberReadsAsTheTrain() {
        assertEquals("Tight transfer · 3 min to catch the train", en.tightTransfer(3, en.theVehicle(TransitMode.TRAIN), null))
        assertEquals("If missed, the next train at 21:40 (+30 min)", en.ifMissed(en.theNextVehicle(TransitMode.TRAIN), "21:40", "30 min"))
        assertEquals("If missed, next 143 at 21:40 (+30 min)", en.ifMissed(en.nextLine("143"), "21:40", "30 min"))
        assertEquals("אם תפספסו, הרכבת הבאה ב־21:40 (+30 דק׳)", he.ifMissed(he.theNextVehicle(TransitMode.TRAIN), "21:40", "30 דק׳"))
    }

    @Test
    fun ridesOfOneModeUseItsWord() {
        assertEquals("Next trains from A", en.nextFrom(TransitMode.LIGHT_RAIL, "A"))
        assertEquals("Next buses from A", en.nextFrom(TransitMode.BUS, "A"))
        assertEquals("Next ferries from A", en.nextFrom(TransitMode.FERRY, "A"))
        // A mix of modes.
        assertEquals("Next rides from A", en.nextFrom(null, "A"))
        assertEquals("האוטובוסים הבאים מ־A", he.nextFrom(TransitMode.BUS, "A"))
    }
}
