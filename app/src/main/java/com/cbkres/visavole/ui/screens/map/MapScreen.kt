package com.cbkres.visavole.ui.screens.map

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.ui.LocalReducedMotion
import com.cbkres.visavole.ui.Motion
import com.cbkres.visavole.domain.AccessLevel
import com.cbkres.visavole.domain.AccessModel
import com.cbkres.visavole.domain.DocAccess
import com.cbkres.visavole.domain.searchCountries
import com.cbkres.visavole.ui.CountryRow
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.app.AppState
import com.cbkres.visavole.ui.hazeAlphas
import com.cbkres.visavole.ui.screens.EmptyState
import com.cbkres.visavole.ui.tapSink
import com.cbkres.visavole.ui.theme.detailCardShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun MapScreen(
    s: AppState.Ready,
    selected: String?,
    onSelect: (String?) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    shapes: List<IsoShape>,
    focus: Map<String, Focus>,
    zoomState: MutableFloatState,
    centerState: MutableState<Offset>,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    // the search field must blur when the map is tapped or Back is pressed, otherwise it
    // keeps its focus/selection forever. This Compose build only exposes clearFocus on the
    // FocusManager (there is no public Modifier.clearFocus / FocusRequester), so grab the manager
    // from LocalFocusManager and clear directly. searchFocused (via onFocusChanged) gates the Back
    // handler + the map-tap blur so we only clear while the field actually holds focus.
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = searchFocused) {
        keyboardController?.hide()
        focusManager.clearFocus()
    }
    // The dropdown fades out over 120ms after the query is cleared; matches must keep the last
    // non-blank query during that exit, otherwise the content flips to "No matching countries"
    // mid-fade right after the user picks a country.
    var shownQuery by remember { mutableStateOf(query) }
    if (query.isNotBlank() && query != shownQuery) shownQuery = query
    val matches = remember(shownQuery, s.world) { searchCountries(s.world.countries, shownQuery) }
    // Stable identity: a fresh lambda on every recomposition would make WorldMapCanvas re-run
    // and repaint every country for the whole duration of the card slide.
    val pick: (String?) -> Unit = remember {
        { iso: String? ->
            keyboardController?.hide()
            if (searchFocused) focusManager.clearFocus()
            onQuery("")
            onSelect(iso)
        }
    }
    // Re-picking the already-selected country from search writes an equal value, so the canvas
    // centring effect's key would not change; this nonce bumps on every search pick so "centre
    // on it again" works, and map taps (which never bump) cannot double-centre.
    var recenterNonce by remember { mutableIntStateOf(0) }
    fun pickFromSearch(iso: String) {
        recenterNonce++
        pick(iso)
    }
    val density = LocalDensity.current
    var searchBarH by remember { mutableStateOf(0.dp) }
    var cardH by remember { mutableStateOf(0.dp) }
    // cardH creeps for ~500 ms after a card appears (rows fill in). The centre-on-country tween
    // now tracks that live height, so it no longer cares — this debounced value is only for the
    // switch envelope, which holds the previous card's height while the new card's rows load.
    var stableCardH by remember { mutableStateOf(0.dp) }
    LaunchedEffect(cardH) {
        delay(150)
        stableCardH = cardH
    }
    var legendH by remember { mutableStateOf(0.dp) }
    val selName = selected?.let { iso -> s.world.countries[iso]?.name ?: s.geometry.countries[iso]?.name ?: iso }
    // selName falls back to the ISO code, so it is null exactly when nothing is selected.
    val cardVisible = selected != null
    // Was a card already up before this selection changed? Computed once on the recomposition
    // that carries the new selection, before any re-layout (self-guarded like shownQuery):
    // a switch morphs the card, an open-from-closed keeps the alpha04 snap.
    var seenSelected by remember { mutableStateOf(selected) }
    var openBefore by remember { mutableStateOf(false) }
    if (seenSelected != selected) {
        openBefore = seenSelected != null
        seenSelected = selected
    }
    // The bottom padding the map's floating zoom controls keep: live card height plus 16dp — the
    // one edge margin every floating action keeps, card or legend below it alike.
    val controlsTargetBottomPadding =
        if (cardVisible) cardH + 16.dp
        else legendH + 16.dp
    // Animated (same spring as the card slide). The map canvas is never shrunk for the card —
    // the card slides over the full-bleed map — so no dark band can appear between the map
    // edge and the card while the fixed 420dp drop exceeds the card's actual height.
    val cardAnimSpec = spring<Dp>(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)
    val controlsBottomPadding by animateDpAsState(
        targetValue = controlsTargetBottomPadding,
        animationSpec = cardAnimSpec,
        label = "controlsBottomPadding",
    )
    Box(modifier.fillMaxSize()) {
        WorldMapCanvas(
            geometry = s.geometry,
            access = s.access,
            shapes = shapes,
            focus = focus,
            zoomState = zoomState,
            centerState = centerState,
            selected = selected,
            recenterNonce = recenterNonce,
            onCountryTap = pick,
            homeCountries = s.homeCountries,
            ownVisaCountries = s.ownVisaCountries,
            residenceCountries = s.residenceCountries,
            controlsBottomPadding = controlsBottomPadding,
            centeringInsetNow = { if (cardVisible) cardH + 16.dp else legendH + 16.dp },
            topInset = searchBarH,
            modifier = Modifier.fillMaxSize(),
        )
        CountrySearchBar(
            query = query,
            onQuery = onQuery,
            // the IME "Search" action picks the first match (previously it only hid the
            // keyboard, leaving the dropdown open with nothing selected).
            onSearch = { matches.firstOrNull()?.let { pickFromSearch(it.iso2) } ?: keyboardController?.hide() },
            onFocusChanged = { searchFocused = it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { searchBarH = Dp(it.height / density.density) },
        )
        // The slide is a graphicsLayer offset, not a layout slide (slideInVertically): while the
        // enter animation runs the card must stay hit-testable at its final position, otherwise
        // an early swipe on the breakdown falls through to the map canvas and pans the map
        // instead of scrolling the list.
        val cardDrop by animateDpAsState(
            targetValue = if (cardVisible) 0.dp else 420.dp,
            animationSpec = cardAnimSpec,
            label = "cardDrop",
        )
        // align must be on the AnimatedVisibility node itself (the direct Box child) — on the
        // inner card it is ignored by the AnimatedVisibility layout and the card lands at TopStart.
        AnimatedVisibility(
            visible = cardVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .onSizeChanged { cardH = Dp(it.height / density.density) },
        ) {
            if (selected != null) {
                val selectedAccess = s.access[selected]
                // Stay windows resolve against the passports that are not expired.
                val validHomes = remember(s.docs, s.statuses) { AccessModel.validHomeCountries(s.docs, s.world, s.today, statuses = s.statuses) }
                // The per-selection breakdown + stay rule are the heavy per-zone access-model
                // lookups. Running them synchronously in composition froze the map (and the
                // centre-on-country tween) for ~1s on tap. They now compute off the main
                // thread — the card slides in immediately with its cheap name/level and the
                // breakdown rows + exact stay label fill in a moment later.
                var breakdown by remember(selected) { mutableStateOf<List<DocAccess>?>(null) }
                var daysLabel by remember(selected) { mutableStateOf<String?>(null) }
                // No rule for any passport: the card lands at header height, far below any
                // held envelope — see the switch-only envelope below.
                val barrenIncoming = selectedAccess == null ||
                    selectedAccess.level == AccessLevel.UNKNOWN

                LaunchedEffect(selected, s.homeCountries, s.access, s.world, s.docs, s.trips, validHomes) {
                    val (b, dl) = withContext(Dispatchers.Default) {
                        val stayRule = when {
                            selected in s.homeCountries -> null
                            s.access[selected]?.level == AccessLevel.RESIDENCE -> null // you live here — no short-stay limit
                            else -> s.world.stayRuleFor(selected, validHomes, s.today)
                        }
                        AccessModel.breakdownFor(selected, s.docs, s.world, s.today, trips = s.trips) to
                                AccessModel.stayLabelFor(selectedAccess, stayRule)
                    }
                    breakdown = b
                    daysLabel = dl
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()

                        // Move the whole thing when the card opens/closes.
                        .graphicsLayer {
                            translationY = cardDrop.toPx()
                        }

                        // Paint the card's rounded silhouette behind the content so a height
                        // change never exposes bare map.
                        .clip(detailCardShape)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        )

                        // Switch-only envelope: when a card was already up, a country whose final
                        // height is comparable holds the settled height while its breakdown loads,
                        // then morphs into place. A barren country (no rule for any passport —
                        // Antarctica) always lands far below any held height, so holding it reads
                        // as a blank stall then a late shrink; it animates down immediately
                        // instead. animateContentSize covers every open path so a loaded card
                        // never snaps between spinner height and final height.
                        .then(
                            Modifier.animateContentSize(
                                animationSpec = tween(250),
                                alignment = Alignment.TopCenter,
                            ),
                        )
                        .then(
                            if (openBefore && !barrenIncoming && breakdown == null && stableCardH > 0.dp) {
                                Modifier.height(stableCardH)
                            } else {
                                Modifier
                            }
                        ),

                    // Keep the actual card CONTENT at the top of the animated area.
                    contentAlignment = Alignment.TopCenter,
                ) {
                    CountryDetailCard(
                        countryName = selName!!,
                        access = selectedAccess,
                        breakdown = breakdown,
                        onDismiss = { pick(null) },

                        // No size animation here anymore.
                        modifier = Modifier.fillMaxWidth(),

                        isHome = selected in s.homeCountries,
                        isOwnCovered = selected in s.ownVisaCountries,
                        isResidence = selected in s.residenceCountries,
                        homePassport =
                            if (selected in s.homeCountries) {
                                s.world.countries[selected]?.name
                            } else {
                                null
                            },
                        daysLabel = daysLabel,
                    )
                }
            }
        }
        if (selName == null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { legendH = Dp(it.height / density.density) },
            ) {
                // Homes are their own "Your country" swatch, so legendCounts keeps them out of the
                // level buckets and out of the slate count; the slate row counts what the
                // canvas can actually paint (the geometry set, incl. e.g. AQ which has no DB row).
                MapLegend(
                    legendCounts(s.geometry.countries.keys, s.access, s.homeCountries, s.residenceCountries),
                    s.homeCountries.size,
                )
            }
        }
        val dropDur = if (LocalReducedMotion.current) 0 else Motion.MICRO
        AnimatedVisibility(
            visible = query.isNotBlank(),
            enter = fadeIn(tween(dropDur)) + scaleIn(tween(dropDur), initialScale = 0.95f),
            exit = fadeOut(tween(dropDur)) + scaleOut(tween(dropDur), targetScale = 0.95f),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 0.dp,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .offset(y = searchBarH)
                    .heightIn(max = 240.dp)
                    .tapSink(),
            ) {
                val searchScroll = rememberScrollState()
                val (searchTop, searchEnd) = searchScroll.hazeAlphas()
                HazeBox(searchTop, searchEnd, MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    Column(Modifier.verticalScroll(searchScroll)) {
                        if (matches.isEmpty()) {
                            // match CountryRow's inner horizontal padding so the text aligns with rows
                            EmptyState("No matching countries", modifier = Modifier.padding(horizontal = 10.dp))
                        } else {
                            matches.forEach { c ->
                                CountryRow(name = c.name, onClick = { pickFromSearch(c.iso2) })
                            }
                        }
                    }
                }
            }
        }
    }
}