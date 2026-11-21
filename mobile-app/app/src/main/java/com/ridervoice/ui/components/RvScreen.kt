package com.ridervoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridervoice.ui.theme.*

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
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 20.sp,
                        color = Ink,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .padding(start = 8.dp, end = 4.dp)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(PaperRaised)
                                .border(1.dp, InkHairline, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Ink,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = Paper,
                    titleContentColor = Ink
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            if (bottomBar != null) {
                Surface(
                    color = Paper,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = RvSpacing.lg, vertical = RvSpacing.sm)
                    ) {
                        bottomBar()
                    }
                }
            }
        },
        containerColor = Paper
    ) { innerPadding ->
        Column(
            modifier = if (scrollable) {
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = RvSpacing.lg)
            } else {
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = RvSpacing.lg)
            }
        ) {
            content()
        }
    }
}
