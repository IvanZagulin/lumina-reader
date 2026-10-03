package com.lumina.reader.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsPaletteTest {

    @Test
    fun heatRampHasFiveStepsAndAnEmptyCell() {
        val palette = StatsPalette.Light
        assertEquals(5, palette.heat.size)
        assertEquals(palette.heat[0], palette.heatColor(0f))
        assertEquals(palette.heat[0], palette.heatColor(-1f))
        assertEquals(palette.heat[1], palette.heatColor(0.01f))
        assertEquals(palette.heat[1], palette.heatColor(0.24f))
        assertEquals(palette.heat[2], palette.heatColor(0.25f))
        assertEquals(palette.heat[3], palette.heatColor(0.5f))
        assertEquals(palette.heat[4], palette.heatColor(0.8f))
        assertEquals(palette.heat[4], palette.heatColor(1f))
    }

    @Test
    fun seriesColoursFollowTheChartPaletteOrder() {
        val dark = StatsPalette.Dark
        assertEquals(listOf(dark.terracotta, dark.emerald, dark.brass, dark.indigo, dark.plum, dark.ochre), dark.series)
        assertEquals(6, StatsPalette.Light.series.distinct().size)
    }
}
