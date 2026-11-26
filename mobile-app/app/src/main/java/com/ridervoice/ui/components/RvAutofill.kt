@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.ridervoice.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

/** Semantic marker exposing the Android/Compose autofill type for UI tests and accessibility. */
@ExperimentalComposeUiApi
val RvAutofillContentTypeKey = SemanticsPropertyKey<AutofillType>("RvAutofillContentType")

@ExperimentalComposeUiApi
var SemanticsPropertyReceiver.rvAutofillContentType by RvAutofillContentTypeKey

/**
 * Registers an [AutofillNode] in [LocalAutofillTree] for [autofillType], forwards platform fill
 * callbacks to [onFill], brings the focused control into view above the IME, and attaches
 * [RvAutofillContentTypeKey] for test verification.
 */
@OptIn(ExperimentalFoundationApi::class)
@ExperimentalComposeUiApi
fun Modifier.rvAutofill(
    autofillType: AutofillType,
    onFill: (String) -> Unit,
    bringIntoViewOnFocus: Boolean = true
): Modifier = composed {
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current
    val scope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    val node = remember(autofillType) {
        AutofillNode(
            autofillTypes = listOf(autofillType),
            onFill = onFill
        )
    }

    DisposableEffect(autofillTree, node) {
        autofillTree += node
        onDispose {
            // AutofillTree keeps weak/identity map entries; unregister when left composition if needed.
        }
    }

    val base = if (bringIntoViewOnFocus) {
        this.bringIntoViewRequester(bringIntoViewRequester)
    } else {
        this
    }

    base
        .semantics {
            rvAutofillContentType = autofillType
        }
        .onGloballyPositioned { coordinates ->
            node.boundingBox = coordinates.boundsInWindow()
        }
        .onFocusChanged { focusState ->
            if (focusState.isFocused) {
                autofill?.requestAutofillForNode(node)
                if (bringIntoViewOnFocus) {
                    scope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            } else {
                autofill?.cancelAutofillForNode(node)
            }
        }
}

/**
 * Attaches a [BringIntoViewRequester] that scrolls the focused input field into the viewport
 * whenever the soft keyboard opens or focus moves between fields.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.rvBringIntoViewOnFocus(): Modifier = composed {
    val scope = rememberCoroutineScope()
    val requester = remember { BringIntoViewRequester() }
    this
        .bringIntoViewRequester(requester)
        .onFocusChanged { state ->
            if (state.isFocused) {
                scope.launch {
                    requester.bringIntoView()
                }
            }
        }
}
