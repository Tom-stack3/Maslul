package com.maslul.app.data
import com.maslul.app.i18n.S

/**
 * House-number queries ("המגינים 14 פתח תקווה"). OSM often lacks the exact number, and then
 * both geocoders return unrelated "… 14" addresses in other streets. We look for the exact
 * address, and otherwise fall back to the street itself, labelled as such.
 */
object AddressSearch {
    data class Query(val text: String, val number: String?, val rest: String)

    private val numberRe = Regex("""(?<![\p{L}\d])(\d{1,4}[א-ת]?)(?![\p{L}\d])""")

    fun parse(text: String): Query {
        val t = text.trim().replace(Regex("""\s+"""), " ")
        val m = numberRe.find(t)?.takeIf { t.any(Char::isLetter) } ?: return Query(t, null, t)
        val rest = t.removeRange(m.range).replace(",", " ").replace(Regex("""\s+"""), " ").trim()
        return Query(t, m.value, rest)
    }

    /** Spelling-tolerant form for comparisons: no punctuation, "תקווה" == "תקוה". */
    fun norm(s: String): String = s.lowercase()
        .replace(Regex("""["'׳״`]"""), "")
        .replace(Regex("""[-־,.]"""), " ")
        .replace("וו", "ו").replace("יי", "י")
        .replace(Regex("""(^|\s)רחוב\s"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    private fun containsWords(hay: String, needle: String) =
        needle.isNotBlank() && " $hay ".contains(" $needle ")

    /** The query's words besides the street (usually the city) must match the place's city. */
    private fun cityOk(q: Query, street: String, p: Place): Boolean {
        val extra = " ${norm(q.rest)} ".replace(" $street ", " ").trim()
        if (extra.isBlank()) return true
        val city = norm(p.subtitle ?: return false)
        return city.isNotBlank() && (containsWords(city, extra) || containsWords(extra, city) || city.contains(extra))
    }

    fun isExact(q: Query, p: Place): Boolean {
        val num = norm(q.number ?: return false)
        val name = norm(p.name)
        if (p.kind != PlaceKind.ADDRESS || !name.endsWith(" $num")) return false
        val street = name.removeSuffix(" $num").trim()
        return containsWords(norm(q.rest), street) && cityOk(q, street, p)
    }

    fun isStreet(q: Query, p: Place): Boolean {
        if (p.kind == PlaceKind.STOP) return false
        val street = norm(p.name)
        return containsWords(norm(q.rest), street) && cityOk(q, street, p)
    }

    /**
     * Puts exact address hits first; failing that, the matching street (named with the house
     * number, subtitle saying it's approximate). Returns null if neither was found.
     */
    fun resolve(q: Query, candidates: List<Place>): List<Place>? {
        val num = q.number ?: return null
        val exact = candidates.filter { isExact(q, it) }
        if (exact.isNotEmpty()) return (exact + candidates).distinctBy { it.key }
        val street = candidates.firstOrNull { isStreet(q, it) } ?: return null
        val approx = street.copy(
            name = "${street.name} $num",
            subtitle = listOfNotNull(S.numberNotOnMap(num), street.subtitle).joinToString(" · "),
            kind = PlaceKind.ADDRESS,
        )
        return (listOf(approx) + candidates).distinctBy { it.key }
    }
}
