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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
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
import com.cbkres.visavole.domain.AccessLevel
import com.cbkres.visavole.domain.AccessModel
import com.cbkres.visavole.domain.DocAccess
import com.cbkres.visavole.domain.searchCountries
import com.cbkres.visavole.ui.CountryRow
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.app.AppState
import com.cbkres.visavole.ui.hazeAlphas
import com.cbkres.visavole.ui.screens.EmptyState
import kotlinx.coroutines.Dispatchers
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
    // P3-59: the search field must blur when the map is tapped or Back is pressed, otherwise it
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
    // and repaint every country for the whole duration of the card slide (P1-13).
    val pick: (String?) -> Unit = remember {
        { iso: String? ->
            keyboardController?.hide()
            if (searchFocused) focusManager.clearFocus()
            onQuery("")
            onSelect(iso)
        }
    }
    val density = LocalDensity.current
    var searchBarH by remember { mutableStateOf(0.dp) }
    var cardH by remember { mutableStateOf(0.dp) }
    var lastStableCardH by remember { mutableStateOf(0.dp) }
    var legendH by remember { mutableStateOf(0.dp) }
    val selName = selected?.let { iso -> s.world.countries[iso]?.name ?: s.geometry.countries[iso]?.name ?: iso }
    // selName falls back to the ISO code, so it is null exactly when nothing is selected.
    val cardVisible = selected != null
    // The bottom padding the map's floating UI keeps between itself and the card/legend: the
    // measured card height plus 24dp while a card is up, the legend height plus 8dp otherwise.
    val controlsTargetBottomPadding =
        if (cardVisible) cardH + 24.dp
        else legendH + 8.dp

    val centeringBottomPadding =
        if (cardVisible) {
            if (lastStableCardH > 0.dp) {
                lastStableCardH + 24.dp
            } else {
                24.dp
            }
        } else {
            legendH + 8.dp
        }
    // Animated (same spring as the card slide). The map canvas is never shrunk for the card —
    // the card slides over the full-bleed map — so no dark band can appear between the map
    // edge and the card while the fixed 420dp drop exceeds the card's actual height. The
    // floating zoom controls ride cardH above the bottom, keeping them 24dp above the card's
    // top edge. The map's focus-fit uses the SETTLED padding instead, so its centre-on-country
    // target is stable while the card slides in (see WorldMapCanvas).
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
            onCountryTap = pick,
            homeCountries = s.homeCountries,
            ownVisaCountries = s.ownVisaCountries,
            controlsBottomPadding = controlsBottomPadding,
            centeringBottomPadding = centeringBottomPadding,
            topInset = searchBarH,
            modifier = Modifier.fillMaxSize(),
        )
        CountrySearchBar(
            query = query,
            onQuery = onQuery,
            // P3-16: the IME "Search" action picks the first match (previously it only hid the
            // keyboard, leaving the dropdown open with nothing selected).
            onSearch = { matches.firstOrNull()?.let { pick(it.iso2) } ?: keyboardController?.hide() },
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
                // Stay windows resolve against the passports that are not expired (P2-30).
                val validHomes = remember(s.docs, s.world, s.today) { AccessModel.validHomeCountries(s.docs, s.world, s.today) }
                // The per-selection breakdown + stay rule are the heavy per-zone access-model
                // lookups. Running them synchronously in composition froze the map (and the
                // centre-on-country tween) for ~1s on tap (P2-46). They now compute off the main
                // thread — the card slides in immediately with its cheap name/level and the
                // breakdown rows + exact stay label fill in a moment later.
                var breakdown by remember(selected) { mutableStateOf<List<DocAccess>?>(null) }
                var daysLabel by remember(selected) { mutableStateOf<String?>(null) }

                LaunchedEffect(selected, s.homeCountries, s.access, s.world, s.docs, s.trips, validHomes) {
                    val (b, dl) = withContext(Dispatchers.Default) {
                        val stayRule = when {
                            selected in s.homeCountries -> null
                            s.access[selected]?.level == AccessLevel.RESIDENCE -> null // you live here — no short-stay limit
                            else -> s.world.stayRuleFor(selected, validHomes, s.today)
                        }
                        AccessModel.breakdownFor(selected, s.docs, s.world, trips = s.trips) to
                                AccessModel.stayLabelFor(selectedAccess, stayRule)
                    }
                    breakdown = b
                    daysLabel = dl
                }

                val detailCardShape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()

                        // Move the whole thing when the card opens/closes.
                        .graphicsLayer {
                            translationY = cardDrop.toPx()
                        }

                        // These two belong OUTSIDE animateContentSize.
                        // They paint the entire animated envelope, including the
                        // temporary extra space while a large card shrinks.
                        .clip(detailCardShape)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        )

                        // The wrapper changes height, not CountryDetailCard itself.
                        .animateContentSize(
                            animationSpec = tween(600),
                            alignment = Alignment.TopCenter,
                            finishedListener = { _, targetSize ->
                                if (breakdown != null) {
                                    lastStableCardH =
                                        Dp(targetSize.height / density.density)
                                }
                            },
                        )

                        // While the next country is loading, hold exactly the
                        // previous finished card height.
                        .then(
                            if (
                                breakdown == null &&
                                lastStableCardH > 0.dp
                            ) {
                                Modifier.height(lastStableCardH)
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
                // Home countries are their own "Your country" swatch, so exclude them from the
                // per-level buckets — otherwise a home country (level FREEDOM) is double-counted
                // in both (P3-15). Null levels (no documented rule) are kept so the slate swatch
                // has a real count.
                MapLegend(
                    s.access.entries
                        .filter { it.key !in s.homeCountries }
                        .groupBy { it.value.level }
                        .mapValues { it.value.size },
                    s.homeCountries.size,
                )
            }
        }
        AnimatedVisibility(
            visible = query.isNotBlank(),
            enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.95f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.95f),
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
                    .heightIn(max = 240.dp),
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
                                CountryRow(name = c.name, onClick = { pick(c.iso2) })
                            }
                        }
                    }
                }
            }
        }
    }
}