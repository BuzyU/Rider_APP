package com.ridervoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.R
import com.ridervoice.ui.theme.*
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RvScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable (() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentWindowInsets = ZeroWindowInsets,
        topBar = {
            TopAppBar(
                modifier = Modifier.rvHorizontalSafeArea(),
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Ink,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() }
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .padding(start = 8.dp, end = 4.dp)
                                .size(RvTouchTarget.min)
                                .clip(CircleShape)
                                .background(PaperRaised)
                                .border(1.dp, InkHairline, CircleShape)
                        ) {
                            Icon(
                                imageVector = RvIcons.Back,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = Ink,
                                modifier = Modifier.size(20.dp).rvRtlMirrored()
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = Paper,
                    titleContentColor = Ink
                ),
                windowInsets = RvSafeTopInsets
            )
        },
        bottomBar = {
            if (bottomBar != null) {
                Surface(
                    color = Paper,
                    modifier = Modifier
                        .fillMaxWidth()
                        .rvBottomSafeArea()
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .rvResponsiveContentWidth()
                                .padding(horizontal = RvSpacing.lg, vertical = RvSpacing.sm)
                        ) {
                            bottomBar()
                        }
                    }
                }
            }
        },
        containerColor = Paper
    ) { innerPadding ->
        val baseModifier = Modifier
            .fillMaxWidth()
            .rvResponsiveContentWidth()
            .let { if (bottomBar == null) it.rvBottomSafeArea() else it.rvHorizontalSafeArea() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = if (scrollable) {
                    baseModifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = RvSpacing.lg)
                } else {
                    baseModifier
                        .fillMaxHeight()
                        .padding(horizontal = RvSpacing.lg)
                }
            ) {
                content()
            }
        }
    }
}
