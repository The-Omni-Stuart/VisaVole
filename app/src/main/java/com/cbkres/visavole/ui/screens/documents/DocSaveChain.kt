package com.cbkres.visavole.ui.screens.documents

import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.GuardAction
import com.cbkres.visavole.domain.GuardContext
import com.cbkres.visavole.domain.GuardEngine
import com.cbkres.visavole.domain.GuardFinding
import com.cbkres.visavole.domain.GuardMutation
import com.cbkres.visavole.domain.duplicateSignature

/**
 * The document dialog's save decision chain, as a pure unit so the JVM can pin every branch:
 * a save walks block → multi-country confirm → mutation confirm → remaining-warning confirm →
 * save, and each confirm re-enters with its own skip flag. The invariant the dialog relies on:
 * no step may end in a save while a warning the user has not seen is still outstanding.
 */
internal sealed class DocSaveDecision {
    /** A hard block; [duplicate], when set, is the existing document the candidate duplicates. */
    data class Blocked(val finding: GuardFinding, val duplicate: Document?) : DocSaveDecision()
    data object ConfirmMultiCountry : DocSaveDecision()
    /** [warning] is what the mutation dialog explains alongside the mutation. */
    data class ConfirmMutation(val mutations: List<GuardMutation>, val warning: GuardFinding?) : DocSaveDecision()
    data class ShowSaveWarnings(val warnings: List<GuardFinding>) : DocSaveDecision()
    data class Save(val doc: Document) : DocSaveDecision()
}

/**
 * One step of the chain. [skipMultiCountry] and [skipMutation] mean "the user already confirmed
 * that step for this exact candidate" — the confirmation dialogs re-enter with them.
 */
internal fun decideDocSave(
    doc: Document,
    guard: GuardContext,
    skipMultiCountry: Boolean = false,
    skipMutation: Boolean = false,
): DocSaveDecision {
    val action = guard.docs.firstOrNull { it.id == doc.id }?.let { GuardAction.UpdateDocument(doc, it) }
        ?: GuardAction.AddDocument(doc)
    val result = GuardEngine.evaluate(action, guard)

    result.blocks.firstOrNull()?.let { block ->
        val duplicate = if (block.code == "doc.duplicate") {
            guard.docs.firstOrNull { it.id != doc.id && it.duplicateSignature() == doc.duplicateSignature() }
        } else {
            null
        }
        return DocSaveDecision.Blocked(block, duplicate)
    }
    if (!skipMultiCountry && result.warnings.any { it.code == MULTI_COUNTRY }) {
        return DocSaveDecision.ConfirmMultiCountry
    }
    if (result.mutations.isNotEmpty()) {
        if (!skipMutation) {
            return DocSaveDecision.ConfirmMutation(result.mutations, mutationDialogWarning(result.warnings))
        }
    }
    // Every warning the dialogs above did not show must reach the user before the edit commits,
    // including those that arrive alongside a confirmed mutation.
    val shownInMutationDialog = if (skipMutation) mutationDialogWarning(result.warnings) else null
    val unconfirmed = result.warnings.filterNot { it.code == MULTI_COUNTRY || it == shownInMutationDialog }
    if (unconfirmed.isNotEmpty()) return DocSaveDecision.ShowSaveWarnings(unconfirmed)
    return DocSaveDecision.Save(doc)
}

/** The warning the mutation dialog quotes: the single-per-country notice, else any first. */
private fun mutationDialogWarning(warnings: List<GuardFinding>): GuardFinding? =
    warnings.firstOrNull { it.code == SINGLE_PER_COUNTRY } ?: warnings.firstOrNull()

private const val MULTI_COUNTRY = "doc.visa.multiCountry"
private const val SINGLE_PER_COUNTRY = "doc.single.perCountry"
