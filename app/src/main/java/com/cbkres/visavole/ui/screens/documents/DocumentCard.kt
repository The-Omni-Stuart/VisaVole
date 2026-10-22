package com.cbkres.visavole.ui.screens.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.DocStatus
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.EntryState
import com.cbkres.visavole.domain.ExpiryReason
import com.cbkres.visavole.domain.entryType
import com.cbkres.visavole.domain.residenceClassFor
import com.cbkres.visavole.ui.StatusPill
import com.cbkres.visavole.ui.docTitle
import com.cbkres.visavole.ui.screens.map.DIM_ALPHA
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
internal fun DocumentCard(
    doc: Document,
    world: WorldData,
    today: LocalDate,
    status: DocStatus? = null,
    supersededByLabel: String? = null,
    canRemove: Boolean = true,
    passportNumber: Int? = null,
    isPrimary: Boolean = false,
    onRemove: () -> Unit,
    onRemoveBlocked: () -> Unit = {},
    onEdit: () -> Unit,
    onStar: (() -> Unit)? = null,
    flashTrigger: Int = 0,
) {
    val isPassport = doc.kind is DocKind.Passport
    val surface = MaterialTheme.colorScheme.onSurface
    val (typeLabel, typeTone) = _root_ide_package_.com.cbkres.visavole.ui.docTypeBadge(
        doc,
        world,
        LocalEffectiveDarkTheme.current
    )
    _root_ide_package_.com.cbkres.visavole.ui.VisaListCard(
        flashTrigger = flashTrigger,
        actions = {
            if (onStar != null) {
                IconButton(onClick = onStar) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = if (isPrimary) "Primary passport" else "Set as primary",
                        // material-icons-core has no true Star outline (bug), so the non-primary
                        // star is the same solid star in a faded grey to stay monochrome.
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isPrimary) 1f else DIM_ALPHA),
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            IconButton(
                onClick = { if (canRemove) onRemove() else onRemoveBlocked() },
                modifier = Modifier.alpha(if (canRemove) 1f else DIM_ALPHA),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = if (canRemove) "Delete" else "In use by a trip",
                    tint = if (canRemove) surface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        // Row 1: the type badge (in the tone the map uses for what the doc unlocks) + the
        // country(ies) it applies to; a passport's stable "(N)" number trails the name.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusPill(typeLabel, typeTone)
            Text(
                buildAnnotatedString {
                    append(docTitle(doc, world))
                    if (isPassport && passportNumber != null) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append("  ($passportNumber)")
                        }
                    }
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // Row 2: the validity period, trips-card style; dropped when the document has no dates.
        docValidityText(doc)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Row 3: the structural details left, the status pill right — a consistent badge column.
        // Top-aligned so the pill sits on the first line when the details text wraps.
        if (status != null) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val details = docDetailsAnnotated(
                    doc,
                    world,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    LocalEffectiveDarkTheme.current
                )
                if (details != null) {
                    Text(
                        details,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                ExpiryStatusPill(today, status, supersededByLabel)
            }
        }
    }
}

/**
 * The card's details line, left of the status pill: a known holding's name (when it is not already
 * the title), then the mobility bloc, then the entry type (tinted) or the residence class. Null
 * when the document carries no structural detail (a plain passport).
 */
private fun docDetailsAnnotated(doc: Document, world: WorldData, base: Color, isDark: Boolean): AnnotatedString? {
    val k = doc.kind
    val regime = (k as? DocKind.Custom)?.blocId?.let { id -> world.regimes.firstOrNull { r -> r.id == id }?.name }
    val holdingName = when (k) {
        is DocKind.Holding -> world.holdings[k.holdingId]?.name
        is DocKind.Custom -> k.holdingId?.let { world.holdings[it]?.name }
        is DocKind.Passport -> null
    }
    val b = AnnotatedString.Builder()
    fun part(text: String?, color: Color = base) {
        if (text.isNullOrEmpty()) return
        val s = b.length
        b.append(if (b.length > 0) " · " else "")
        b.append(text)
        b.addStyle(SpanStyle(color = color), s, b.length)
    }
    part(holdingName?.takeIf { it != docTitle(
        doc,
        world
    )
    })
    part(regime?.let { "bloc $it" })
    doc.entryType()?.let { type ->
        val (label, severity) = when (type) {
            "single" -> "single" to Severity.BAD
            "double" -> "double" to Severity.WARN
            else -> "multiple" to Severity.OK
        }
        part("${label} entry",
            severityColor(severity, isDark)
        )
    } ?: run {
        part(doc.residenceClassFor(world)?.label)
    }
    return if (b.length == 0) null else b.toAnnotatedString()
}

/** Days from [today] until [date]; null when unset. */
private fun daysUntil(date: LocalDate?, today: LocalDate): Long? =
    date?.let { ChronoUnit.DAYS.between(today, it) }

/**
 * The per-document status pill, driven by the unified [DocStatus]: the reason (date, entries, or
 * supersession) attached to the effective expiry decides the text — "Expired on …" / "Entries used
 * up" / "Replaced by …" — so a lapsed document always says *why* it is in the archive.
 */
@Composable
private fun ExpiryStatusPill(
    today: LocalDate,
    status: DocStatus?,
    supersededByLabel: String? = null,
) {
    val effective = status?.effectiveExpiry
    val entry = status?.entries
    val days = daysUntil(effective, today)
    val replacedBy = supersededByLabel ?: "a newer document"
    val (text, severity, bold) = when {
        // Same state-to-colour map as the ring and the tab-top summary line: in use with nothing
        // left is terminal (red), in use or down to the last entry is a watch-it state (amber).
        entry?.total != null && entry.state == EntryState.IN_USE ->
            Triple("In use", if (entry.remaining == 0) Severity.BAD else Severity.WARN, false)
        status?.reason == ExpiryReason.SUPERSEDED && status.expired ->
            Triple("Replaced by $replacedBy", Severity.BAD, true)
        status?.expired == true && status.reason == ExpiryReason.ENTRIES ->
            Triple("Entries used up", Severity.BAD, true)
        status?.expired == true -> Triple("Expired on $effective", Severity.BAD, true)
        status?.reason == ExpiryReason.SUPERSEDED ->
            Triple("Replaced by $replacedBy from $effective", Severity.WARN, false)
        entry?.total != null && entry.remaining != null && entry.remaining > 0 ->
            Triple("${entry.remaining} ${if (entry.remaining == 1) "entry" else "entries"} left", if (entry.remaining == 1) Severity.WARN else Severity.OK, false)
        days == null -> Triple("No expiry", Severity.OK, false)
        days <= 7 -> Triple("Expiring soon ($days ${if (days == 1L) "day" else "days"})", Severity.WARN, false)
        else -> Triple("Valid for $days ${if (days == 1L) "day" else "days"}", Severity.OK, false)
    }
    // Long pills (e.g. "Replaced by <label>") must wrap instead of squeezing the card's weighted
    // text column; the cap keeps the title column readable for the longest labels.
    _root_ide_package_.com.cbkres.visavole.ui.StatusPill(
        text,
        severityColor(
            severity,
            LocalEffectiveDarkTheme.current
        ),
        bold = bold,
        maxWidth = 120.dp
    )
}

/** The card's validity-period line; null (line dropped) when the document carries no dates. */
private fun docValidityText(doc: Document): String? = when {
    doc.validFrom != null && doc.expiry != null -> "${doc.validFrom} → ${doc.expiry}"
    doc.validFrom != null -> "from ${doc.validFrom}"
    doc.expiry != null -> "to ${doc.expiry}"
    else -> null
}