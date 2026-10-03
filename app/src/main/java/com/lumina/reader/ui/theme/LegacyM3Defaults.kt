package com.lumina.reader.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * The Material3 1.3 colours that Material3 1.4 changed in its defaults, so the
 * app looks the same after the Compose 1.11 / Material3 1.4 upgrade.
 *
 * Material3 1.4 switched to the newer M3 token set:
 * - [androidx.compose.material3.OutlinedButton]: label `primary` -> `onSurfaceVariant`,
 *   border `outline` -> `outlineVariant`;
 * - unselected [androidx.compose.material3.FilterChip], [androidx.compose.material3.AssistChip]
 *   and [androidx.compose.material3.SuggestionChip]: border `outline` -> `outlineVariant`;
 * - disabled buttons: label `onSurface` -> `onSurfaceVariant` at 38 %, container at
 *   10 % instead of 12 %.
 *
 * Call sites that relied on those defaults pass these values instead. Everything
 * else (shapes, sizes, paddings, enabled filled/text button colours) is unchanged
 * between the two versions.
 */
object LegacyM3Defaults {
    private const val DISABLED_CONTAINER_ALPHA = 0.12f
    private const val DISABLED_CONTENT_ALPHA = 0.38f

    /** [ButtonDefaults.buttonColors] as in Material3 1.3 (only the disabled colours differ). */
    @Composable
    fun buttonColors(): ButtonColors {
        val colors = MaterialTheme.colorScheme
        return ButtonDefaults.buttonColors(
            disabledContainerColor = colors.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA),
            disabledContentColor = colors.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
        )
    }

    /** [ButtonDefaults.textButtonColors] as in Material3 1.3 (only the disabled colour differs). */
    @Composable
    fun textButtonColors(): ButtonColors {
        val colors = MaterialTheme.colorScheme
        return ButtonDefaults.textButtonColors(
            contentColor = colors.primary,
            disabledContentColor = colors.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
        )
    }

    /** [ButtonDefaults.outlinedButtonColors] as in Material3 1.3: a `primary` label. */
    @Composable
    fun outlinedButtonColors(): ButtonColors {
        val colors = MaterialTheme.colorScheme
        return ButtonDefaults.outlinedButtonColors(
            contentColor = colors.primary,
            disabledContentColor = colors.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
        )
    }

    /** [ButtonDefaults.outlinedButtonBorder] as in Material3 1.3: a 1 dp `outline` stroke. */
    @Composable
    fun outlinedButtonBorder(enabled: Boolean = true): BorderStroke {
        val colors = MaterialTheme.colorScheme
        return BorderStroke(
            width = 1.dp,
            color = if (enabled) colors.outline else colors.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA)
        )
    }

    /** [FilterChipDefaults.filterChipBorder] with the Material3 1.3 `outline` colour. */
    @Composable
    fun filterChipBorder(selected: Boolean, enabled: Boolean = true): BorderStroke =
        FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline
        )

    /** [AssistChipDefaults.assistChipBorder] with the Material3 1.3 `outline` colour. */
    @Composable
    fun assistChipBorder(enabled: Boolean = true): BorderStroke =
        AssistChipDefaults.assistChipBorder(
            enabled = enabled,
            borderColor = MaterialTheme.colorScheme.outline
        )

    /** [SuggestionChipDefaults.suggestionChipBorder] with the Material3 1.3 `outline` colour. */
    @Composable
    fun suggestionChipBorder(enabled: Boolean = true): BorderStroke =
        SuggestionChipDefaults.suggestionChipBorder(
            enabled = enabled,
            borderColor = MaterialTheme.colorScheme.outline
        )
}
