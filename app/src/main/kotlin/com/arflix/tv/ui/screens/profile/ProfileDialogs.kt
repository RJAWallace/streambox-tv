package com.arflix.tv.ui.screens.profile

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.widget.doAfterTextChanged
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arflix.tv.data.model.ProfileColors
import com.arflix.tv.ui.components.AvatarIcon
import com.arflix.tv.ui.components.AvatarRegistry

// ============================================================
// Add Profile Dialog
// ============================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddProfileDialog(
    name: String,
    onNameChange: (String) -> Unit,
    selectedColorIndex: Int,
    selectedAvatarId: Int = 0,
    onAvatarSelected: (Int) -> Unit = {},
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ProfileDialogContent(
        title = "Add Profile",
        name = name,
        onNameChange = onNameChange,
        selectedColorIndex = selectedColorIndex,
        selectedAvatarId = selectedAvatarId,
        onAvatarSelected = onAvatarSelected,
        confirmLabel = "Create",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        onDelete = null
    )
}

// ============================================================
// Edit Profile Dialog
// ============================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EditProfileDialog(
    name: String,
    onNameChange: (String) -> Unit,
    selectedColorIndex: Int,
    selectedAvatarId: Int = 0,
    onAvatarSelected: (Int) -> Unit = {},
    onConfirm: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ProfileDialogContent(
        title = "Edit Profile",
        name = name,
        onNameChange = onNameChange,
        selectedColorIndex = selectedColorIndex,
        selectedAvatarId = selectedAvatarId,
        onAvatarSelected = onAvatarSelected,
        confirmLabel = "Save",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        onDelete = onDelete
    )
}

// ============================================================
// Shared dialog content — compact layout
// ============================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileDialogContent(
    title: String,
    name: String,
    onNameChange: (String) -> Unit,
    selectedColorIndex: Int,
    selectedAvatarId: Int,
    onAvatarSelected: (Int) -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val context = LocalContext.current
    var editTextRef by remember { mutableStateOf<EditText?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.90f)),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 48.dp)
                    .background(Color(0xFF141414), RoundedCornerShape(16.dp))
                    .padding(start = 28.dp, top = 28.dp, bottom = 28.dp, end = 20.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                // ---- Left column: preview + name + buttons ----
                Column(
                    modifier = Modifier.width(200.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Preview avatar
                    val bgColors = if (selectedAvatarId > 0) {
                        val (c1, c2) = AvatarRegistry.gradientColors(selectedAvatarId)
                        c1 to c2
                    } else {
                        val c = Color(ProfileColors.getByIndex(selectedColorIndex))
                        c to c
                    }
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.verticalGradient(listOf(bgColors.first, bgColors.second))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedAvatarId > 0) {
                            AvatarIcon(
                                avatarId = selectedAvatarId,
                                modifier = Modifier.fillMaxSize().padding(10.dp)
                            )
                        } else {
                            Text(
                                text = name.firstOrNull()?.uppercase() ?: "?",
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Name input
                    Surface(
                        onClick = {
                            editTextRef?.let { et ->
                                et.requestFocus()
                                et.postDelayed({
                                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                    @Suppress("DEPRECATION")
                                    imm?.showSoftInput(et, InputMethodManager.SHOW_FORCED)
                                }, 100)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF222222),
                            focusedContainerColor = Color(0xFF222222)
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = androidx.tv.material3.Border(
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(8.dp)
                            ),
                            focusedBorder = androidx.tv.material3.Border(
                                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                                shape = RoundedCornerShape(8.dp)
                            )
                        )
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                EditText(ctx).apply {
                                    editTextRef = this
                                    setText(name)
                                    setTextColor(android.graphics.Color.WHITE)
                                    setHintTextColor(android.graphics.Color.GRAY)
                                    hint = "Profile name"
                                    textSize = 16f
                                    background = null
                                    setPadding(36, 32, 36, 32)
                                    isSingleLine = true
                                    inputType = InputType.TYPE_CLASS_TEXT
                                    imeOptions = EditorInfo.IME_ACTION_DONE
                                    isFocusable = true
                                    isFocusableInTouchMode = true
                                    doAfterTextChanged { editable ->
                                        onNameChange(editable?.toString() ?: "")
                                    }
                                    setOnEditorActionListener { _, actionId, _ ->
                                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                                            val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                            imm?.hideSoftInputFromWindow(windowToken, 0)
                                            clearFocus()
                                            true
                                        } else false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            update = { et ->
                                if (et.text.toString() != name) {
                                    et.setText(name)
                                    et.setSelection(name.length)
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Buttons
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        DialogButton(
                            text = confirmLabel,
                            enabled = name.isNotBlank(),
                            onClick = onConfirm,
                            modifier = Modifier.fillMaxWidth()
                        )
                        DialogButton(
                            text = "Cancel",
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (onDelete != null) {
                            DialogButton(
                                text = "Delete",
                                isDestructive = true,
                                onClick = onDelete,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // ---- Right column: avatar picker (4 themed rows) ----
                Column(
                    modifier = Modifier.width(460.dp)
                ) {
                    // Avatar picker - 4 horizontal scrolling rows by category
                    AvatarRegistry.categories.forEachIndexed { rowIdx, (label, ids) ->
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(start = 4.dp, end = 4.dp)
                        ) {
                            // "None" option only in first row
                            if (rowIdx == 0) {
                                item {
                                    AvatarGridItem(
                                        avatarId = 0,
                                        isSelected = selectedAvatarId == 0,
                                        onClick = { onAvatarSelected(0) },
                                        isNone = true
                                    )
                                }
                            }
                            items(ids.size) { col ->
                                val id = ids[col]
                                AvatarGridItem(
                                    avatarId = id,
                                    isSelected = selectedAvatarId == id,
                                    onClick = { onAvatarSelected(id) }
                                )
                            }
                        }

                        if (rowIdx < AvatarRegistry.categories.size - 1) Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

// ============================================================
// Avatar grid item — individual avatar cell
// ============================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AvatarGridItem(
    avatarId: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    isNone: Boolean = false
) {
    val (c1, c2) = if (isNone) {
        Color(0xFF2A2A2A) to Color(0xFF333333)
    } else {
        AvatarRegistry.gradientColors(avatarId)
    }

    // Fixed-size surface — no size animation to avoid LazyRow viewport clipping
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(58.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (isSelected) {
                androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                    shape = RoundedCornerShape(10.dp)
                )
            } else {
                androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(10.dp)
                )
            },
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.5.dp, Color.White),
                shape = RoundedCornerShape(10.dp)
            )
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(c1, c2))),
            contentAlignment = Alignment.Center
        ) {
            if (isNone) {
                Text(
                    text = "Aa",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.7f)
                )
            } else {
                AvatarIcon(
                    avatarId = avatarId,
                    modifier = Modifier.fillMaxSize().padding(5.dp)
                )
            }
            // Selected checkmark
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

// ============================================================
// Dialog button
// ============================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DialogButton(
    text: String,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableIntStateOf(0) }

    val containerColor = when {
        isDestructive -> Color(0xFFDC2626)
        else -> Color.Transparent
    }
    val focusedContainerColor = when {
        isDestructive -> Color(0xFFEF4444)
        else -> Color.White.copy(alpha = 0.1f)
    }

    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .onFocusChanged { isFocused = if (it.isFocused) 1 else 0 },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = focusedContainerColor
        ),
        border = if (!isDestructive) {
            ClickableSurfaceDefaults.border(
                border = androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(6.dp)
                ),
                focusedBorder = androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                    shape = RoundedCornerShape(6.dp)
                )
            )
        } else {
            ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                    shape = RoundedCornerShape(6.dp)
                )
            )
        }
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}
