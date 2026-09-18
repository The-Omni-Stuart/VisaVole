package com.cbkres.visavole.data

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
 * Encode/decode [PersistedState] in the JSON layout the app persists to its state file. This is
 * the single source of truth for the on-disk format: the ViewModel persists through [encode] and
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

    /** Decode persisted state. Tolerant of a partially corrupt file: malformed stops and trips
     *  are skipped rather than failing the whole load. */
    fun decode(text: String): PersistedState {
        val root = JSONObject(text)
        val primaryDocId = root.strOrNull("primaryDocId")
        val arr = root.optJSONArray("docs") ?: JSONArray()
        val list = mutableListOf<Document>()
        for (i in 0 until arr.length()) list.add(jsonToDoc(arr.getJSONObject(i)))
        val tripArr = root.optJSONArray("trips") ?: JSONArray()
        val tripList = mutableListOf<Trip>()
        for (i in 0 until tripArr.length()) {
            runCatching { tripArr.getJSONObject(i) }.getOrNull()?.let { o ->
                val stops = mutableListOf<TripStop>()
                val stopArr = o.optJSONArray("stops") ?: JSONArray()
                for (j in 0 until stopArr.length()) {
                    runCatching {
                        val so = stopArr.getJSONObject(j)
                        TripStop(
                            so.optString("id").ifBlank { UUID.randomUUID().toString() },
                            so.getString("country"),
                            LocalDate.parse(so.getString("arrival")),
                            so.strOrNull("departure")?.let { LocalDate.parse(it) },
                            so.strOrNull("documentId"),
                        )
                    }.getOrNull()?.let { stops.add(it) }
                }
                if (stops.isNotEmpty()) {
                    tripList.add(Trip(o.optString("id").ifBlank { UUID.randomUUID().toString() }, stops, o.strOrNull("note")))
                }
            }
        }
        return PersistedState(primaryDocId, list, tripList)
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

    private fun jsonToDoc(o: JSONObject): Document {
        val kind = when (o.optString("kind")) {
            "passport" -> DocKind.Passport(o.getString("iso2"))
            "holding" -> DocKind.Holding(o.getString("holdingId"), o.strOrNull("entryType"))
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
        val residenceClass = ResidenceClass.fromId(o.strOrNull("residenceClass"))
        val supersededBy = o.strOrNull("supersededBy")
        return Document(o.getString("id"), o.getString("label"), kind, null, expiry, validFrom, residenceClass, supersededBy)
    }
}
