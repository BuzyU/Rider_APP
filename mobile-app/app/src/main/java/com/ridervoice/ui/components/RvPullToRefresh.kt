package com.ridervoice.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.ridervoice.ui.theme.InkHairline
import com.ridervoice.ui.theme.Moss
import com.ridervoice.ui.theme.PaperRaised
import kotlin.math.roundToInt

/**
 * Zero-dependency Roadside Paper pull-to-refresh container compatible with Material3 1.0.1.
 * Pull down past [thresholdDp] to trigger [onRefresh]; displays a warm circular indicator.
 */
@Composable
fun RvPullToRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 72.dp.toPx() }
    val maxPullPx = with(density) { 112.dp.toPx() }

    var pullDistancePx by remember { mutableFloatStateOf(0f) }

    val targetOffsetPx = when {
        isRefreshing -> thresholdPx * 0.72f
        else -> pullDistancePx
    }
    val animatedOffsetPx by animateFloatAsState(
        targetValue = targetOffsetPx,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 420f)
    )

    val nestedScrollConnection = remember(isRefreshing, onRefresh, thresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y < 0f && pullDistancePx > 0f) {
                    val consumed = available.y.coerceAtLeast(-pullDistancePx)
                    pullDistancePx = (pullDistancePx + consumed).coerceAtLeast(0f)
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.Drag && !isRefreshing && available.y > 0f) {
                    val damped = available.y * 0.5f
                    val next = (pullDistancePx + damped).coerceAtMost(maxPullPx)
                    val delta = next - pullDistancePx
                    pullDistancePx = next
                    return Offset(0f, delta)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!isRefreshing && pullDistancePx >= thresholdPx) {
                    onRefresh()
                }
                pullDistancePx = 0f
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, animatedOffsetPx.roundToInt()) },
            content = content
        )

        val progress = (animatedOffsetPx / thresholdPx).coerceIn(0f, 1f)
        if (isRefreshing || animatedOffsetPx > 6f) {
            Surface(
                color = PaperRaised,
                shape = CircleShape,
                border = BorderStroke(1.dp, InkHairline),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        val indicatorY = (animatedOffsetPx - with(density) { 44.dp.toPx() })
                            .coerceAtLeast(with(density) { 8.dp.toPx() })
                        IntOffset(0, indicatorY.roundToInt())
                    }
                    .size(38.dp)
                    .graphicsLayer {
                        alpha = if (isRefreshing) 1f else progress
                        scaleX = if (isRefreshing) 1f else (0.7f + 0.3f * progress)
                        scaleY = if (isRefreshing) 1f else (0.7f + 0.3f * progress)
                    }
                    .testTag("rv_pull_refresh_indicator")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            color = Moss,
                            strokeWidth = 2.4.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = progress * 0.85f,
                            color = Moss,
                            strokeWidth = 2.4.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
