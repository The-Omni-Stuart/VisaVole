package com.cbkres.visavole.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/** True when the OS animation scale is off, so autonomous loops must hold still. */
internal val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** Android's animation scale is 0 when the user has turned animations off in accessibility. */
internal fun reducedMotionFromScale(scale: Float): Boolean = scale <= 0f
