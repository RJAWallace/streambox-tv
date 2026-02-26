package com.arflix.tv.ui.screens.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.repository.AuthState
import com.arflix.tv.ui.theme.*

/**
 * Simple code-based login screen for TV
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = hiltViewModel(),
    onLoginSuccess: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var code by remember { mutableStateOf("") }
    val codeFocusRequester = remember { FocusRequester() }
    val buttonFocusRequester = remember { FocusRequester() }
    var isCodeFocused by remember { mutableStateOf(false) }

    // Navigate on successful auth
    LaunchedEffect(uiState.authState) {
        if (uiState.authState is AuthState.Authenticated) {
            onLoginSuccess()
        }
    }

    // Auto-focus code input
    LaunchedEffect(Unit) {
        codeFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.width(400.dp)
        ) {
            // App title
            Text(
                text = "StreamBox",
                style = TextStyle(
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = Pink
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Instructions
            Text(
                text = "Enter your access code",
                style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextSecondary
                )
            )

            // Code input field
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .border(
                        width = 2.dp,
                        color = if (isCodeFocused) Pink else Color.White.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .background(
                        Color.White.copy(alpha = 0.08f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicTextField(
                    value = code,
                    onValueChange = { newValue ->
                        // Only accept letters, max 5 characters
                        val filtered = newValue.uppercase().filter { it.isLetter() }.take(5)
                        code = filtered
                    },
                    textStyle = TextStyle(
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        letterSpacing = 12.sp
                    ),
                    cursorBrush = SolidColor(Pink),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.Characters
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (code.length == 5) {
                                viewModel.loginWithCode(code)
                            }
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(codeFocusRequester)
                        .onFocusChanged { isCodeFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionDown, Key.Tab -> {
                                        buttonFocusRequester.requestFocus()
                                        true
                                    }
                                    Key.Enter -> {
                                        if (code.length == 5) {
                                            viewModel.loginWithCode(code)
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                )

                // Placeholder
                if (code.isEmpty()) {
                    Text(
                        text = "_ _ _ _ _",
                        style = TextStyle(
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center,
                            letterSpacing = 12.sp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Error message
            uiState.error?.let { error ->
                Text(
                    text = error,
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = Color(0xFFFF6B6B)
                    )
                )
            }

            // Submit button
            Button(
                onClick = { viewModel.loginWithCode(code) },
                enabled = code.length == 5 && !uiState.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .focusRequester(buttonFocusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            codeFocusRequester.requestFocus()
                            true
                        } else false
                    },
                colors = ButtonDefaults.colors(
                    containerColor = Pink,
                    contentColor = Color.White,
                    focusedContainerColor = Pink.copy(alpha = 0.8f)
                ),
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp))
            ) {
                Text(
                    text = if (uiState.isLoading) "Signing in..." else "Sign In",
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}
