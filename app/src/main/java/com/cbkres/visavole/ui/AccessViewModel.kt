package com.cbkres.visavole.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cbkres.visavole.data.VisaDb
import com.cbkres.visavole.data.VisaRepository
import com.cbkres.visavole.data.WorldGeometry
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.data.WorldMapData
import com.cbkres.visavole.domain.Access
import com.cbkres.visavole.domain.AccessModel
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.ResidenceClass
import com.cbkres.visavole.domain.residenceClassFor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Stable id for the traveller's home (primary) passport document. */
const val HOME_DOC_ID = "home"

sealed interface AppState {
    data object Loading : AppState
    data class Ready(
        val homeCountries: Set<String>,
        val ownVisaCountries: Set<String>,
        val docs: List<Document>,
        val access: Map<String, Access>,
        val world: WorldData,
        val geometry: WorldMapData,
    ) : AppState
}

/**
 * Owns the traveller's passports + documents, loads the bundled world once (on a background
 * dispatcher) and recomputes the per-country access map whenever inputs change. Every passport is
 * a "home" country (blue on the map) — see [AccessModel.homeCountries] — and onboarding gates on
 * there being none. The onboarding passport is kept as a first-class [Document] (id
 * [HOME_DOC_ID]) so it appears in, and can be removed from, the documents list like any other.
 * User state is persisted as a small JSON file.
 */
class AccessViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context = app
    private val repository = VisaRepository(VisaDb(context))
    @Volatile private var world: WorldData? = null
    private val geometry: WorldMapData by lazy { WorldGeometry.load(context) }

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var docs: MutableList<Document> = mutableListOf()
    private var pendingHome: String? = null
    private var reloadGeneration = 0

    private val homeIso: String?
        get() = (docs.firstOrNull { it.id == HOME_DOC_ID }?.kind as? DocKind.Passport)?.iso2

    init {
        readPersisted()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                world = repository.loadWorld(AccessModel.homeCountries(docs))
                geometry
                normalizeResidenceClasses(world!!)
                migrateHomeDoc(world!!)
                world = repository.loadWorld(AccessModel.homeCountries(docs))
            }
            publish()
        }
    }

    /** Fill in the residence class of residence documents saved before the field existed
     *  (inferred from their known holding); passport/visa documents stay null. */
    private fun normalizeResidenceClasses(w: WorldData) {
        val normalized = docs.map { d ->
            if (d.residenceClass == null) d.residenceClassFor(w)?.let { d.copy(residenceClass = it) } ?: d
            else d
        }
        if (normalized != docs) {
            docs = normalized.toMutableList()
            persist()
        }
    }

    /** Promote a legacy "home" string (pre home-as-document) into a first-class document. */
    private fun migrateHomeDoc(w: WorldData) {
        val iso = pendingHome ?: return
        pendingHome = null
        if (docs.none { it.id == HOME_DOC_ID }) {
            docs = (listOf(Document(HOME_DOC_ID, "Passport · ${w.countries[iso]?.name ?: iso}", DocKind.Passport(iso))) + docs).toMutableList()
            persist()
        }
    }

    private fun publish() {
        val w = world ?: return
        val homeCountries = AccessModel.homeCountries(docs)
        val ownVisaCountries = if (homeCountries.isNotEmpty()) AccessModel.ownVisaCountries(docs, w) else emptySet()
        val access = if (homeCountries.isNotEmpty()) AccessModel.compute(docs, w) else emptyMap()
        _state.value = AppState.Ready(homeCountries, ownVisaCountries, docs.toList(), access, w, geometry)
    }

    private fun updateDocs(nextDocs: List<Document>) {
        val oldHomes = AccessModel.homeCountries(docs)
        docs = nextDocs.toMutableList()
        persist()
        if (AccessModel.homeCountries(docs) != oldHomes) reloadWorldAndPublish() else publish()
    }

    private fun reloadWorldAndPublish() {
        val homes = AccessModel.homeCountries(docs)
        val gen = ++reloadGeneration
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val w = repository.loadWorld(homes)
                if (gen != reloadGeneration) return@withContext
                world = w
            }
            if (gen != reloadGeneration) return@launch
            publish()
        }
    }

    fun setHome(iso2: String, expiry: String? = null) {
        val homeDoc = Document(HOME_DOC_ID, "Passport · ${world?.countries?.get(iso2)?.name ?: iso2}", DocKind.Passport(iso2), null, expiry)
        updateDocs(listOf(homeDoc) + docs.filterNot { it.id == HOME_DOC_ID })
    }

    fun addDocument(doc: Document) {
        updateDocs(docs.filterNot { it.id == doc.id } + doc)
    }

    fun updateDocument(doc: Document) = addDocument(doc)

    fun removeDocument(id: String) {
        val next = if (id != HOME_DOC_ID) {
            docs.filterNot { it.id == id }
        } else {
            var nextDocs = docs.filterNot { it.id == id }
            docs.firstOrNull { it.kind is DocKind.Passport }?.let { p ->
                val iso = (p.kind as DocKind.Passport).iso2
                val promoted = p.copy(id = HOME_DOC_ID, label = "Passport · ${world?.countries?.get(iso)?.name ?: iso}")
                nextDocs = nextDocs.map { if (it.id == p.id) promoted else it }
            }
            nextDocs
        }
        updateDocs(next)
    }

    // ---- persistence (JSON in the app's files dir) ----
    private val file get() = File(context.filesDir, "visavole_state.json")

    private fun persist() {
        try {
            val root = JSONObject()
            root.put("home", homeIso ?: JSONObject.NULL)
            val arr = JSONArray()
            docs.forEach { arr.put(docToJson(it)) }
            root.put("docs", arr)
            file.writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    private fun readPersisted() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            pendingHome = if (root.isNull("home")) null else root.optString("home").ifEmpty { null }
            val arr = root.optJSONArray("docs") ?: JSONArray()
            val list = mutableListOf<Document>()
            for (i in 0 until arr.length()) list.add(jsonToDoc(arr.getJSONObject(i)))
            docs = list
        } catch (_: Exception) {
        }
    }

    private fun docToJson(d: Document): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("label", d.label)
        put("expiry", d.expiry ?: JSONObject.NULL)
        put("validFrom", d.validFrom ?: JSONObject.NULL)
        put("residenceClass", d.residenceClass?.id ?: JSONObject.NULL)
        when (val k = d.kind) {
            is DocKind.Passport -> {
                put("kind", "passport"); put("iso2", k.iso2)
            }
            is DocKind.Holding -> {
                put("kind", "holding"); put("holdingId", k.holdingId)
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

    private fun jsonToDoc(o: JSONObject): Document {
        val kind = when (o.optString("kind")) {
            "passport" -> DocKind.Passport(o.getString("iso2"))
            "holding" -> DocKind.Holding(o.getString("holdingId"))
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
        return Document(o.getString("id"), o.getString("label"), kind, null, expiry, validFrom, residenceClass)
    }
}
