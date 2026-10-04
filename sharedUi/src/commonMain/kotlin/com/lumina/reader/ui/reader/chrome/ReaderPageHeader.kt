package com.lumina.reader.ui.reader.chrome

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** Running head of a page: the chapter title, centred, one line (§4.2). */
@Composable
fun ReaderPageHeader(
    title: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        fontSize = 11.sp,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth()
    )
}
