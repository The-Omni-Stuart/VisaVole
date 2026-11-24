package com.cbkres.visavole.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.cbkres.visavole.R
import com.cbkres.visavole.data.BackupManager
import com.cbkres.visavole.data.PersistedState
import com.cbkres.visavole.domain.FreshnessLevel
import com.cbkres.visavole.domain.freshnessFor
import com.cbkres.visavole.ui.AccessViewModel
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.StatusPill
import com.cbkres.visavole.ui.app.AppState
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor
import com.cbkres.visavole.ui.theme.ThemePrefs
import com.cbkres.visavole.ui.VoleAlertDialog
import kotlin.math.roundToInt

private const val SOURCE_URL = "https://github.com/The-Omni-Stuart/VisaVole"
private const val ISSUES_URL = "https://github.com/The-Omni-Stuart/VisaVole/issues"
private const val LICENSE_URL = "https://github.com/The-Omni-Stuart/VisaVole/blob/main/LICENSE"
private const val VISA_DB_URL = "https://github.com/The-Omni-Stuart/VisaDB"

/**
 * The app's info + settings screen (opened from the top-bar gear). Shows the app identity and
 * links, and the two actions that manage the user's data: exporting and restoring a backup.
 * All backup logic lives in [BackupManager] behind the ViewModel; this screen only triggers the
 * pickers and confirms the restore.
 */
// No inner Scaffold/TopAppBar: this screen is a destination under the app's persistent top bar
// (which shows the back arrow), so there are no doubled status-bar insets and the content starts
// exactly where the other tabs' content starts.
@Composable
internal fun SettingsScreen(
    vm: AccessViewModel,
    ready: AppState.Ready,
    themeMode: ThemePrefs.Mode,
    dynamicColor: Boolean,
    onThemeModeChanged: (ThemePrefs.Mode) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pendingRestore by remember { mutableStateOf<PersistedState?>(null) }
    // P3: same edge-fade scroll cue as the Documents/Trips lists. Kept scrollable (not dropped)
    // so a short screen still scrolls; now that the nav bar is hidden while Settings is open the
    // content fits on tall screens, so the haze stays 0 there and only appears if it overflows.
    val settingsScroll = rememberScrollState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) vm.exportBackup(uri) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.prepareRestore(uri) { pendingRestore = it } }

    HazeBox(
        settingsScroll,
        MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(settingsScroll)
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon()
            Spacer(Modifier.height(20.dp))
            Text("Visa Vole", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Developed by The Omni Stuart © 2026",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            Text("Display", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(
                    ThemePrefs.Mode.SYSTEM to "Auto",
                    ThemePrefs.Mode.LIGHT to "Light",
                    ThemePrefs.Mode.DARK to "Dark",
                ).forEach { (mode, label) ->
                    val selected = themeMode == mode
                    OutlinedButton(
                        onClick = { onThemeModeChanged(mode) },
                        modifier = Modifier
                            .weight(1f)
                            // The tint alone does not announce selection; a RadioButton role carries it.
                            .selectable(selected, role = Role.RadioButton) { onThemeModeChanged(mode) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        ),
                    ) {
                        Text(label)
                    }
                }
            }
            // Dynamic colour needs Android S+ (the same gate as VisaVoleTheme in Theme.kt); on
            // Android 9-11 the switch would be a visible no-op.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        // One node carries the state and the touch target; the Switch inside is decoration.
                        .toggleable(value = dynamicColor, role = Role.Switch, onValueChange = onDynamicColorChanged)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        "Dynamic color",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = dynamicColor, onCheckedChange = null)
                }
            }
            Spacer(Modifier.height(48.dp))

            OutlinedButton(
                onClick = { openUrl(context, SOURCE_URL) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Source Code")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { openUrl(context, ISSUES_URL) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Found a bug?", style = MaterialTheme.typography.titleSmall)
                    Text("Report it here!", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(40.dp))
            HorizontalDivider()
            Spacer(Modifier.height(40.dp))
            OutlinedButton(
                onClick = { exportLauncher.launch(BackupManager.BACKUP_FILE_NAME) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Export Data Backup")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                // JSON only: backups are small text files; keeping the picker to text types
                // steers users away from offering the app a video/ISO (imports are capped anyway).
                onClick = { restoreLauncher.launch(arrayOf("application/json", "text/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore from Backup")
            }
            Spacer(Modifier.height(48.dp))

            Text(
                "Thank you for using Visa Vole!\nMade with love in 🏴󠁧󠁢󠁳󠁣󠁴󠁿 and 🇨🇿.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Proudly free and open source under the ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "GPLv3",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    // the bare labelSmall word was a ~40×20dp target; pad it to a ≥48dp hit
                    // area (padding before clickable so the padding counts toward the target).
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .clickable { openUrl(context, LICENSE_URL) },
                )
            }
            Spacer(Modifier.height(16.dp))
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrDefault("dev")
            Text(
                "Version $version",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            DataBuildFooter(ready)
        }
    }

    val pending = pendingRestore
    if (pending != null) {
        VoleAlertDialog(
            title = "Restore backup?",
            confirmLabel = "Restore",
            confirmDestructive = true,
            onConfirm = {
                vm.applyRestore(pending)
                pendingRestore = null
            },
            onDismiss = { pendingRestore = null },
            content = {
                Text(
                    "Replace your current " +
                        pluralStringResource(R.plurals.settings_docs_count, ready.docs.size, ready.docs.size) +
                        " and " +
                        pluralStringResource(R.plurals.settings_trips_count, ready.trips.size, ready.trips.size) +
                        " with the backup's " +
                        pluralStringResource(R.plurals.settings_docs_count, pending.docs.size, pending.docs.size) +
                        " and " +
                        pluralStringResource(R.plurals.settings_trips_count, pending.trips.size, pending.trips.size) +
                        "?",
                )
            },
        )
    }
}

/**
 * The bundled dataset's audit trail as two compact pills under the version line: a freshness dot
 * with the build date, plus a counts pill only when the load-time probes caught lapsed or
 * soon-lapsing stay rules. Freshness is bounded by the refresh cadence, so the copy says "data
 * build", never "out of date"; the full audit dates live in the pill's accessibility label, and
 * tapping the version pill (the row's only link) opens the upstream dataset page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DataBuildFooter(ready: AppState.Ready) {
    val context = LocalContext.current
    val isDark = LocalEffectiveDarkTheme.current
    val meta = ready.world.visaDbMeta
    val freshness = freshnessFor(
        generated = meta.generated,
        rulesVerifiedAt = meta.rulesVerifiedAt,
        nextReview = meta.nextReview,
        lapsedRules = meta.lapsedRules,
        lapsingSoon = meta.lapsingSoon,
        today = ready.today,
    )
    val dotColour = when (freshness.level) {
        FreshnessLevel.GREEN -> severityColor(Severity.OK, isDark)
        FreshnessLevel.AMBER -> severityColor(Severity.CAUTION, isDark)
        FreshnessLevel.RED -> severityColor(Severity.BAD, isDark)
        FreshnessLevel.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Counts guarded once so the compact pill and its spoken-out label can never disagree.
    val lapsedRules = meta.lapsedRules.takeIf { it > 0 }
    val lapsingSoon = meta.lapsingSoon.takeIf { it > 0 }
    val grantsLapsed = meta.lapsedCorridors.takeIf { it > 0 }
    val grantsLapsingSoon = meta.corridorsLapsingSoon.takeIf { it > 0 }
    val pills = dataBuildPills(
        meta = meta,
        unknownLabel = stringResource(R.string.settings_data_build_unknown),
        warningParts = listOfNotNull(
            lapsedRules?.let { pluralStringResource(R.plurals.settings_data_lapsed_pill, it, it) },
            lapsingSoon?.let { pluralStringResource(R.plurals.settings_data_lapsing_soon_pill, it, it) },
            grantsLapsed?.let { pluralStringResource(R.plurals.settings_data_lapsed_pill, it, it) },
            grantsLapsingSoon?.let { pluralStringResource(R.plurals.settings_data_lapsing_soon_pill, it, it) },
        ),
        separator = stringResource(R.string.settings_data_warn_separator),
    )
    val details = if (pills.complete) {
        stringResource(R.string.settings_data_build_details, meta.generated, meta.rulesVerifiedAt, meta.nextReview)
    } else {
        stringResource(R.string.settings_data_build_no_details)
    }
    val warnText = severityColor(Severity.WARN, isDark)
    val openLabel = stringResource(R.string.settings_data_source_open)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.settings_data_build_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusPill(
                    text = pills.buildLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    alpha = 0.10f,
                    fontWeight = MaterialTheme.typography.labelSmall.fontWeight,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                    leading = {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColour),
                        )
                    },
                    a11yLabel = details,
                    onClick = { openUrl(context, VISA_DB_URL) },
                    onClickLabel = openLabel,
                )
            }
            pills.warningLabel?.let { label ->
                // The pill text is compact ("2 lapsed"); the announcement spells the counts out.
                StatusPill(
                    text = label,
                    color = warnText,
                    alpha = 0.12f,
                    fontWeight = MaterialTheme.typography.labelSmall.fontWeight,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                    leading = {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = warnText,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    a11yLabel = listOfNotNull(
                        lapsedRules?.let { pluralStringResource(R.plurals.settings_data_lapsed, it, it) },
                        lapsingSoon?.let { pluralStringResource(R.plurals.settings_data_lapsing_soon, it, it) },
                        grantsLapsed?.let { pluralStringResource(R.plurals.settings_data_grants_lapsed, it, it) },
                        grantsLapsingSoon?.let { pluralStringResource(R.plurals.settings_data_grants_lapsing_soon, it, it) },
                    ).joinToString(stringResource(R.string.settings_data_warn_separator)),
                )
            }
        }
    }
}

/** The app icon, resolved from the manifest at runtime so it always matches what's installed. */
// Suppressing the Compose "resource via LocalContext" rule on purpose: we rasterize the
// manifest icon (adaptive icons can't be loaded through painterResource), and the icon
// resource ID is fixed for the lifetime of the installed app, so no recomposition risk.
@Suppress("LocalContextGetResourceValueCall")
@Composable
private fun AppIcon() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val iconRes = remember { context.packageManager.getApplicationInfo(context.packageName, 0).icon }
    // rasterize at the display size (96.dp in px), not a fixed 384 — the old bitmap was
    // ~4x oversized for the 96.dp slot. Capped so a very high density can't over-allocate.
    val size = (96.dp.value * density.density).roundToInt().coerceIn(1, 512)
    // Adaptive icons can't be loaded through painterResource, so rasterize the drawable instead
    // (drawing the AdaptiveIconDrawable applies the launcher mask).
    val iconBitmap = remember(iconRes, size) {
        runCatching {
            val drawable = context.getDrawable(iconRes) ?: throw IllegalStateException("no app icon")
            val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            bitmap.asImageBitmap()
        }.getOrNull()
    }
    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = "Visa Vole app icon",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(20.dp)),
        )
    } else {
        Spacer(Modifier.size(96.dp))
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        // A bare ACTION_VIEW with NO mime type is the correct way to open a web URL: the https
        // scheme routes it to a browser. ( first added type="text/html", but on a real device
        // that made the intent match text/html handlers — a notes app — instead of the browser.)
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }.onFailure {
        // A device without a browser would otherwise be a silent no-op.
        Toast.makeText(context, "Couldn't open this link — no browser available?", Toast.LENGTH_SHORT).show()
    }
}
