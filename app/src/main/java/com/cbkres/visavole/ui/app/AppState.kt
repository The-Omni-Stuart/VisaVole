package com.cbkres.visavole.ui.app

import com.cbkres.visavole.data.WorldMapData
import com.cbkres.visavole.domain.Access
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.DocStatus
import com.cbkres.visavole.domain.GuardResult
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripSections
import com.cbkres.visavole.domain.WorldData
import com.cbkres.visavole.ui.screens.map.Focus
import com.cbkres.visavole.ui.screens.map.IsoShape
import java.time.LocalDate

sealed interface AppState {
    data object Loading : AppState
    /** The bundled world data could not be loaded; [message] is shown with a retry action. */
    data class Failed(val message: String) : AppState
    data class Ready(
        val homeCountries: Set<String>,
        val ownVisaCountries: Set<String>,
        val docs: List<Document>,
        val access: Map<String, Access>,
        /** This publish's unified document validity ([DocStatus.all]) — screens read it instead
         *  of deriving their own. */
        val statuses: Map<String, DocStatus>,
        val world: WorldData,
        val geometry: WorldMapData,
        /** Pre-built on the IO thread at load time so the one-time ~121k-point `Path`
         *  construction doesn't run on the main thread at first composition. */
        val shapes: List<IsoShape> = emptyList(),
        val focus: Map<String, Focus> = emptyMap(),
        val trips: List<Trip> = emptyList(),
        val tripSections: TripSections = TripSections(emptyList(), emptyList(), emptyList()),
        val allowances: List<AllowanceSnapshot> = emptyList(),
        /** The effective primary passport id (null when no valid passport is held). */
        val primaryDocId: String? = null,
        /** This publish's guard verdict for removing each document id — screens read it instead
         *  of re-evaluating the guard per document on the main thread. */
        val removeBlockings: Map<String, GuardResult> = emptyMap(),
        /** The raw starred document id (may point at an expired document). */
        val starredDocId: String? = null,
        val today: LocalDate,
    ) : AppState
}