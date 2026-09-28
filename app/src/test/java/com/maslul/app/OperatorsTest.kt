package com.maslul.app

import com.maslul.app.data.Operators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorsTest {

    private fun key(id: String?, name: String?) = Operators.find(id, name)?.key

    @Test
    fun matchesHebrewNamesFromTheFeed() {
        assertEquals("egged", key(null, "אגד"))
        assertEquals("dan", key(null, "דן"))
        assertEquals("metropoline", key(null, "מטרופולין"))
        assertEquals("kavim", key(null, "קווים"))
        assertEquals("superbus", key(null, "סופרבוס"))
        assertEquals("electra-afikim", key(null, "אלקטרה אפיקים"))
        assertEquals("electra-afikim-transport", key(null, "אלקטרה אפיקים תחבורה"))
        assertEquals("extra", key(null, "אקסטרה"))
        assertEquals("extra-jerusalem", key(null, "אקסטרה ירושלים"))
        assertEquals("tnufa", key(null, "תנופה"))
        assertEquals("nateev-express", key(null, "נתיב אקספרס"))
        assertEquals("golan", key(null, "מועצה אזורית גולן"))
        assertEquals("galim", key(null, "גלים"))
        assertEquals("beit-shemesh-express", key(null, "בית שמש אקספרס"))
        assertEquals("dan-badarom", key(null, "דן בדרום"))
        assertEquals("dan-beer-sheva", key(null, "דן באר שבע"))
        assertEquals("dan-netivim", key(null, "דן נתיבים בעמ"))
        assertEquals("nazareth-tourism", key(null, "נסיעות ותיירות"))
        assertEquals("jerusalem-lrt", key(null, "כפיר"))
        assertEquals("tavlit", key(null, "תבל"))
        assertEquals("israel-railways", key(null, "רכבת ישראל"))
        assertEquals("sam", key(null, "ש.א.מ"))
        assertEquals("gb-tours", key(null, "גי.בי.טורס"))
        assertEquals("derech-egged", key(null, "דרך אגד עוטף ירושלים"))
        assertEquals("east-jerusalem", key(null, "ירושלים-רמאללה איחוד"))
        assertEquals("sherut", key(null, "מוניות מטרו קו"))
    }

    @Test
    fun matchesEnglishNamesLoosely() {
        assertEquals("metropoline", key(null, "Metropoline"))
        assertEquals("metropoline", key(null, "  METROPOLINE Ltd. "))
        assertEquals("egged", key(null, "Egged"))
        assertEquals("egged-taavura", key(null, "Egged Taavura"))
        assertEquals("dan-badarom", key(null, "Dan Badarom"))
        assertEquals("israel-railways", key(null, "Israel Railways"))
        assertEquals("jerusalem-lrt", key(null, "CityPass"))
        assertEquals("nazareth-tourism", key(null, "Nazareth Transport and Tourism"))
        assertEquals("sam", key(null, "S.A.M"))
    }

    @Test
    fun fallsBackToAgencyId() {
        assertEquals("metropoline", key("15", null))
        assertEquals("egged", key("3", ""))
        assertEquals("dan", key("5", "unknown co"))
        assertEquals("israel-railways", key("il-Israel-MOT_2", null))
        assertEquals("kavim", Operators.findByRef(18L, "")?.key)
        assertNull(key("9999", "Some Other Bus"))
        assertNull(key(null, null))
        assertNull(Operators.findByRef(0L, ""))
    }

    @Test
    fun nameWinsOverReusedId() {
        // id 4 used to be Egged Taavura; the name is authoritative.
        assertEquals("egged-taavura", key("4", "אגד תעבורה"))
        assertEquals("electra-afikim-transport", key("4", null))
    }

    @Test
    fun doesNotMatchInsideOtherWords() {
        // "דן" must not match inside "ירדן", nor "Dan" inside "Danish".
        assertNull(key(null, "ירדן"))
        assertNull(key(null, "Danish Buses"))
    }

    @Test
    fun brandColours() {
        assertEquals(0xFFF28C00.toInt(), Operators.colorOf("15", "מטרופולין"))
        assertEquals(0xFF00913F.toInt(), Operators.colorOf(null, "אגד"))
        assertEquals(0xFF0067C5.toInt(), Operators.colorOf(null, "דן"))
        assertNull(Operators.colorOf(null, "nobody"))
        // Major operators should be distinguishable from each other.
        val majors = listOf("אגד", "דן", "מטרופולין", "קווים", "סופרבוס", "אלקטרה אפיקים", "אקסטרה", "רכבת ישראל")
            .map { Operators.colorOf(null, it) }
        assertEquals(majors.size, majors.toSet().size)
        assertNotEquals(Operators.colorOf(null, "דן"), Operators.colorOf(null, "רכבת ישראל"))
    }

    @Test
    fun readableTextColour() {
        assertTrue(Operators.prefersDarkText(0xFFF28C00.toInt())) // Metropoline orange -> dark text
        assertTrue(Operators.prefersDarkText(0xFFF5C400.toInt())) // yellow -> dark text
        assertFalse(Operators.prefersDarkText(0xFF00913F.toInt())) // Egged green -> white
        assertFalse(Operators.prefersDarkText(0xFF0067C5.toInt())) // Dan blue -> white
        assertFalse(Operators.prefersDarkText(0xFF1C3F94.toInt())) // Railways navy -> white
        // Every brand colour gets a text colour with at least 3:1 contrast.
        for (o in Operators.all) {
            val white = Operators.whiteContrast(o.color)
            val black = (Operators.luminance(o.color) + 0.05) / 0.05
            val chosen = if (Operators.prefersDarkText(o.color)) black else white
            assertTrue("${o.key} contrast $chosen", chosen >= 3.0)
        }
    }
}
