package com.wallshare.app.wallpaper.lock.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.wallshare.app.ui.theme.*

/**
 * Dialog for creating and confirming a new 4-digit PIN when activating Forced Wallpaper Lock.
 */
@Composable
fun PinCreateDialog(
    onDismiss: () -> Unit,
    onPinCreated: (String) -> Unit
) {
    var step by remember { mutableStateOf(1) } // 1 = Create, 2 = Confirm
    var firstPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(AccentPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = AccentPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (step == 1) "Create Security PIN" else "Confirm Security PIN",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (step == 1)
                        "Enter a 4-digit PIN to lock your wallpaper. You'll need this PIN to unlock it later."
                    else
                        "Re-enter the 4-digit PIN to confirm.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 4-Digit Input
                val currentPin = if (step == 1) firstPin else confirmPin
                PinInputBox(
                    pin = currentPin,
                    isError = errorMessage != null,
                    onPinChange = { updated ->
                        if (updated.length <= 4 && updated.all { it.isDigit() }) {
                            errorMessage = null
                            if (step == 1) {
                                firstPin = updated
                                if (updated.length == 4) {
                                    // Move to step 2 after brief moment
                                    step = 2
                                    confirmPin = ""
                                }
                            } else {
                                confirmPin = updated
                                if (updated.length == 4) {
                                    if (updated == firstPin) {
                                        onPinCreated(firstPin)
                                    } else {
                                        errorMessage = "PINs do not match. Please try again."
                                        confirmPin = ""
                                        step = 1
                                        firstPin = ""
                                    }
                                }
                            }
                        }
                    }
                )

                AnimatedVisibility(visible = errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Text("Cancel")
                    }

                    if (step == 2) {
                        Button(
                            onClick = {
                                step = 1
                                firstPin = ""
                                confirmPin = ""
                                errorMessage = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Text("Back", color = TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog for verifying the 4-digit PIN when the user attempts to toggle OFF the wallpaper lock.
 */
@Composable
fun PinVerifyDialog(
    onDismiss: () -> Unit,
    onVerifyPin: (String) -> Boolean,
    onSuccess: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(AccentSecondary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = AccentSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Unlock Wallpaper",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enter your 4-digit PIN to turn off the forced wallpaper lock.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 4-Digit Input
                PinInputBox(
                    pin = enteredPin,
                    isError = errorMessage != null,
                    onPinChange = { updated ->
                        if (updated.length <= 4 && updated.all { it.isDigit() }) {
                            enteredPin = updated
                            errorMessage = null

                            if (updated.length == 4) {
                                val isCorrect = onVerifyPin(updated)
                                if (isCorrect) {
                                    onSuccess()
                                } else {
                                    errorMessage = "Incorrect PIN. Try again."
                                    enteredPin = ""
                                }
                            }
                        }
                    }
                )

                AnimatedVisibility(visible = errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            if (enteredPin.length == 4) {
                                if (onVerifyPin(enteredPin)) {
                                    onSuccess()
                                } else {
                                    errorMessage = "Incorrect PIN. Try again."
                                    enteredPin = ""
                                }
                            }
                        },
                        enabled = enteredPin.length == 4,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = DarkBackground
                        )
                    ) {
                        Text("Unlock", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * 4-digit PIN visual component with auto-focused numeric keyboard.
 */
@Composable
private fun PinInputBox(
    pin: String,
    isError: Boolean,
    onPinChange: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) {
            focusRequester.requestFocus()
        }
    ) {
        // Invisible input field that grabs keyboard focus
        BasicTextField(
            value = pin,
            onValueChange = onPinChange,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
        )

        // 4 Sleek Slots
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until 4) {
                val isFilled = i < pin.length
                val isCurrent = i == pin.length

                val borderColor = when {
                    isError -> ErrorRed
                    isCurrent -> AccentPrimary
                    isFilled -> AccentPrimary.copy(alpha = 0.7f)
                    else -> DarkSurfaceVariant
                }

                val backgroundColor = when {
                    isError -> ErrorRed.copy(alpha = 0.1f)
                    isFilled -> AccentPrimary.copy(alpha = 0.15f)
                    else -> DarkSurfaceVariant.copy(alpha = 0.4f)
                }

                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(backgroundColor)
                        .border(
                            width = if (isCurrent || isError) 2.dp else 1.dp,
                            color = borderColor,
                            shape = RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isFilled) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(if (isError) ErrorRed else AccentPrimary)
                        )
                    }
                }
            }
        }
    }
}
