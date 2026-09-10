package com.example.visa_vole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    isHome: Boolean = false,
    isOwnCovered: Boolean = false,
    homePassport: String? = null,
    daysLabel: String? = null,
) {
    val level = access?.level ?: AccessLevel.UNKNOWN
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(20.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(countryName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .background(
                            if (isHome) HOME
                            else if (level == AccessLevel.RESIDENCE) RESIDENCE_FILL
                            else if (level == AccessLevel.COVERED && isOwnCovered) COVERED_OWN
                            else colorFor(level),
                            CircleShape,
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (isHome) "Your country" else level.label(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                val shownDays = daysLabel ?: access?.days?.let { "$it days" }
                if (!shownDays.isNullOrBlank()) {
                    Spacer(Modifier.width(12.dp))
                    Text(shownDays, style = MaterialTheme.typography.titleMedium)
                }
            }
            val classSuffix = access?.residenceClass?.let { " (${it.label.lowercase()})" }.orEmpty()
            val subtext = if (isHome) "Passport: ${homePassport.orEmpty()}" else (access?.reason.orEmpty() + classSuffix).trim()
            if (subtext.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    subtext,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (breakdown.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Enter with",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column {
                    breakdown.forEachIndexed { i, entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isYourCountry = entry.access.reason == "Your country"
                            Text(
                                if (isYourCountry) "Your country" else entry.access.level.label(),
                                color = if (isYourCountry) HOME
                                    else if (entry.access.level == AccessLevel.COVERED && entry.isOwn) COVERED_OWN
                                    else colorFor(entry.access.level),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.widthIn(min = 72.dp),
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
                        if (i < breakdown.size - 1) Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
