package com.ridervoice.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared Safe-Area Inset Contract for RiderVoice screens.
 *
 * Root Scaffolds must pass [ZeroWindowInsets] for `contentWindowInsets` so they never
 * automatically inject duplicate systemBar padding on top of per-bar or per-screen
 * safe-area modifiers. Each inset has exactly one owner:
 * - Top bar / header: [rvTopSafeArea] / [RvSafeTopInsets] (`max(systemBars, displayCutout)` top + sides)
 * - Bottom bar / pinned actions: [rvBottomSafeArea] / [RvSafeBottomInsets] (`max(navigationBars, displayCutout, tappableElement)` bottom + sides)
 * - Side/scroll body: [rvHorizontalSafeArea] / [RvSafeHorizontalInsets] (`max(systemBars, displayCutout)` horizontal sides)
 */
val ZeroWindowInsets = WindowInsets(0, 0, 0, 0)

val RvSafeTopInsets: WindowInsets
    @Composable
    get() = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

val RvSafeBottomInsets: WindowInsets
    @Composable
    get() = WindowInsets.navigationBars
        .union(WindowInsets.displayCutout)
        .union(WindowInsets.tappableElement)
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

val RvSafeHorizontalInsets: WindowInsets
    @Composable
    get() = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal)

fun Modifier.rvHorizontalSafeArea(): Modifier = composed {
    this.windowInsetsPadding(RvSafeHorizontalInsets)
}

fun Modifier.rvTopSafeArea(): Modifier = composed {
    this.windowInsetsPadding(RvSafeTopInsets)
}

fun Modifier.rvBottomSafeArea(): Modifier = composed {
    this.windowInsetsPadding(RvSafeBottomInsets)
}

@Composable
fun rememberRvSafeTopPadding(): Dp {
    val density = LocalDensity.current
    return with(density) { RvSafeTopInsets.getTop(this).toDp() }
}

val RvMaxContentWidth = 640.dp

fun Modifier.rvResponsiveContentWidth(maxWidth: Dp = RvMaxContentWidth): Modifier = composed {
    val widthDp = LocalConfiguration.current.screenWidthDp
    if (widthDp >= 600) this.widthIn(max = maxWidth) else this
}
