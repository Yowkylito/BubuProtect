package com.personal.bubuprotect.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.R
import com.personal.bubuprotect.domain.model.FieldKeyboard
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.SecretMono

/**
 * Every text input in the app.
 *
 * ### Why `KeyboardType.Password` is security code, not a keyboard preference
 *
 * Compose maps it to `IME_FLAG_NO_PERSONALIZED_LEARNING`, which tells the keyboard not to feed what
 * is typed into its prediction dictionary. Without it a master passphrase can end up as an
 * autocomplete suggestion in another app - a real leak that has nothing to do with how well the
 * vault is encrypted. It is applied to every secret field, not just the passphrase.
 *
 * Numeric secrets (a card PIN) get `NumberPassword` for the same reason: plain `Number` is a
 * personalised keyboard.
 *
 * @param modifier applied to the root, per the usual contract.
 * @param onVisibilityToggle when null the field has no reveal control. A masked field with no way to
 *   unmask it is a usability trap, so pass this for anything the user is typing themselves.
 * @param trailingSlot an extra action beside the reveal toggle - the password generator uses it.
 */
@Composable
fun VaultTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isSecret: Boolean = false,
    isVisible: Boolean = false,
    onVisibilityToggle: (() -> Unit)? = null,
    isMultiline: Boolean = false,
    keyboard: FieldKeyboard = FieldKeyboard.TEXT,
    supportingText: String? = null,
    errorText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
    trailingSlot: (@Composable () -> Unit)? = null
) {
    // A multi-line secret is never masked while editing: you cannot proof-read a note you cannot
    // see, and opening the editor already cost an authentication.
    val masked = isSecret && !isVisible && !isMultiline

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isMultiline) Modifier.heightIn(min = 120.dp) else Modifier)
                // Announces the field's purpose even when the label has floated out of view.
                .semantics {
                    contentDescription = label
                    // Marks the node as a password field. TalkBack then stops speaking each
                    // character out loud - which is what turns a masked field back into a
                    // plaintext one for anyone within earshot - and the framework withholds the
                    // value from accessibility clients that have not asked for password content.
                    if (masked) password()
                },
            label = { Text(label) },
            singleLine = !isMultiline,
            minLines = if (isMultiline) 4 else 1,
            enabled = enabled,
            isError = errorText != null,
            shape = MaterialTheme.shapes.medium,
            textStyle = if (isSecret && !isMultiline) {
                MaterialTheme.typography.bodyLarge.copy(fontFamily = SecretMono)
            } else {
                MaterialTheme.typography.bodyLarge
            },
            visualTransformation = if (masked) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboard.toKeyboardType(isSecret),
                autoCorrectEnabled = !isSecret,
                imeAction = if (isMultiline) ImeAction.Default else imeAction
            ),
            trailingIcon = when {
                trailingSlot != null || onVisibilityToggle != null -> {
                    {
                        Row {
                            trailingSlot?.invoke()
                            if (onVisibilityToggle != null && !isMultiline) {
                                IconButton(onClick = onVisibilityToggle) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(
                                            if (isVisible) {
                                                R.drawable.icon_show_password
                                            } else {
                                                R.drawable.icon_hide_password
                                            }
                                        ),
                                        contentDescription = if (isVisible) "Hide $label" else "Show $label"
                                    )
                                }
                            }
                        }
                    }
                }

                else -> null
            },
            supportingText = null
        )

        // Animated rather than always-present: an error appearing pushes the form down, and doing
        // that instantly makes the user's thumb land on the wrong control.
        AnimatedVisibility(
            visible = errorText != null || supportingText != null,
            enter = fadeIn(tween(BubuMotion.FAST)) + expandVertically(tween(BubuMotion.FAST)),
            exit = fadeOut(tween(BubuMotion.FAST)) + shrinkVertically(tween(BubuMotion.FAST))
        ) {
            Text(
                text = errorText ?: supportingText.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (errorText != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp)
            )
        }
    }
}

/** The passphrase preset: single line, always secret, monospaced. */
@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isVisible: Boolean = false,
    onVisibilityToggle: (() -> Unit)? = null,
    supportingText: String? = null,
    errorText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true
) = VaultTextField(
    value = value,
    onValueChange = onValueChange,
    label = label,
    modifier = modifier,
    isSecret = true,
    isVisible = isVisible,
    onVisibilityToggle = onVisibilityToggle,
    supportingText = supportingText,
    errorText = errorText,
    imeAction = imeAction,
    enabled = enabled
)

private fun FieldKeyboard.toKeyboardType(isSecret: Boolean): KeyboardType = when {
    // NumberPassword, not Number: the plain numeric keyboard is still a personalised one.
    this == FieldKeyboard.NUMBER && isSecret -> KeyboardType.NumberPassword
    isSecret -> KeyboardType.Password
    this == FieldKeyboard.NUMBER -> KeyboardType.Number
    this == FieldKeyboard.EMAIL -> KeyboardType.Email
    this == FieldKeyboard.URI -> KeyboardType.Uri
    else -> KeyboardType.Text
}

@Preview(name = "Masked", showBackground = true)
@Composable
private fun SecretTextFieldMaskedPreview() {
    BubuProtectTheme {
        SecretTextField(
            value = "correct-horse-battery",
            onValueChange = {},
            label = "Master passphrase",
            onVisibilityToggle = {},
            supportingText = "At least 12 characters"
        )
    }
}

@Preview(name = "Visible with error", showBackground = true)
@Composable
private fun SecretTextFieldErrorPreview() {
    BubuProtectTheme {
        SecretTextField(
            value = "short",
            onValueChange = {},
            label = "Master passphrase",
            isVisible = true,
            onVisibilityToggle = {},
            errorText = "Use at least 12 characters"
        )
    }
}

@Preview(name = "Multiline note", showBackground = true)
@Composable
private fun VaultTextFieldMultilinePreview() {
    BubuProtectTheme {
        VaultTextField(
            value = "The spare key is under the third plant pot, not the second.",
            onValueChange = {},
            label = "Your note",
            isSecret = true,
            isMultiline = true
        )
    }
}
