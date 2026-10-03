package com.lumina.reader.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.lumina.reader.R

// UI typefaces (spec §1.4). Both files are subset variable fonts (wght axis);
// Font(resId, weight) sets the `wght` variation for each declared weight, so
// one file serves every weight. Faces are resolved lazily on first use.

/** Onest: the clean Cyrillic grotesque used for all UI text. */
val OnestFamily: FontFamily = FontFamily(
    Font(R.font.onest_variable, FontWeight.Normal),
    Font(R.font.onest_variable, FontWeight.Medium),
    Font(R.font.onest_variable, FontWeight.SemiBold),
    Font(R.font.onest_variable, FontWeight.Bold),
)

/** Lora: display headings only (screen titles, shelf names, generated covers). */
val LoraFamily: FontFamily = FontFamily(
    Font(R.font.lora_variable, FontWeight.Normal),
    Font(R.font.lora_variable, FontWeight.Medium),
    Font(R.font.lora_variable, FontWeight.SemiBold),
    Font(R.font.lora_variable, FontWeight.Bold),
)
