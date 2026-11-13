package com.cbkres.visavole.data

import com.cbkres.visavole.data.StateCodec.SCHEMA_VERSION
import com.cbkres.visavole.data.StateCodec.decode
import com.cbkres.visavole.data.StateCodec.encode
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.ResidenceClass
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripStop
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** The user's persisted data: the primary passport pointer plus every document and trip. */
data class PersistedState(
    val primaryDocId: String?,
    val docs: List<Document>,
    val trips: List<Trip>,
)

/**
 * [StateCodec.decode]'s result: the readable [state], plus [droppedRecords] — how many docs,
 * stops and trips the file held but could not be read (0 for a clean file). Silently skipping
 * damaged records was M6: the user must hear about them before an edit persists the shrunken set.
 */
data class DecodedState(val state: PersistedState, val droppedRecords: Int)

/**
 * Encode/decode [PersistedState] in the JSON layout the app persists to its state file. This is
 * the single source of truth for the on-disk format: the store persists through [encode] and
 * restores through [decode], and backups are validated with the same [decode], so anything the
 * app wrote is a valid backup and any valid backup is loadable by the app.
 */
object StateCodec {
    const val SCHEMA_VERSION = 3

    fun encode(state: PersistedState): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("primaryDocId", state.primaryDocId ?: JSONObject.NULL)
        val arr = JSONArray()
        state.docs.forEach { arr.put(docToJson(it)) }
        root.put("docs", arr)
        val tripArr = JSONArray()
        state.trips.forEach { tripArr.put(tripToJson(it)) }
        root.put("trips", tripArr)
        return root.toString()
    }

    /** Decode persisted state. Tolerant of a partially corrupt file: malformed docs, stops and
     *  trips are skipped rather than failing the whole load, so one bad entry can never wipe the
     *  rest of the user's data — each skipped record is counted in [DecodedState.droppedRecords]
     *  (a trip whose every stop was skipped counts once more, as is a trip left with no stops).
     *  A [SCHEMA_VERSION] written by a NEWER app is rejected outright (the caller quarantines the
     *  file) rather than risking a misread. */
    fun decode(text: String): DecodedState {
        val root = JSONObject(text)
        if (root.optInt("schemaVersion", 0) > SCHEMA_VERSION) {
            throw IllegalArgumentException(
                "Unsupported schemaVersion ${root.getInt("schemaVersion")} (this app supports up to $SCHEMA_VERSION)",
            )
        }
        val primaryDocId = root.strOrNull("primaryDocId")
        val arr = root.optJSONArray("docs") ?: JSONArray()
        // Duplicate ids (e.g. from a hand-edited backup) would crash the lists — items are keyed
        // on id — and make an edit delete both twins, so the first occurrence wins. Dedupes are
        // deliberate merges, not damage, so only unreadable records count as dropped.
        val list = mutableListOf<Document>()
        val seenDocIds = mutableSetOf<String>()
        var dropped = 0
        for (i in 0 until arr.length()) {
            val doc = runCatching { jsonToDoc(arr.getJSONObject(i)) }.getOrNull()
            if (doc == null) {
                dropped++
            } else if (seenDocIds.add(doc.id)) {
                list.add(doc)
            }
        }
        val tripArr = root.optJSONArray("trips") ?: JSONArray()
        val tripList = mutableListOf<Trip>()
        val seenTripIds = mutableSetOf<String>()
        for (i in 0 until tripArr.length()) {
            val o = runCatching { tripArr.getJSONObject(i) }.getOrNull()
            if (o == null) {
                dropped++
                continue
            }
            val stops = mutableListOf<TripStop>()
            val stopArr = o.optJSONArray("stops") ?: JSONArray()
            for (j in 0 until stopArr.length()) {
                val so = runCatching { stopArr.getJSONObject(j) }.getOrNull()
                // A missing/null country is garbage (getString would mint "null"): drop the stop.
                val country = so?.strOrNull("country")
                val stop = country?.let {
                    runCatching {
                        TripStop(
                            so.optString("id").ifBlank { UUID.randomUUID().toString() },
                            it,
                            LocalDate.parse(so.getString("arrival")),
                            so.strOrNull("departure")?.let { d -> LocalDate.parse(d) },
                            so.strOrNull("documentId"),
                        )
                    }.getOrNull()
                }
                if (stop == null) dropped++ else stops.add(stop)
            }
            if (stops.isEmpty()) {
                dropped++
                continue
            }
            val trip = Trip(o.optString("id").ifBlank { UUID.randomUUID().toString() }, stops, o.strOrNull("note"))
            if (seenTripIds.add(trip.id)) tripList.add(trip)
        }
        // Backups and hand-edited state files are trusted input but never passed through the
        // guard, so the invariants the mutation paths maintain are re-established here (the
        // single choke point for both the state file and restores): a supersession pointer must
        // name a real, DIFFERENT document in this set. Dangling or self-referencing pointers are
        // dropped — a self pointer in particular would make the doc supersede itself and vanish.
        val ids = list.mapTo(mutableSetOf()) { it.id }
        val cleanedDocs = list.map { d ->
            val ref = d.supersededBy
            if (ref != null && (ref == d.id || ref !in ids)) d.copy(supersededBy = null) else d
        }
        return DecodedState(PersistedState(primaryDocId, cleanedDocs, tripList), dropped)
    }

    private fun docToJson(d: Document): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("label", d.label)
        put("expiry", d.expiry ?: JSONObject.NULL)
        put("validFrom", d.validFrom ?: JSONObject.NULL)
        put("residenceClass", d.residenceClass?.id ?: JSONObject.NULL)
        put("supersededBy", d.supersededBy ?: JSONObject.NULL)
        when (val k = d.kind) {
            is DocKind.Passport -> {
                put("kind", "passport"); put("iso2", k.iso2)
            }
            is DocKind.Holding -> {
                put("kind", "holding"); put("holdingId", k.holdingId)
                put("entryType", k.entryType ?: JSONObject.NULL)
            }
            is DocKind.Custom -> {
                put("kind", "custom"); put("ckind", k.kind)
                put("bloc", k.blocId ?: JSONObject.NULL)
                put("holding", k.holdingId ?: JSONObject.NULL)
                put("entryType", k.entryType ?: JSONObject.NULL)
                put("countries", JSONArray(k.countries.toList()))
            }
        }
    }

    /** Read a nullable string field: missing, JSON null, empty, or the literal "null" → null. */
    private fun JSONObject.strOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun tripToJson(t: Trip): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("note", t.note ?: JSONObject.NULL)
        val stops = JSONArray()
        t.stops.forEach { s ->
            stops.put(JSONObject().apply {
                put("id", s.id)
                put("country", s.countryIso2)
                put("arrival", s.arrival.toString())
                put("departure", s.departure?.toString() ?: JSONObject.NULL)
                put("documentId", s.documentId ?: JSONObject.NULL)
            })
        }
        put("stops", stops)
    }

    /** Parses one doc object; returns null (→ skipped) when a REQUIRED field is missing, JSON
     *  null, or a number coerced to a garbage string — org.json's getString on a null value
     *  returns the literal "null", which would mint docs with id/iso2 "null". */
    private fun jsonToDoc(o: JSONObject): Document? {
        val id = o.strOrNull("id") ?: return null
        val label = o.strOrNull("label") ?: return null
        val kind = when (o.optString("kind")) {
            "passport" -> DocKind.Passport(o.strOrNull("iso2") ?: return null)
            "holding" -> DocKind.Holding(o.strOrNull("holdingId") ?: return null, o.strOrNull("entryType"))
            else -> {
                val arr = o.getJSONArray("countries")
                val set = LinkedHashSet<String>()
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                DocKind.Custom(
                    set,
                    o.strOrNull("bloc"),
                    o.strOrNull("ckind") ?: "visa",
                    o.strOrNull("holding"),
                    o.strOrNull("entryType"),
                )
            }
        }
        val expiry = o.strOrNull("expiry")
        val validFrom = o.strOrNull("validFrom")
        // P3-27: a non-null date that doesn't parse would otherwise decode as "unconstrained" —
        // an immortal doc, or one valid immediately — because every downstream consumer wraps
        // LocalDate.parse in runCatching. Skip the doc instead of minting one with garbage dates.
        if (!isDateOrNull(expiry) || !isDateOrNull(validFrom)) return null
        val residenceClass = ResidenceClass.fromId(o.strOrNull("residenceClass"))
        val supersededBy = o.strOrNull("supersededBy")
        return Document(id, label, kind, expiry, validFrom, residenceClass, supersededBy)
    }

    /** True when [s] is null (unset) or a parseable ISO date — a non-null, unparseable date is
     *  corrupt input (P3-27). */
    private fun isDateOrNull(s: String?): Boolean =
        s == null || runCatching { LocalDate.parse(s) }.isSuccess
}
