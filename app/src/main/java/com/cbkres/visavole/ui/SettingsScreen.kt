package com.cbkres.visavole.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.data.BackupManager
import com.cbkres.visavole.data.PersistedState

private const val SOURCE_URL = "https://github.com/The-Omni-Stuart/VisaVole"
private const val ISSUES_URL = "https://github.com/The-Omni-Stuart/VisaVole/issues"
private const val LICENSE_URL = "https://github.com/The-Omni-Stuart/VisaVole/blob/main/LICENSE"

/**
 * The app's info + settings screen (opened from the top-bar gear). Shows the app identity and
 * links, and the two actions that manage the user's data: exporting and restoring a backup.
 * All backup logic lives in [BackupManager] behind the ViewModel; this screen only triggers the
 * pickers and confirms the restore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: AccessViewModel,
    ready: AppState.Ready,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pendingRestore by remember { mutableStateOf<PersistedState?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) vm.exportBackup(uri) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.prepareRestore(uri) { pendingRestore = it } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 48.dp),
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
                onClick = { restoreLauncher.launch(arrayOf("*/*")) },
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
@Composable
private fun AppIcon() {
    val context = LocalContext.current
    val iconRes = remember { context.packageManager.getApplicationInfo(context.packageName, 0).icon }
    // Adaptive icons can't be loaded through painterResource, so rasterize the drawable instead
    // (drawing the AdaptiveIconDrawable applies the launcher mask).
    val iconBitmap = remember(iconRes) {
        runCatching {
            val drawable = context.getDrawable(iconRes) ?: throw IllegalStateException("no app icon")
            val size = 384
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
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
