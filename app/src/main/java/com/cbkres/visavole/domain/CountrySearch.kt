package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Country
import java.text.Normalizer
import java.util.Locale

/**
 * The shared country-search matching used by every country search UI (map search bar,
 * onboarding picker, document/stop country picker).
 */
internal fun searchCountries(countries: Map<String, Country>, query: String): List<Country> {
    val q = fold(query)
    if (q.isEmpty()) return emptyList()
    return countries.values
        .mapNotNull { c -> rankMatch(q, c)?.let { c to it } }
        .sortedWith(compareBy({ it.second }, { it.first.name }))
        .map { it.first }
        .take(20)
}

/**
 * The strongest way [query] matches [country], or null for no match: 0 exact ISO code,
 * 1 folded substring, 2 word initials, 3 subsequence, 4 near-miss edit distance.
 */
private fun rankMatch(q: String, country: Country): Int? {
    val name = fold(country.name)
    return when {
        country.iso2.lowercase(Locale.ROOT) == q -> 0
        name.contains(q) -> 1
        wordInitials(name).startsWith(q) -> 2
        isSubsequence(q, name) -> 3
        isNearMatch(q, name) -> 4
        else -> null
    }
}

/**
 * Letter-in-order match ("usa" in United States). Only for queries of 3+ letters: at
 * length 2 it matches near anything (e.g. "uk" would pull in Türkiye).
 */
private fun isSubsequence(q: String, foldedName: String): Boolean {
    if (q.length < 3) return false
    var i = 0
    for (ch in foldedName) {
        if (i < q.length && q[i] == ch) i++
    }
    return i == q.length
}

/**
 * Typo tolerance: query within a small edit distance of the full name or any of its
 * words — this is what bridges exonym to endonym where letters are reordered
 * ("turkey" vs Türkiye: neither substring nor subsequence spans the e/y swap).
 * Threshold scales with query length (1 at 4-5 letters, 2 at 6+); shorter queries are
 * exempt, where distance 2 matches near anything.
 */
private fun isNearMatch(q: String, foldedName: String): Boolean {
    val maxDistance = when {
        q.length < 4 -> return false
        q.length < 6 -> 1
        else -> 2
    }
    return (foldedName.split(" ") + foldedName)
        .filter { it.isNotEmpty() }
        .any { editDistance(q, it) <= maxDistance }
}

/** Optimal string alignment distance: Levenshtein plus transpositions (typos like "turkey" -> "turkye"). */
private fun editDistance(a: String, b: String): Int {
    val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
    }
    return d[a.length][b.length]
}

/** First letters of the (already-folded) name's words, e.g. "United Kingdom" -> "uk". */
private fun wordInitials(foldedName: String): String =
    foldedName.split(" ")
        .filter { it.isNotEmpty() }
        .map { it.first() }
        .joinToString("")

/**
 * Unicode fold used for matching: decompose (NFD), drop combining marks, lowercase.
 * Applied to both name and query, so a typed umlaut (ünited) matches a plain name
 * (United) just as a plain query (cote) matches a diacritic name (Côte).
 */
private fun fold(s: String): String =
    Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
