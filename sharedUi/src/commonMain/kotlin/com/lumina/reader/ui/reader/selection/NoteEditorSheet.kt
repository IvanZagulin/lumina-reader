package com.lumina.reader.ui.reader.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.reader.ReaderFonts
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.reader.chrome.ReaderModalSheet
import com.lumina.reader.ui.theme.HighlightPalette

/**
 * Note for a quote (§7.5): the quote with its colour bar, the note field,
 * the colour dots, «Сохранить» and — for a saved highlight — «Удалить
 * выделение».
 */
@Composable
fun NoteEditorSheet(
    quote: String,
    initialNote: String,
    initialColorHex: String,
    colors: ReaderChromeColors,
    onSave: (note: String, colorHex: String) -> Unit,
    onDeleteHighlight: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var note by rememberSaveable { mutableStateOf(initialNote) }
    var colorHex by rememberSaveable { mutableStateOf(HighlightPalette.fromHex(initialColorHex).hex) }
    ReaderModalSheet(colors = colors, onDismiss = onDismiss, scrimAlpha = 0.32f) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(HighlightPalette.fromHex(colorHex).color)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = quote.trim(),
                    fontFamily = ReaderFonts.Literata.family,
                    fontStyle = FontStyle.Italic,
                    fontSize = 15.sp,
                    color = colors.content,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("Ваша заметка…") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HighlightPalette.all.forEach { swatch ->
                    val selected = swatch.hex == colorHex
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { colorHex = swatch.hex })
                            .semantics { contentDescription = swatch.label },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .then(if (selected) Modifier.border(2.dp, colors.content, CircleShape).padding(3.dp) else Modifier)
                                .clip(CircleShape)
                                .background(swatch.color)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDeleteHighlight != null) {
                    TextButton(onClick = onDeleteHighlight) {
                        Text("Удалить выделение", color = colors.error)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { onSave(note, colorHex) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
                ) {
                    Text("Сохранить")
                }
            }
        }
    }
}
