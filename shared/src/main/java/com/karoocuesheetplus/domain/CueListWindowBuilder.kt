package com.karoocuesheetplus.domain

/**
 * Builds the 5-row Full-Page viewport combining:
 * - Automatic live focus on the next upcoming waypoint (`isNextActive == true`),
 * - Manual `▲ / ▼` scrolling up into passed `✓` POIs or down into distant POIs,
 * - Always pinning `Meta / Fin Ruta` in the 5th slot when there are further points below.
 */
object CueListWindowBuilder {

    const val MAX_VISIBLE_ROWS = 5

    fun buildWindow(
        allPoints: List<CuePoint>,
        manualStartIndex: Int?,
    ): CueListWindow {
        if (allPoints.isEmpty()) {
            return CueListWindow(
                rows = emptyList(),
                activeOrdinal = 0,
                totalCount = 0,
                canScrollUp = false,
                canScrollDown = false,
                isManualScroll = false,
            )
        }

        val defaultStartIdx = allPoints.indexOfFirst { !it.isPassed }
            .let { if (it >= 0) it else allPoints.lastIndex }

        val activeOrdinal = (defaultStartIdx + 1).coerceIn(1, allPoints.size)

        if (allPoints.size <= MAX_VISIBLE_ROWS) {
            return CueListWindow(
                rows = allPoints,
                activeOrdinal = activeOrdinal,
                totalCount = allPoints.size,
                canScrollUp = false,
                canScrollDown = false,
                isManualScroll = false,
            )
        }

        // Maximum start index so we always fill 5 rows cleanly ending at Meta
        val maxStartIdx = (allPoints.size - MAX_VISIBLE_ROWS).coerceAtLeast(0)
        val clampedDefaultStart = defaultStartIdx.coerceIn(0, maxStartIdx)
        val effectiveStart = (manualStartIndex ?: clampedDefaultStart).coerceIn(0, maxStartIdx)
        val isManual = manualStartIndex != null && effectiveStart != clampedDefaultStart

        val rows = if (effectiveStart < maxStartIdx) {
            // Take 4 rows from effectiveStart + pin Finish in row 5
            val topFour = allPoints.subList(effectiveStart, effectiveStart + (MAX_VISIBLE_ROWS - 1))
            val pinnedFinish = allPoints.last().copy(isPinnedFinish = true)
            topFour + pinnedFinish
        } else {
            // At the bottom of the list: the natural 5th item IS already Finish
            allPoints.subList(maxStartIdx, allPoints.size)
        }

        return CueListWindow(
            rows = rows,
            activeOrdinal = activeOrdinal,
            totalCount = allPoints.size,
            canScrollUp = effectiveStart > 0,
            canScrollDown = effectiveStart < maxStartIdx,
            isManualScroll = isManual,
        )
    }

    fun computeNextScrollIndex(
        allPoints: List<CuePoint>,
        currentManualStartIndex: Int?,
        delta: Int,
    ): Int? {
        if (allPoints.size <= MAX_VISIBLE_ROWS) return null
        val defaultStartIdx = allPoints.indexOfFirst { !it.isPassed }
            .let { if (it >= 0) it else allPoints.lastIndex }
        val maxStartIdx = (allPoints.size - MAX_VISIBLE_ROWS).coerceAtLeast(0)
        val base = (currentManualStartIndex ?: defaultStartIdx.coerceIn(0, maxStartIdx))
        return (base + delta).coerceIn(0, maxStartIdx)
    }
}
