package com.ridervoice.ui.components

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ridervoice.R
import com.ridervoice.ui.theme.Brick
import com.ridervoice.ui.theme.Ink
import com.ridervoice.ui.theme.InkBorderStrong
import com.ridervoice.ui.theme.InkHairline
import com.ridervoice.ui.theme.InkSecondary
import com.ridervoice.ui.theme.PaperSunken
import com.ridervoice.ui.theme.Pop

val RvTextFieldUnfocusedBorderColorKey = SemanticsPropertyKey<Color>("RvTextFieldUnfocusedBorderColor")
var SemanticsPropertyReceiver.rvTextFieldUnfocusedBorderColor by RvTextFieldUnfocusedBorderColorKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rvTextFieldColors(
    focusedBorderColor: Color = Pop,
    unfocusedBorderColor: Color = InkBorderStrong,
    errorBorderColor: Color = Brick,
    disabledBorderColor: Color = InkHairline,
    containerColor: Color = PaperSunken,
    textColor: Color = Ink,
    cursorColor: Color = Pop,
    placeholderColor: Color = InkSecondary,
    focusedLabelColor: Color = focusedBorderColor,
    unfocusedLabelColor: Color = InkSecondary
): TextFieldColors = TextFieldDefaults.outlinedTextFieldColors(
    focusedBorderColor = focusedBorderColor,
    unfocusedBorderColor = unfocusedBorderColor,
    errorBorderColor = errorBorderColor,
    disabledBorderColor = disabledBorderColor,
    containerColor = containerColor,
    textColor = textColor,
    cursorColor = cursorColor,
    placeholderColor = placeholderColor,
    focusedLabelColor = focusedLabelColor,
    unfocusedLabelColor = unfocusedLabelColor
)

/**
 * Standardized outlined input field with >= 3:1 unfocused contrast ([InkBorderStrong]),
 * `52.dp` minimum touch height, and inline error messaging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    errorText: String? = null,
    isError: Boolean = errorText != null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    shape: Shape = RoundedCornerShape(16.dp),
    colors: TextFieldColors = rvTextFieldColors()
) {
    val resolvedSupportingText: (@Composable () -> Unit)? = when {
        supportingText != null -> supportingText
        errorText != null -> {
            { Text(text = errorText, color = Brick, style = MaterialTheme.typography.bodySmall) }
        }
        else -> null
    }
    val activeUnfocusedBorder = when {
        isError -> Brick
        !enabled -> InkHairline
        else -> InkBorderStrong
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .defaultMinSize(minHeight = 52.dp)
            .semantics { rvTextFieldUnfocusedBorderColor = activeUnfocusedBorder },
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = resolvedSupportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        shape = shape,
        colors = colors
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RvSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = stringResource(R.string.cd_search),
            tint = InkSecondary
        )
    },
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    colors: TextFieldColors = rvTextFieldColors()
) {
    RvTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(text = placeholder, color = InkSecondary, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = colors
    )
}
