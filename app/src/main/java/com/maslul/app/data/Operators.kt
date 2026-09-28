package com.maslul.app.data

/**
 * Israeli public-transport operators and their brand colours, so every line badge, route
 * rail and map line can be told apart by company (Egged green, Dan blue, Metropoline
 * orange, …). The MOT GTFS feed carries no route_color, so this is the main colour source.
 *
 * Keys are the MOT GTFS agency_id (== Stride operator_ref == Transitous agencyId) plus the
 * agency name in Hebrew and English. Names win over ids because ids get reused when a
 * franchise changes hands (e.g. 4 was Egged Taavura, now Electra Afikim Transport).
 */
object Operators {

    /** Legal-form words ignored when matching names. */
    private val Noise = setOf("בעמ", "ltd", "inc", "company", "co", "חברת", "the")

    data class Operator(
        val key: String,
        /** ARGB brand colour. */
        val color: Int,
        val ids: Set<String> = emptySet(),
        val names: List<String> = emptyList(),
    )

    val all: List<Operator> = listOf(
        op("egged", 0xFF00913F, ids = setOf("3"), "אגד", "Egged"),
        op("egged-taavura", 0xFF5E9732, ids = setOf(), "אגד תעבורה", "Egged Taavura", "Egged Transport"),
        op("derech-egged", 0xFF2E7D32, ids = setOf("135"), "דרך אגד עוטף ירושלים", "דרך אגד", "Derech Egged"),
        op("dan", 0xFF0067C5, ids = setOf("5"), "דן", "Dan"),
        op("dan-badarom", 0xFF2F80D0, ids = setOf("31"), "דן בדרום", "Dan Badarom", "Dan South", "Dan in the South"),
        op("dan-beer-sheva", 0xFF1E88B8, ids = setOf("32"), "דן באר שבע", "Dan Beer Sheva", "Dan Be'er Sheva"),
        op("dan-netivim", 0xFF3A6FB7, ids = setOf("39"), "דן נתיבים", "Dan Netivim"),
        op("metropoline", 0xFFF28C00, ids = setOf("15"), "מטרופולין", "Metropoline", "Metropolin"),
        op("kavim", 0xFF00A99D, ids = setOf("18"), "קווים", "Kavim"),
        op("superbus", 0xFFD71920, ids = setOf("16"), "סופרבוס", "Superbus", "Super Bus"),
        op("electra-afikim", 0xFF7B2D8E, ids = setOf("25"), "אלקטרה אפיקים", "Electra Afikim"),
        op("electra-afikim-transport", 0xFF9C4DB0, ids = setOf("4"), "אלקטרה אפיקים תחבורה", "Electra Afikim Transport"),
        op("afikim", 0xFF7B2D8E, ids = setOf(), "אפיקים", "Afikim"),
        op("extra", 0xFFE4007C, ids = setOf("37"), "אקסטרה", "Extra"),
        op("extra-jerusalem", 0xFFC2186B, ids = setOf("38"), "אקסטרה ירושלים", "Extra Jerusalem"),
        op("tnufa", 0xFF00B3E3, ids = setOf("34"), "תנופה", "Tnufa"),
        op("nateev-express", 0xFFF5C400, ids = setOf("14"), "נתיב אקספרס", "Nateev Express", "Nativ Express"),
        op("golan", 0xFF8D6E3F, ids = setOf("24"), "מועצה אזורית גולן", "Golan Regional Council", "Golan"),
        op("galim", 0xFF1976D2, ids = setOf("23"), "גלים", "Galim"),
        op("beit-shemesh-express", 0xFF9E1B32, ids = setOf("35"), "בית שמש אקספרס", "Beit Shemesh Express"),
        op("nazareth-tourism", 0xFFB07B2C, ids = setOf("7"), "נסיעות ותיירות", "נסיעות ותיירות נצרת",
            "Nazareth Transport and Tourism", "Nazareth Travel and Tourism", "Nazareth Tourism"),
        op("gb-tours", 0xFF7CB342, ids = setOf("8"), "גי.בי.טורס", "GB Tours", "G.B. Tours"),
        op("sam", 0xFF5C6BC0, ids = setOf("6"), "ש.א.מ", "SAM", "S.A.M"),
        op("eilot", 0xFFD08A3C, ids = setOf("10"), "מועצה אזורית אילות", "Eilot Regional Council", "Eilot"),
        op("united-tours", 0xFF455A64, ids = setOf("40"), "יונייטד טורס", "United Tours"),
        // Jerusalem light rail: CityPass, operated by Kfir since 2023.
        op("jerusalem-lrt", 0xFFE2001A, ids = setOf("21"), "כפיר", "Kfir", "סיטיפס", "CityPass", "City Pass",
            "Jerusalem Light Rail"),
        // Tel Aviv light rail (Red line), operated by Tavlit for NTA.
        op("tavlit", 0xFFE3232C, ids = setOf("22"), "תבל", "Tavlit", "נת\"ע", "NTA", "Tel Aviv Light Rail", "Dankal"),
        op("carmelit", 0xFF8E4EC6, ids = setOf("20"), "כרמלית", "Carmelit"),
        op("cable-express", 0xFF0E9AA7, ids = setOf("33"), "כבל אקספרס", "Cable Express"),
        op("israel-railways", 0xFF1C3F94, ids = setOf("2"), "רכבת ישראל", "Israel Railways", "Israel Railway", "Rakevet Israel"),
        // Shared taxis (sherut): yellow like the cabs themselves.
        op("sherut", 0xFFFFB300, ids = setOf("91", "93", "97", "98"), "מוניות", "Sherut", "Taxi"),
        // East Jerusalem unified bus companies (Jerusalem-… איחוד).
        op("east-jerusalem", 0xFF4A6FA5, ids = setOf("42", "44", "45", "47", "49", "50", "51"), "איחוד",
            "ירושלים הר הזיתים", "East Jerusalem"),
    )

    private fun op(key: String, color: Long, ids: Set<String>, vararg names: String) =
        Operator(key, color.toInt(), ids, names.toList())

    private val byName: List<Pair<List<String>, Operator>> =
        all.flatMap { o -> o.names.map { tokens(it) to o } }
            .filter { it.first.isNotEmpty() }
            // Longest alias first so "דן בדרום" beats "דן" and "אגד תעבורה" beats "אגד".
            .sortedByDescending { it.first.size }

    private val byId: Map<String, Operator> = all.flatMap { o -> o.ids.map { it to o } }.toMap()

    /**
     * Finds the operator for a GTFS agency id and/or agency name. Accepts ids with a feed
     * prefix (e.g. "il-Israel-MOT_15") and names in any case/punctuation.
     */
    fun find(agencyId: String?, agencyName: String?): Operator? {
        agencyName?.let { n ->
            val t = tokens(n)
            if (t.isNotEmpty()) {
                byName.firstOrNull { (alias, _) -> alias == t }?.let { return it.second }
                byName.firstOrNull { (alias, _) -> containsRun(t, alias) }?.let { return it.second }
            }
        }
        val id = agencyId?.trim()?.substringAfterLast('_')?.substringAfterLast(':')?.trimStart('0')
        return id?.takeIf { it.isNotEmpty() }?.let { byId[it] }
    }

    fun findByRef(operatorRef: Long?, agencyName: String?): Operator? =
        find(operatorRef?.takeIf { it > 0 }?.toString(), agencyName)

    /** ARGB brand colour, or null when the operator is unknown. */
    fun colorOf(agencyId: String?, agencyName: String?): Int? = find(agencyId, agencyName)?.color

    private fun tokens(s: String): List<String> =
        s.lowercase()
            .replace(Regex("""["'׳״`]"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() && it !in Noise }
            // "ש.א.מ" / "g.b." collapse to one token so they still match.
            .let { collapseInitials(it) }


    /** Merges runs of single-letter tokens ("s a m" -> "sam"). */
    private fun collapseInitials(t: List<String>): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (w in t) {
            if (w.length == 1) buf.append(w) else {
                if (buf.isNotEmpty()) { out += buf.toString(); buf.clear() }
                out += w
            }
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    private fun containsRun(hay: List<String>, needle: List<String>): Boolean {
        if (needle.size > hay.size) return false
        for (i in 0..hay.size - needle.size) {
            if (hay.subList(i, i + needle.size) == needle) return true
        }
        return false
    }

    /** WCAG relative luminance of an ARGB colour, 0 (black) .. 1 (white). */
    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    /** WCAG contrast ratio of white text on [argb]. */
    fun whiteContrast(argb: Int): Double = 1.05 / (luminance(argb) + 0.05)

    /**
     * True when white text would be hard to read on [argb]. Badge text is bold, so white is
     * kept down to the WCAG large-text ratio of 3:1 (brand greens/blues stay white), and
     * light colours like orange or yellow get dark text.
     */
    fun prefersDarkText(argb: Int): Boolean = whiteContrast(argb) < 3.0
}
