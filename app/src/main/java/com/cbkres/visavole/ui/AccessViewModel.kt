package com.cbkres.visavole.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cbkres.visavole.data.BackupManager
import com.cbkres.visavole.data.PersistedState
import com.cbkres.visavole.data.StateCodec
import com.cbkres.visavole.data.VisaDb
import com.cbkres.visavole.data.VisaRepository
import com.cbkres.visavole.data.WorldGeometry
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.data.WorldMapData
import com.cbkres.visavole.domain.Access
import com.cbkres.visavole.domain.AccessModel
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.DocumentMigration
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.GuardAction
import com.cbkres.visavole.domain.GuardContext
import com.cbkres.visavole.domain.GuardEngine
import com.cbkres.visavole.domain.GuardFinding
import com.cbkres.visavole.domain.GuardMutation
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripCalculation
import com.cbkres.visavole.domain.TripModel
import com.cbkres.visavole.domain.TripSections
import com.cbkres.visavole.domain.passportDocument
import com.cbkres.visavole.domain.residenceClassFor
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AppState {
    data object Loading : AppState
    data class Ready(
        val homeCountries: Set<String>,
        val ownVisaCountries: Set<String>,
        val docs: List<Document>,
        val access: Map<String, Access>,
        val world: WorldData,
        val geometry: WorldMapData,
        val trips: List<Trip> = emptyList(),
        val tripSections: TripSections = TripSections(emptyList(), emptyList(), emptyList()),
        val allowances: List<AllowanceSnapshot> = emptyList(),
        /** The effective primary passport id (null when no valid passport is held). */
        val primaryDocId: String? = null,
        /** The raw starred document id (may point at an expired document). */
        val starredDocId: String? = null,
        val today: LocalDate,
    ) : AppState
}

/**
 * Owns the traveller's passports + documents, loads the bundled world once (on a background
 * dispatcher) and recomputes the per-country access map whenever inputs change. Every passport is
 * a "home" country (blue on the map) — see [AccessModel.homeCountries] — and onboarding gates on
 * there being none. Each passport is a first-class [Document] with a stable UUID; one of them is
 * the "primary" ([AppState.Ready.primaryDocId]), used only as a tie-breaker when several held
 * documents grant equal access to a destination. User state is persisted as a small JSON file.
 */
class AccessViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context = app
    private val repository = VisaRepository(VisaDb(context))
    private val backup = BackupManager(context)
    @Volatile private var world: WorldData? = null
    private val geometry: WorldMapData by lazy { WorldGeometry.load(context) }

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** Transient user feedback, shown as a snackbar by the UI. Emitted whenever a guarded
     *  mutation is rejected by the backstop, so no blocked action is ever silent. */
    private val _snacks = Channel<String>(Channel.BUFFERED)
    val snacks: Flow<String> = _snacks.receiveAsFlow()
    private fun snack(message: String) {
        _snacks.trySend(message)
    }

    private var docs: MutableList<Document> = mutableListOf()
    private var trips: MutableList<Trip> = mutableListOf()
    private var primaryDocId: String? = null
    private var reloadGeneration = 0

    init {
        readPersisted()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                world = repository.loadWorld(AccessModel.homeCountries(docs))
                geometry
                ensurePrimaryPassport()
                normalizeResidenceClasses(world!!)
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

    /** Keeps the primary pointer on a held passport; a missing or dangling primary falls back to the first one. */
    private fun ensurePrimaryPassport() {
        val current = docs.firstOrNull { it.id == primaryDocId && it.kind is DocKind.Passport }
        val next = current?.id ?: docs.firstOrNull { it.kind is DocKind.Passport }?.id
        if (next != primaryDocId) {
            primaryDocId = next
            persist()
        }
    }

    /** One "today" per publish cycle: screens, derived state and the guard backstop all read this. */
    private var today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    private fun publish() {
        val w = world ?: return
        today = LocalDate.now(ZoneOffset.UTC)
        val homeCountries = AccessModel.homeCountries(docs)
        val ownVisaCountries =
            if (homeCountries.isNotEmpty()) AccessModel.ownVisaCountries(docs, w, trips = trips.toList()) else emptySet()
        val effectiveDocs = if (homeCountries.isNotEmpty()) TripModel.effectiveDocs(docs, trips, w) else docs
        val access =
            if (homeCountries.isNotEmpty()) AccessModel.compute(effectiveDocs, w, today, trips = trips.toList()) else emptyMap()
        val calc = if (homeCountries.isNotEmpty()) TripModel.calculate(trips, docs, w, today)
        else TripCalculation(TripSections(emptyList(), emptyList(), emptyList()), emptyList(), emptyMap())
        _state.value = AppState.Ready(
            homeCountries = homeCountries,
            ownVisaCountries = ownVisaCountries,
            docs = docs.toList(),
            access = access,
            world = w,
            geometry = geometry,
            trips = trips.toList(),
            tripSections = calc.sections,
            allowances = calc.allowances,
            primaryDocId = effectivePrimaryId(today),
            starredDocId = primaryDocId,
            today = today,
        )
    }

    /**
     * The effective primary passport: the starred one if it's still a valid (non-expired) passport,
     * otherwise the first valid passport. Feeds the guard context (via [GuardContext.of]) and acts
     * as a tie-breaker for [AccessModel.bestDocumentId].
     */
    private fun effectivePrimaryId(today: LocalDate): String? =
        DocumentMigration.effectivePrimaryId(docs, primaryDocId, today)

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

    /** The guard context for the current state (null while the world is still loading). The
     *  backstop re-evaluates with the same `today` the screens last published, so a tap straddling
     *  midnight cannot disagree with what the user just saw. */
    private fun guardCtx(): GuardContext? {
        val w = world ?: return null
        return GuardContext.of(docs.toList(), trips.toList(), w, primaryDocId, today)
    }

    /**
     * Backstop for a guarded mutation: re-run the same [GuardEngine] rules the screens ran and
     * drop the action if they now block (e.g. state changed between the tap and the commit).
     * Surfaces the blocking reason as a snackbar. Returns true when the action must not proceed.
     */
    private fun guardBlocked(action: GuardAction): Boolean {
        val result = guardCtx()?.let { GuardEngine.evaluate(action, it) } ?: return false
        if (!result.canProceed) {
            snack(result.blocks.first().message)
        }
        return !result.canProceed
    }

    /** Applies the guard's accompanying mutations (superseding a replaced document, ending a trip). */
    private fun applyMutations(mutations: List<GuardMutation>) {
        if (mutations.isEmpty()) return
        for (m in mutations) {
            when (m) {
                is GuardMutation.SupersedeDocument ->
                    docs = docs.map { if (it.id == m.oldId) it.copy(supersededBy = m.newId) else it }.toMutableList()
                is GuardMutation.EndTrip ->
                    trips = trips.map { t -> if (t.id == m.tripId) TripModel.closeTrip(t, m.onDate) else t }.toMutableList()
            }
        }
        persist()
    }

    /** Onboarding's "add your first passport": the same guarded add path as the documents tab.
     *  Returns the blocking findings when the guard rejects the add (empty on success). */
    fun addOnboardingPassport(iso2: String, expiry: String? = null): List<GuardFinding> =
        addDocument(passportDocument(iso2, world?.countries?.get(iso2)?.name, expiry))

    /** Adds or updates [doc] through the guard backstop. Returns the blocking findings when the
     *  guard rejects the mutation (empty on success), so callers can surface why nothing happened. */
    fun addDocument(doc: Document): List<GuardFinding> {
        val original = docs.firstOrNull { it.id == doc.id }
        val action = if (original != null) GuardAction.UpdateDocument(doc, original) else GuardAction.AddDocument(doc)
        val result = guardCtx()?.let { GuardEngine.evaluate(action, it) }
        if (result != null && !result.canProceed) return result.blocks
        if (result != null) applyMutations(result.mutations)
        if (primaryDocId == null && doc.kind is DocKind.Passport) primaryDocId = doc.id
        updateDocs(docs.filterNot { it.id == doc.id } + doc)
        return emptyList()
    }

    fun updateDocument(doc: Document) = addDocument(doc)

    fun removeDocument(id: String) {
        if (guardBlocked(GuardAction.RemoveDocument(id))) return
        var next = docs.filterNot { it.id == id }
        // Deleting a replacement revives the documents it superseded: clear the dangling pointers.
        next = next.map { if (it.supersededBy == id) it.copy(supersededBy = null) else it }
        if (primaryDocId == id) primaryDocId = next.firstOrNull { it.kind is DocKind.Passport }?.id
        updateDocs(next)
    }

    /** Star/un-star [id] as the primary passport (a pure tie-breaker). Tapping the current one clears it. */
    fun setPrimary(id: String?) {
        val target = if (id != null && id != primaryDocId) id else null
        if (target != primaryDocId) {
            if (guardBlocked(GuardAction.SetPrimary(target))) return
            primaryDocId = target
            persist()
            publish()
        }
    }

    fun addTrip(trip: Trip, mutations: List<GuardMutation> = emptyList()) {
        if (guardBlocked(GuardAction.AddTrip(trip))) return
        applyMutations(mutations)
        trips = (trips + trip).toMutableList()
        persist()
        publish()
    }

    fun updateTrip(trip: Trip, mutations: List<GuardMutation> = emptyList()) {
        if (guardBlocked(GuardAction.UpdateTrip(trip))) return
        applyMutations(mutations)
        trips = trips.map { if (it.id == trip.id) trip else it }.toMutableList()
        persist()
        publish()
    }

    fun removeTrip(id: String) {
        trips = trips.filterNot { it.id == id }.toMutableList()
        persist()
        publish()
    }

    /** UX close of an ongoing trip — intentionally outside the guard (see spec §Phase 3). */
    fun endTrip(id: String, departure: LocalDate) {
        val trip = trips.firstOrNull { it.id == id } ?: return
        trips = trips.map { if (it.id == id) TripModel.closeTrip(it, departure) else it }.toMutableList()
        persist()
        publish()
    }

    // ---- backup (triggered from the Settings screen) ----

    /** Export the current data to a user-chosen location ([uri] from the SAF create-document picker). */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            when (val outcome = withContext(Dispatchers.IO) { backup.exportTo(uri) }) {
                is BackupManager.Outcome.Exported -> snack("Backup saved.")
                is BackupManager.Outcome.Imported -> snack("Backup ready to restore.")
                is BackupManager.Outcome.Failed -> snack(outcome.message)
            }
        }
    }

    /** Read and validate the backup at [uri]; [onReady] receives the state to confirm restoring. */
    fun prepareRestore(uri: Uri, onReady: (PersistedState) -> Unit) {
        viewModelScope.launch {
            when (val outcome = withContext(Dispatchers.IO) { backup.importFrom(uri) }) {
                is BackupManager.Outcome.Imported -> onReady(outcome.state)
                is BackupManager.Outcome.Exported -> {}
                is BackupManager.Outcome.Failed -> snack(outcome.message)
            }
        }
    }

    /** Replace the live state with [state] (after the user confirms), then re-persist and republish. */
    fun applyRestore(state: PersistedState) {
        docs = state.docs.toMutableList()
        trips = state.trips.toMutableList()
        primaryDocId = state.primaryDocId
        ensurePrimaryPassport()
        persist()
        reloadWorldAndPublish()
        snack("Backup restored.")
    }

    // ---- persistence (JSON in the app's files dir, formatted by StateCodec) ----
    private val file get() = backup.stateFile

    private fun persist() {
        try {
            file.writeText(StateCodec.encode(PersistedState(primaryDocId, docs.toList(), trips.toList())))
        } catch (_: Exception) {
        }
    }

    private fun readPersisted() {
        try {
            if (!file.exists()) return
            val state = StateCodec.decode(file.readText())
            primaryDocId = state.primaryDocId
            docs = state.docs.toMutableList()
            trips = state.trips.toMutableList()
        } catch (_: Exception) {
        }
    }
}
