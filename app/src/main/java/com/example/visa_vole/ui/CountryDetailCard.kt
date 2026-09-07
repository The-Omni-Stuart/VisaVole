package com.example.visa_vole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.visa_vole.domain.Access
import com.example.visa_vole.domain.AccessLevel
import com.example.visa_vole.domain.DocAccess

@Composable
fun CountryDetailCard(
    countryName: String,
    access: Access?,
    breakdown: List<DocAccess> = emptyList(),
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val level = access?.level ?: AccessLevel.UNKNOWN
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(countryName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val c = colorFor(level)
                Column(
                    Modifier
                        .background(c, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(level.label(), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(12.dp))
                if (access?.days != null) {
                    Text(
                        "${access?.days} days",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (!access?.reason.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    access?.reason.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (breakdown.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Enter with",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    breakdown.forEachIndexed { i, entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.access.level.label(),
                                color = colorFor(entry.access.level),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                entry.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (entry.access.days != null) {
                                Text(
                                    "${entry.access.days} days",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (i < breakdown.size - 1) Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}
