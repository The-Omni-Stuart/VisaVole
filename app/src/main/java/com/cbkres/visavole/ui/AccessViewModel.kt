package com.cbkres.visavole.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.cbkres.visavole.data.BackupManager
import com.cbkres.visavole.data.PersistedState
import com.cbkres.visavole.data.VisaDb
import com.cbkres.visavole.data.VisaRepository
import com.cbkres.visavole.data.VisaStateStore
import com.cbkres.visavole.data.WorldGeometry
import com.cbkres.visavole.data.WorldMapData
import com.cbkres.visavole.domain.AccessModel
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.DocStatus
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.DocumentMigration
import com.cbkres.visavole.domain.GuardAction
import com.cbkres.visavole.domain.GuardContext
import com.cbkres.visavole.domain.GuardEngine
import com.cbkres.visavole.domain.GuardFinding
import com.cbkres.visavole.domain.GuardMutation
import com.cbkres.visavole.domain.GuardResult
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripCalculation
import com.cbkres.visavole.domain.TripModel
import com.cbkres.visavole.domain.TripSections
import com.cbkres.visavole.domain.WorldData
import com.cbkres.visavole.domain.passportDocument
import com.cbkres.visavole.domain.residenceClassFor
import com.cbkres.visavole.ui.app.AppState
import com.cbkres.visavole.ui.screens.map.Focus
import com.cbkres.visavole.ui.screens.map.IsoShape
import com.cbkres.visavole.ui.screens.map.buildFocus
import com.cbkres.visavole.ui.screens.map.buildShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Owns the traveller's passports + documents, loads the bundled world once (on a background
 * dispatcher) and recomputes the per-country access map whenever inputs change. Every passport is
 * a "home" country (blue on the map) — see [AccessModel.homeCountries] — and onboarding gates on
 * there being none. Each passport is a first-class [Document] with a stable UUID; one of them is
 * the "primary" ([AppState.Ready.primaryDocId]), used only as a tie-breaker when several held
 * documents grant equal access to a destination. User state is persisted as a small JSON file.
 */
class AccessViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = VisaRepository(VisaDb(application))
    private val backup = BackupManager(application)
    // The persistence subsystem: snapshots of docs/trips are handed to VisaStateStore, which owns
    // the ordered IO write queue (flushed if the scope ends) and the corrupted-file handling.
    // viewModelScope preserves the writer's existing lifecycle.
    private val stateStore = VisaStateStore(file = backup.stateFile, scope = viewModelScope)
    @Volatile private var world: WorldData? = null
    private val geometry: WorldMapData by lazy { WorldGeometry.load(application) }
    // Built once on the IO thread at load time and shipped via AppState.Ready.
    @Volatile private var shapes: List<IsoShape>? = null
    @Volatile private var focus: Map<String, Focus>? = null

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** Transient user feedback, shown as a snackbar by the UI. Emitted whenever a guarded
     *  mutation is rejected by the backstop, so no blocked action is ever silent. */
    private val snackQueue = SnackQueue()
    val snacks: Flow<String> = snackQueue.flow
    private fun snack(message: String, sticky: Boolean = false) {
        // Past capacity the feedback would vanish silently; a log is the honest minimum.
        if (!snackQueue.send(message, sticky)) {
            Log.w(TAG, "Snack channel full; dropped feedback: $message")
        }
    }

    /** Drops the feedback buffered before the main screen first appeared; call once, from it. */
    fun discardStaleSnacks() {
        if (staleSnacksDiscarded) return
        staleSnacksDiscarded = true
        snackQueue.dropBuffered()
    }

    private var staleSnacksDiscarded = false

    /** M6: skipped records must have a voice before an edit persists the shrunken set. */
    private fun snackUnreadableRecords(count: Int) {
        snack("$count record${if (count == 1) "" else "s"} could not be read — your backup may be damaged", sticky = true)
    }

    private var docs: MutableList<Document> = mutableListOf()
    private var trips: MutableList<Trip> = mutableListOf()
    private var primaryDocId: String? = null
    // Newest-ticket-wins gates: a superseded world reload or derivation must not write state late.
    private val reloadGate = PublishGeneration()
    private val publishGate = PublishGeneration()
    // M6: records the pending restore's file held but could not read; voiced when the restore lands.
    private var restoreDroppedRecords = 0
    // persisted state is read off the main thread, once, before the first world load.
    @Volatile private var stateRead = false

    init {
        launchLoad()
    }

    /**
     * Loads the bundled world once and publishes. Kept as a single method so [retryLoad] (the
     * error screen's retry button) reruns exactly the same path as the first launch.
     */
    private fun launchLoad() {
        // One gate for both load paths: a superseded launch must not overwrite a newer world.
        val gen = reloadGate.next()
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    // read the persisted state off the main thread, once, before deriving the
                    // home countries from the loaded docs. retryLoad skips it — by then the in-memory
                    // state (and the async writes it triggered) is already authoritative.
                    if (!stateRead) {
                        stateStore.load()?.let { read ->
                            primaryDocId = read.state.primaryDocId
                            docs = read.state.docs.toMutableList()
                            trips = read.state.trips.toMutableList()
                            if (read.droppedRecords > 0) snackUnreadableRecords(read.droppedRecords)
                        }
                        stateRead = true
                    }
                    val initialHomes = AccessModel.homeCountries(docs)
                    val w = repository.loadWorld(initialHomes)
                    // pre-build the map shapes + per-country focus here (IO thread) rather than
                    // on the main thread at first composition — the one-time ~121k-point Path
                    // construction would otherwise add to first-frame latency on top of the load.
                    Triple(w, buildShapes(geometry), buildFocus(geometry))
                }
                if (!reloadGate.isCurrent(gen)) return@launch
                world = loaded.first
                shapes = loaded.second
                focus = loaded.third
                // ensurePrimaryPassport/normalizeResidenceClasses mutate docs + persist; they run
                // here on the main thread, keeping every state mutation single-threaded.
                ensurePrimaryPassport()
                normalizeResidenceClasses(world!!)
                // neither of the above changes the passport set, so homeCountries is unchanged
                // and the world loaded above is still correct. The old "reload if finalHomes differs"
                // branch (a full second read/parse of the ~7.4 MB DB) was dead code.
                publish()
            } catch (e: Exception) {
                // A corrupted/missing bundled DB (or any load failure) must not crash the process:
                // surface it as a state the UI can show with a retry.
                Log.e(TAG, "Failed to load world data", e)
                if (reloadGate.isCurrent(gen)) {
                    _state.value = AppState.Failed("Could not load the bundled visa data. ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Reruns the initial load (exposed for the error screen's retry button). */
    fun retryLoad() {
        _state.value = AppState.Loading
        launchLoad()
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

    /**
     * Called by the UI's ON_RESUME observer: an app left open across UTC midnight must not keep
     * showing yesterday's numbers. Recomputes only when the date actually rolled over, so a
     * resume storm (every tab switch back) cannot recompute the world.
     */
    fun onResume() {
        if (world == null) return // nothing published yet; the load publishes its own date
        if (shouldRepublish(today, LocalDate.now(ZoneOffset.UTC))) publish()
    }

    // The derivation is the heaviest per-mutation work, so it runs off-main on a snapshot;
    // only the newest scheduled derivation writes state.
    private fun publish() {
        val w = world ?: return
        today = LocalDate.now(ZoneOffset.UTC)
        val inputs = snapshotPublishedInputs(
            world = w,
            geometry = geometry,
            shapes = shapes.orEmpty(),
            focus = focus.orEmpty(),
            docs = docs,
            trips = trips,
            starredDocId = primaryDocId,
            today = today,
        )
        val generation = publishGate.next()
        viewModelScope.launch {
            val ready = withContext(Dispatchers.Default) { deriveReady(inputs) }
            if (!publishGate.isCurrent(generation)) return@launch
            _state.value = ready
        }
    }

    private fun updateDocs(nextDocs: List<Document>) {
        val oldHomes = AccessModel.homeCountries(docs)
        docs = nextDocs.toMutableList()
        persist()
        // Publish in-memory first: a new passport stays visible even if its corridor reload fails.
        publish()
        if (AccessModel.homeCountries(docs) != oldHomes) reloadWorldAndPublish()
    }

    private fun reloadWorldAndPublish() {
        val homes = AccessModel.homeCountries(docs)
        val gen = reloadGate.next()
        viewModelScope.launch {
            val w = withContext(Dispatchers.IO) {
                try {
                    repository.loadWorld(homes)
                } catch (e: Exception) {
                    // Keep the last good world rather than crashing: the user's documents are
                    // still usable, the new home corridor simply cannot be computed yet.
                    Log.e(TAG, "Failed to reload world data; keeping last good state", e)
                    null
                }
            }
            if (!reloadGate.isCurrent(gen)) return@launch
            if (w == null) {
                snack("Could not reload the visa database; showing the last good state.", sticky = true)
                return@launch
            }
            world = w
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
     *
     * [evaluated] is a verdict the dialog just computed for this exact action on the same main
     * thread, so no mutation can have interleaved; dialog-less paths still re-evaluate fully.
     */
    private fun guardBlocked(action: GuardAction, evaluated: GuardResult? = null): Boolean {
        val result = evaluated ?: guardCtx()?.let { GuardEngine.evaluate(action, it) } ?: return false
        if (!result.canProceed) {
            snack(result.blocks.first().message)
        }
        return !result.canProceed
    }

    /** Applies the guard's accompanying mutations (superseding/reviving documents, ending a trip). */
    private fun applyMutations(mutations: List<GuardMutation>) {
        if (mutations.isEmpty()) return
        for (m in mutations) {
            when (m) {
                is GuardMutation.SupersedeDocument ->
                    docs = docs.map { if (it.id == m.oldId) it.copy(supersededBy = m.newId) else it }.toMutableList()
                is GuardMutation.UnsupersedeDocument ->
                    docs = docs.map { if (it.id == m.docId) it.copy(supersededBy = null) else it }.toMutableList()
                is GuardMutation.EndTrip ->
                    trips = trips.map { t -> if (t.id == m.tripId) TripModel.closeTrip(t, m.onDate) else t }.toMutableList()
            }
        }
        persist()
    }

    /** Onboarding's "add your first passport": the same guarded add path as the documents tab.
     *  Returns the blocking findings when the guard rejects the add (empty on success). */
    // takes validFrom as well as expiry so onboarding can record a full (or no) validity
    // period, symmetric with the add-doc dialog.
    fun addOnboardingPassport(iso2: String, validFrom: String? = null, expiry: String? = null): List<GuardFinding> =
        addDocument(passportDocument(iso2, world?.countries?.get(iso2)?.name, expiry, validFrom))

    /** Adds or updates [doc] through the guard backstop. Returns the blocking findings when the
     *  guard rejects the mutation (empty on success), so callers can surface why nothing happened.
     *  The first block is ALSO snack'd here: the dialogs close unconditionally after submit,
     *  so without this the backstop's rejection — the only one that can disagree with the dialog's
     *  own evaluation — would vanish with the filled form and zero feedback. */
    fun addDocument(doc: Document): List<GuardFinding> {
        val original = docs.firstOrNull { it.id == doc.id }
        val action = if (original != null) GuardAction.UpdateDocument(doc, original) else GuardAction.AddDocument(doc)
        val result = guardCtx()?.let { GuardEngine.evaluate(action, it) }
        if (result != null && !result.canProceed) {
            snack(result.blocks.first().message)
            return result.blocks
        }
        if (result != null) applyMutations(result.mutations)
        if (primaryDocId == null && doc.kind is DocKind.Passport) primaryDocId = doc.id
        updateDocs(docs.filterNot { it.id == doc.id } + doc)
        return emptyList()
    }

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

    /** Lets a dialog report a side effect it committed itself (e.g. a mid-dialog trip split). */
    fun snackMessage(message: String) = snack(message)

    /** Adds [trip]. [evaluated] is the verdict the trip dialog already computed for this add (see
     *  [guardBlocked]); [mutations] are the ones the user confirmed alongside it. */
    fun addTrip(trip: Trip, mutations: List<GuardMutation> = emptyList(), evaluated: GuardResult? = null) {
        if (guardBlocked(GuardAction.AddTrip(trip), evaluated)) return
        applyMutations(mutations)
        trips = (trips + trip).toMutableList()
        persist()
        publish()
    }

    /** Updates [trip], honouring the [evaluated] verdict the trip dialog computed for it. */
    fun updateTrip(trip: Trip, mutations: List<GuardMutation> = emptyList(), evaluated: GuardResult? = null) {
        if (guardBlocked(GuardAction.UpdateTrip(trip), evaluated)) return
        applyMutations(mutations)
        trips = trips.map { if (it.id == trip.id) trip else it }.toMutableList()
        persist()
        publish()
    }

    // Intentionally outside the guard: deletion must never be blocked — the user owns their trips,
    // and the delete confirm dialog (ongoing-aware) is the warning surface.
    fun removeTrip(id: String) {
        trips = trips.filterNot { it.id == id }.toMutableList()
        persist()
        publish()
    }

    /** UX close of an ongoing trip — intentionally outside the guard (see spec §Phase 3). */
    fun endTrip(id: String, departure: LocalDate) {
        // a stale id used to no-op silently — surface it instead (the path is modal-blocked
        // today, but it was the last silent return left in the VM).
        if (trips.none { it.id == id }) {
            snack("That trip no longer exists.")
            return
        }
        trips = trips.map { if (it.id == id) TripModel.closeTrip(it, departure) else it }.toMutableList()
        persist()
        publish()
    }

    // ---- backup (triggered from the Settings screen) ----

    /** Export the current data to a user-chosen location ([uri] from the SAF create-document picker). */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            // Snapshot on the main thread where the state lives; encoding runs inside exportTo, on IO.
            val snapshot = snapshot()
            when (val outcome = withContext(Dispatchers.IO) { backup.exportTo(uri, snapshot) }) {
                is BackupManager.Outcome.Exported -> snack("Backup saved.")
                is BackupManager.Outcome.Imported -> {}
                is BackupManager.Outcome.Failed -> snack(outcome.message)
            }
        }
    }

    /** Read and validate the backup at [uri]; [onReady] receives the state to confirm restoring. */
    fun prepareRestore(uri: Uri, onReady: (PersistedState) -> Unit) {
        viewModelScope.launch {
            when (val outcome = withContext(Dispatchers.IO) { backup.importFrom(uri) }) {
                is BackupManager.Outcome.Imported -> {
                    restoreDroppedRecords = outcome.droppedRecords
                    onReady(outcome.state)
                }
                is BackupManager.Outcome.Exported -> {}
                is BackupManager.Outcome.Failed -> snack(outcome.message)
            }
        }
    }

    /** Replace the live state with [state] (after the user confirms), then re-persist and republish. */
    fun applyRestore(state: PersistedState) {
        // a restore bypasses the guard's supersession-on-add, so a hand-edited or foreign
        // backup could carry a state the UI can never create (two valid documents for one country).
        // Validate before swapping it in; on violation keep the current state so a bad backup can't
        // clobber the user's data.
        val cur = guardCtx()
        val violations = if (cur != null) {
            GuardEngine.restoreViolations(
                GuardContext.of(state.docs, state.trips, cur.world, state.primaryDocId, cur.today),
            )
        } else {
            emptyList()
        }
        if (violations.isNotEmpty()) {
            snack("Backup not restored — it holds ${violations.first()}.")
            restoreDroppedRecords = 0
            return
        }
        docs = state.docs.toMutableList()
        trips = state.trips.toMutableList()
        primaryDocId = state.primaryDocId
        ensurePrimaryPassport()
        persist()
        reloadWorldAndPublish()
        snack("Backup restored.")
        // decode already counted any unreadable records during importFrom (M6): the user must
        // know the restored set is smaller than the file appeared to hold before editing it.
        if (restoreDroppedRecords > 0) snackUnreadableRecords(restoreDroppedRecords)
        restoreDroppedRecords = 0
    }

    // ---- persistence (JSON snapshot handed to VisaStateStore, encoded by StateCodec) ----

    /**
     * Snapshots the live state. The [.toList]s are load-bearing: they freeze the mutable
     * docs/trips so the async writer (and export) serializes exactly this moment's content.
     */
    private fun snapshot() = PersistedState(
        primaryDocId = primaryDocId,
        docs = docs.toList(),
        trips = trips.toList(),
    )

    private fun persist() {
        stateStore.save(snapshot())
    }

    companion object {
        private const val TAG = "AccessViewModel"
    }
}

/** Everything [deriveReady] needs, captured on the main thread at mutation time. */
internal data class PublishedInputs(
    val world: WorldData,
    val geometry: WorldMapData,
    val shapes: List<IsoShape>,
    val focus: Map<String, Focus>,
    val docs: List<Document>,
    val trips: List<Trip>,
    /** The raw starred document id (may point at an expired document). */
    val starredDocId: String?,
    val today: LocalDate,
)

/**
 * The pure derivation behind [AccessViewModel]'s published state — the whole access map plus every
 * trip's and document's allowance snapshots. Runs off the main thread; touches nothing but [input].
 */
internal fun deriveReady(input: PublishedInputs): AppState.Ready {
    val (world, geometry, shapes, focus, docs, trips, starredDocId, today) = input
    val homeCountries = AccessModel.homeCountries(docs)
    // The unified document validity, computed once per publish and threaded through the
    // derivations below — each of which used to recompute it per document (~5× per document
    // per publish, plus 2× per zone country inside the allowance caps).
    val statuses = DocStatus.all(docs, trips, world, today)
    val ownVisaCountries =
        if (homeCountries.isNotEmpty()) AccessModel.ownVisaCountries(docs, world, today, trips = trips, statuses = statuses) else emptySet()
    val effectiveDocs = if (homeCountries.isNotEmpty()) TripModel.effectiveDocs(docs, trips, world, today, statuses = statuses) else docs
    val access =
        if (homeCountries.isNotEmpty()) AccessModel.compute(effectiveDocs, world, today, trips = trips, statuses = statuses) else emptyMap()
    // thread this publish's access map + per-doc candidate maps into the
    // calculation so zone usage and gate probes reuse them instead of recomputing.
    val calc = if (homeCountries.isNotEmpty()) TripModel.calculate(
        trips, docs, world, today, statuses = statuses,
        access = access,
        candidateMaps = AccessModel.candidateMaps(docs, world, statuses),
    )
    else TripCalculation(TripSections(emptyList(), emptyList(), emptyList()), emptyList(), emptyMap())
    // Removal verdicts, derived here so the documents screen never re-runs the guard per
    // document on the main thread (the last per-publish fan-out of that kind).
    val guardCtx = GuardContext.of(docs, trips, world, starredDocId, today)
    val removeBlockings = docs.associateBy({ it.id }, { d ->
        GuardEngine.evaluate(GuardAction.RemoveDocument(d.id), guardCtx)
    })
    return AppState.Ready(
        homeCountries = homeCountries,
        removeBlockings = removeBlockings,
        ownVisaCountries = ownVisaCountries,
        docs = docs,
        access = access,
        statuses = statuses,
        world = world,
        geometry = geometry,
        shapes = shapes,
        focus = focus,
        trips = trips,
        tripSections = calc.sections,
        allowances = calc.allowances,
        primaryDocId = DocumentMigration.effectivePrimaryId(docs, starredDocId, today),
        starredDocId = starredDocId,
        today = today,
    )
}

/**
 * Freezes the live docs/trips for one publish. The derivation runs later on another thread, so it
 * must see this moment's lists, not whatever the next mutation has made of the mutable ones.
 */
internal fun snapshotPublishedInputs(
    world: WorldData,
    geometry: WorldMapData,
    shapes: List<IsoShape>,
    focus: Map<String, Focus>,
    docs: List<Document>,
    trips: List<Trip>,
    starredDocId: String?,
    today: LocalDate,
) = PublishedInputs(
    world = world,
    geometry = geometry,
    shapes = shapes,
    focus = focus,
    docs = docs.toList(),
    trips = trips.toList(),
    starredDocId = starredDocId,
    today = today,
)

/** The resume-storm guard: republish the derived state only when the UTC date actually changed. */
internal fun shouldRepublish(published: LocalDate, observed: LocalDate): Boolean = published != observed

// Monotonic tickets for work that may be superseded: only the newest may write state, so a
// late-finishing older run cannot overwrite it.
internal class PublishGeneration {
    // Volatile so a superseded run finishing on a worker sees the newer ticket.
    @Volatile private var latest = 0

    /** Takes the next ticket, superseding every earlier one. Called on the main thread only. */
    fun next(): Int = ++latest

    fun isCurrent(generation: Int): Boolean = generation == latest
}
