package com.cbkres.visavole.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Central dialog chrome: every hand-rolled app dialog is wrapped in this so the surface
 * style and the pop-in/pop-out animation are defined exactly once.
 *
 * [width] is the fixed dialog width (340.dp for confirms, 400.dp for forms); [maxHeight]
 * caps form dialogs against the screen height; [dismissable] = false keeps filled forms
 * open on outside tap (use the explicit Cancel button to discard).
 */
@Composable
fun VisaDialog(
    onDismissRequest: () -> Unit,
    width: Dp = 340.dp,
    maxHeight: Dp? = null,
    dismissable: Boolean = true,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = dismissable),
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.92f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.92f),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .width(width)
                    .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier),
            ) {
                content()
            }
        }
    }
}
