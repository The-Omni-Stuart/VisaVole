package com.cbkres.visavole.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.cbkres.visavole.data.BackupManager
import com.cbkres.visavole.data.PersistedState
import com.cbkres.visavole.ui.theme.ThemePrefs

private const val SOURCE_URL = "https://github.com/The-Omni-Stuart/VisaVole"
private const val ISSUES_URL = "https://github.com/The-Omni-Stuart/VisaVole/issues"
private const val LICENSE_URL = "https://github.com/The-Omni-Stuart/VisaVole/blob/main/LICENSE"

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
fun SettingsScreen(
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
                "Developed by cbk-res, and co.",
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
                        modifier = Modifier.weight(1f),
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
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Dynamic color",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = dynamicColor, onCheckedChange = onDynamicColorChanged)
            }
            Spacer(Modifier.height(48.dp))

            OutlinedButton(onClick = { openUrl(context, SOURCE_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text("Source Code")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { openUrl(context, ISSUES_URL) }, modifier = Modifier.fillMaxWidth()) {
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
            Spacer(Modifier.height(64.dp))

            Text(
                "Thank you for using Visa Vole!\nMade with love in 🏴󠁧󠁢󠁳󠁣󠁴󠁿 and 🇨🇿.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Proudly free and open source under the ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "GPLv3",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { openUrl(context, LICENSE_URL) },
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
        }
    }

    val pending = pendingRestore
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore backup?") },
            text = {
                Text(
                    "Replace your current ${ready.docs.size} documents and ${ready.trips.size} trips " +
                        "with the backup's ${pending.docs.size} documents and ${pending.trips.size} trips?",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.applyRestore(pending)
                        pendingRestore = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("Cancel") }
            },
        )
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
    // P3-46: rasterize at the display size (96.dp in px), not a fixed 384 — the old bitmap was
    // ~4x oversized for the 96.dp slot. Capped so a very high density can't over-allocate.
    val size = (96.dp.value * density.density).roundToInt().coerceIn(1, 512)
    // Adaptive icons can't be loaded through painterResource, so rasterize the drawable instead
    // (drawing the AdaptiveIconDrawable applies the launcher mask).
    val iconBitmap = remember(iconRes, size) {
        runCatching {
            val drawable = context.getDrawable(iconRes) ?: throw IllegalStateException("no app icon")
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
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
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        // text/html keeps the link unambiguous: it opens straight in a browser instead of
        // surfacing an app chooser. (No CustomTabs dependency — three infrequently-tapped links
        // don't justify a warm-start browser lib for this app.)
        type = "text/html"
    }
    runCatching { context.startActivity(intent) }.onFailure {
        // P3-24: a device without a browser would otherwise be a silent no-op.
        Toast.makeText(context, "Couldn't open this link — no browser available?", Toast.LENGTH_SHORT).show()
    }
}
