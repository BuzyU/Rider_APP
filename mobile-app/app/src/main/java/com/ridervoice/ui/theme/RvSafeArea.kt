package com.ridervoice.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Shared Safe-Area Inset Contract for RiderVoice screens.
 *
 * Root Scaffolds must pass [ZeroWindowInsets] for `contentWindowInsets` so they never
 * automatically inject duplicate systemBar padding on top of per-bar or per-screen
 * safe-area modifiers. Each inset has exactly one owner:
 * - Top bar / header: [rvTopSafeArea] (status bars + horizontal display cutout)
 * - Bottom bar / pinned actions: [rvBottomSafeArea] (navigation bars + horizontal display cutout)
 * - Side/scroll body: [rvHorizontalSafeArea] (horizontal display cutout in landscape)
 */
val ZeroWindowInsets = WindowInsets(0, 0, 0, 0)

fun Modifier.rvHorizontalSafeArea(): Modifier = composed {
    this.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
}

fun Modifier.rvTopSafeArea(): Modifier = composed {
    this.statusBarsPadding().rvHorizontalSafeArea()
}

fun Modifier.rvBottomSafeArea(): Modifier = composed {
    this.navigationBarsPadding().rvHorizontalSafeArea()
}
