package com.karoocuesheetplus.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.karoocuesheetplus.domain.CueCategory
import com.karoocuesheetplus.domain.CueFormatter
import com.karoocuesheetplus.domain.CueListWindowBuilder
import com.karoocuesheetplus.domain.CuePoint
import com.karoocuesheetplus.domain.CueSheetSnapshot
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object CueSheetRenderer {

    private data class Palette(
        val bg: Int,
        val cardBg: Int,
        val passedRowBg: Int,
        val activeRowBg: Int,
        val activeBorder: Int,
        val headerBg: Int,
        val divider: Int,
        val pinnedDivider: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val accentTime: Int,
        val passedGreen: Int,
        val buttonBg: Int,
        val buttonBorder: Int,
        val isNight: Boolean,
    )

    private val NIGHT_PALETTE = Palette(
        bg = 0xFF000000.toInt(),
        cardBg = 0xFF1A1D21.toInt(),
        passedRowBg = 0xFF111316.toInt(),
        activeRowBg = 0xFF0B1926.toInt(),
        activeBorder = 0xFF38BDF8.toInt(),
        headerBg = 0xFF0D0F12.toInt(),
        divider = 0xFF262A30.toInt(),
        pinnedDivider = 0xFF6B7280.toInt(),
        textPrimary = 0xFFFFFFFF.toInt(),
        textSecondary = 0xFFD1D5DB.toInt(),
        textMuted = 0xFF717882.toInt(),
        accentTime = 0xFFF5C518.toInt(),
        passedGreen = 0xFF22C55E.toInt(),
        buttonBg = 0xFF1E2228.toInt(),
        buttonBorder = 0xFF4B5563.toInt(),
        isNight = true,
    )

    private val DAY_PALETTE = Palette(
        bg = 0xFFFFFFFF.toInt(),
        cardBg = 0xFFF3F4F6.toInt(),
        passedRowBg = 0xFFEAECEF.toInt(),
        activeRowBg = 0xFFE0F2FE.toInt(),
        activeBorder = 0xFF0284C7.toInt(),
        headerBg = 0xFFF8FAFC.toInt(),
        divider = 0xFFD1D5DB.toInt(),
        pinnedDivider = 0xFF374151.toInt(),
        textPrimary = 0xFF090A0C.toInt(),
        textSecondary = 0xFF374151.toInt(),
        textMuted = 0xFF6B7280.toInt(),
        accentTime = 0xFFB45309.toInt(),
        passedGreen = 0xFF16A34A.toInt(),
        buttonBg = 0xFFFFFFFF.toInt(),
        buttonBorder = 0xFF1F2937.toInt(),
        isNight = false,
    )

    private val boldSans: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val condensedBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val mediumSans: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun renderFullPage(
        snapshot: CueSheetSnapshot,
        widthPx: Int,
        heightPx: Int,
        isNightMode: Boolean,
    ): Bitmap {
        val w = widthPx.coerceIn(200, 1080)
        val h = heightPx.coerceIn(240, 1920)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = if (isNightMode) NIGHT_PALETTE else DAY_PALETTE

        canvas.drawColor(p.bg)

        if (!snapshot.hasActiveRoute || snapshot.allPoints.isEmpty()) {
            drawEmptyFullState(canvas, w.toFloat(), h.toFloat(), snapshot, p)
            return bmp
        }

        val detail = snapshot.selectedDetailPoint
        if (detail != null) {
            drawDetailView(canvas, w.toFloat(), h.toFloat(), detail, snapshot, p)
        } else {
            drawListView(canvas, w.toFloat(), h.toFloat(), snapshot, p)
        }
        return bmp
    }

    fun renderHalfPage(
        snapshot: CueSheetSnapshot,
        widthPx: Int,
        heightPx: Int,
        isNightMode: Boolean,
    ): Bitmap {
        val w = widthPx.coerceIn(200, 1080)
        val h = heightPx.coerceIn(160, 1920)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = if (isNightMode) NIGHT_PALETTE else DAY_PALETTE

        canvas.drawColor(p.bg)

        if (!snapshot.hasActiveRoute || snapshot.allPoints.isEmpty()) {
            drawEmptyFullState(canvas, w.toFloat(), h.toFloat(), snapshot, p)
            return bmp
        }

        drawHalfPageListView(canvas, w.toFloat(), h.toFloat(), snapshot, p)
        return bmp
    }

    fun renderSingleField(
        snapshot: CueSheetSnapshot,
        widthPx: Int,
        heightPx: Int,
        isWideSlot: Boolean,
        isNightMode: Boolean,
    ): Bitmap {
        val w = widthPx.coerceIn(120, 1080)
        val h = heightPx.coerceIn(70, 600)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = if (isNightMode) NIGHT_PALETTE else DAY_PALETTE

        canvas.drawColor(p.bg)

        val next = snapshot.nextPoint
        if (!snapshot.hasActiveRoute || next == null) {
            drawEmptySingleState(canvas, w.toFloat(), h.toFloat(), isWideSlot, snapshot, p)
            return bmp
        }

        if (isWideSlot) {
            drawWideSingleField(canvas, w.toFloat(), h.toFloat(), next, snapshot, p)
        } else {
            drawCompactSingleField(canvas, w.toFloat(), h.toFloat(), next, snapshot, p)
        }
        return bmp
    }

    // -------------------------------------------------------------------------
    // FULL-PAGE LIST VIEW (10% header + 5 x 18% rows matching XML touch targets)
    // -------------------------------------------------------------------------

    private fun drawListView(
        canvas: Canvas,
        w: Float,
        h: Float,
        snapshot: CueSheetSnapshot,
        p: Palette,
    ) {
        val headerH = h * 0.10f
        val rowH = h * 0.18f
        val padX = w * 0.035f

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        // Header background
        fillPaint.color = p.headerBg
        canvas.drawRect(0f, 0f, w, headerH, fillPaint)

        // Header title: "CUE SHEET+ (2/7)"
        val win = snapshot.window
        val demoTag = if (snapshot.isDemoMode) " • ${snapshot.strings.demoBadge}" else ""
        val headerTitle = "CUE SHEET+ (${win.activeOrdinal}/${win.totalCount})$demoTag"
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = condensedBold
            color = p.textPrimary
            textSize = headerH * 0.42f
        }
        val titleMaxW = w * 0.54f
        val clippedHeader = TextUtils.ellipsize(headerTitle, headerPaint, titleMaxW, TextUtils.TruncateAt.END).toString()
        val headerBaseline = headerH * 0.5f - (headerPaint.descent() + headerPaint.ascent()) * 0.5f
        canvas.drawText(clippedHeader, padX, headerBaseline, headerPaint)

        // Up [ ▲ ] button in 56%..78% width, Down [ ▼ ] button in 78%..100% width
        drawHeaderScrollButton(
            canvas = canvas,
            left = w * 0.57f,
            top = headerH * 0.15f,
            right = w * 0.765f,
            bottom = headerH * 0.85f,
            isUp = true,
            enabled = win.canScrollUp,
            p = p,
        )
        drawHeaderScrollButton(
            canvas = canvas,
            left = w * 0.785f,
            top = headerH * 0.15f,
            right = w * 0.975f,
            bottom = headerH * 0.85f,
            isUp = false,
            enabled = win.canScrollDown,
            p = p,
        )

        // Bottom border of header
        strokePaint.color = p.divider
        strokePaint.strokeWidth = max(1.5f, h * 0.002f)
        canvas.drawLine(0f, headerH, w, headerH, strokePaint)

        // Draw up to 5 rows
        win.rows.take(5).forEachIndexed { idx, point ->
            val top = headerH + idx * rowH
            val bottom = top + rowH
            drawCueRow(canvas, w, top, bottom, point, snapshot, p)
        }
    }

    private fun drawHalfPageListView(
        canvas: Canvas,
        w: Float,
        h: Float,
        snapshot: CueSheetSnapshot,
        p: Palette,
    ) {
        val rowCount = if (h >= 310f) 3 else 2
        val headerH = h * (if (rowCount == 3) 0.16f else 0.18f)
        val rowH = (h - headerH) / rowCount.toFloat()
        val padX = w * 0.035f

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        fillPaint.color = p.headerBg
        canvas.drawRect(0f, 0f, w, headerH, fillPaint)

        val win = CueListWindowBuilder.buildWindow(
            allPoints = snapshot.allPoints,
            manualStartIndex = null,
            maxVisibleRows = rowCount,
        )
        val demoTag = if (snapshot.isDemoMode) " • ${snapshot.strings.demoBadge}" else ""
        val headerTitle = "CUE SHEET+ (${win.activeOrdinal}/${win.totalCount})$demoTag"
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = condensedBold
            color = p.textPrimary
            textSize = (headerH * 0.48f).coerceAtLeast(14f)
        }
        val titleMaxW = (w - padX * 2f).coerceAtLeast(40f)
        val clippedHeader = TextUtils.ellipsize(headerTitle, headerPaint, titleMaxW, TextUtils.TruncateAt.END).toString()
        val headerBaseline = headerH * 0.5f - (headerPaint.descent() + headerPaint.ascent()) * 0.5f
        canvas.drawText(clippedHeader, padX, headerBaseline, headerPaint)

        strokePaint.color = p.divider
        strokePaint.strokeWidth = max(1.5f, h * 0.003f)
        canvas.drawLine(0f, headerH, w, headerH, strokePaint)

        win.rows.take(rowCount).forEachIndexed { idx, point ->
            val top = headerH + idx * rowH
            val bottom = top + rowH
            drawCueRow(canvas, w, top, bottom, point, snapshot, p)
        }
    }

    private fun drawHeaderScrollButton(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        isUp: Boolean,
        enabled: Boolean,
        p: Palette,
    ) {
        val rect = RectF(left, top, right, bottom)
        val radius = rect.height() * 0.28f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = p.buttonBg
            alpha = if (enabled) 255 else 110
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, rect.height() * 0.05f)
            color = if (enabled) p.buttonBorder else p.divider
        }
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        // Crisp triangle arrow
        val cx = rect.centerX()
        val cy = rect.centerY()
        val halfW = rect.height() * 0.22f
        val halfH = rect.height() * 0.18f
        val tri = Path().apply {
            if (isUp) {
                moveTo(cx, cy - halfH)
                lineTo(cx - halfW, cy + halfH)
                lineTo(cx + halfW, cy + halfH)
            } else {
                moveTo(cx, cy + halfH)
                lineTo(cx - halfW, cy - halfH)
                lineTo(cx + halfW, cy - halfH)
            }
            close()
        }
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (enabled) p.textPrimary else p.textMuted
        }
        canvas.drawPath(tri, arrowPaint)
    }

    private fun drawCueRow(
        canvas: Canvas,
        w: Float,
        top: Float,
        bottom: Float,
        point: CuePoint,
        snapshot: CueSheetSnapshot,
        p: Palette,
    ) {
        val rowH = bottom - top
        val padX = w * 0.032f
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        // Row background / highlight
        when {
            point.isNextActive -> {
                val inset = rowH * 0.045f
                val cardRect = RectF(w * 0.015f, top + inset, w * 0.985f, bottom - inset)
                val r = rowH * 0.13f
                fillPaint.color = p.activeRowBg
                canvas.drawRoundRect(cardRect, r, r, fillPaint)
                strokePaint.color = p.activeBorder
                strokePaint.strokeWidth = max(3f, rowH * 0.028f)
                canvas.drawRoundRect(cardRect, r, r, strokePaint)
            }
            point.isPassed -> {
                fillPaint.color = p.passedRowBg
                canvas.drawRect(0f, top, w, bottom, fillPaint)
            }
        }

        // Pinned Finish double divider at top of row 5 when jumping ahead
        if (point.isPinnedFinish) {
            strokePaint.color = p.pinnedDivider
            strokePaint.strokeWidth = max(2.5f, rowH * 0.022f)
            canvas.drawLine(padX, top + 1.5f, w - padX, top + 1.5f, strokePaint)
            canvas.drawLine(padX, top + 6.5f, w - padX, top + 6.5f, strokePaint)
        }

        // Left category / passed checkmark circular badge
        val iconRadius = min(rowH * 0.28f, w * 0.078f)
        val iconCx = padX + iconRadius + w * 0.01f
        val iconCy = top + rowH * 0.50f
        drawWaypointBadge(canvas, iconCx, iconCy, iconRadius, point.category, point.isPassed, p)

        // Right-side metrics (Distance on top, ETA + ETE on bottom)
        val rightEdge = w - padX - w * 0.01f
        val distFormatted = CueFormatter.formatDistance(point.deltaDistanceM, snapshot.isMetricDistance)

        val unitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = rowH * 0.22f
            color = if (point.isPassed) p.textMuted else p.textSecondary
            textAlign = Paint.Align.RIGHT
        }
        val distNumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = rowH * 0.37f
            color = if (point.isPassed) p.textMuted else p.textPrimary
            textAlign = Paint.Align.RIGHT
        }
        val unitText = " ${distFormatted.unit}"
        val unitW = unitPaint.measureText(unitText)
        val distBaseline = top + rowH * 0.45f
        canvas.drawText(unitText, rightEdge, distBaseline, unitPaint)
        canvas.drawText(distFormatted.value, rightEdge - unitW, distBaseline, distNumPaint)

        // Right secondary text (ETA + ETE or "✓ Done" / "✓ Completado")
        val s = snapshot.strings
        val subRightText = if (point.isPassed) {
            s.completedRowRight
        } else {
            CueFormatter.formatCombinedEtaAndEte(point.etaEpochMillis, point.eteSeconds, snapshot.is24HourFormat)
        }
        val subRightPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (point.isNextActive || point.isPassed) boldSans else mediumSans
            textSize = rowH * 0.215f
            color = when {
                point.isPassed -> p.passedGreen
                point.isNextActive -> p.accentTime
                else -> p.textSecondary
            }
            textAlign = Paint.Align.RIGHT
        }
        val subBaseline = top + rowH * 0.78f
        canvas.drawText(subRightText, rightEdge, subBaseline, subRightPaint)

        // Measure occupied right width so left name never overlaps right numbers
        val rightBlockWidth = max(
            distNumPaint.measureText(distFormatted.value) + unitW,
            subRightPaint.measureText(subRightText)
        ) + w * 0.04f

        // Left text block (Waypoint Name + Ascent/Status subtext)
        val textLeft = iconCx + iconRadius + w * 0.032f
        val availableLeftW = (rightEdge - rightBlockWidth - textLeft).coerceAtLeast(w * 0.20f)

        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = rowH * 0.29f
            color = if (point.isPassed) p.textMuted else p.textPrimary
        }
        // Shrink slightly if name is long so more characters fit cleanly
        if (namePaint.measureText(point.name) > availableLeftW) {
            namePaint.textSize = rowH * 0.25f
        }
        val cleanName = TextUtils.ellipsize(point.name, namePaint, availableLeftW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(cleanName, textLeft, top + rowH * 0.45f, namePaint)

        val leftSubText = if (point.isPassed) {
            s.passedRowSub
        } else {
            CueFormatter.formatElevationDelta(point.ascentFromRiderM, point.descentFromRiderM, snapshot.isMetricElevation, s)
        }
        val leftSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mediumSans
            textSize = rowH * 0.21f
            color = if (point.isPassed) p.textMuted else p.textSecondary
        }
        val clippedSub = TextUtils.ellipsize(leftSubText, leftSubPaint, availableLeftW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(clippedSub, textLeft, top + rowH * 0.78f, leftSubPaint)

        // Bottom row divider (skip if active row already drew border)
        if (!point.isNextActive) {
            strokePaint.color = p.divider
            strokePaint.strokeWidth = max(1.5f, rowH * 0.012f)
            canvas.drawLine(padX, bottom, w - padX, bottom, strokePaint)
        }
    }

    // -------------------------------------------------------------------------
    // FULL-PAGE TOUCH DETAIL VIEW (matches mockup_detail_view)
    // -------------------------------------------------------------------------

    private fun drawDetailView(
        canvas: Canvas,
        w: Float,
        h: Float,
        point: CuePoint,
        snapshot: CueSheetSnapshot,
        p: Palette,
    ) {
        val padX = w * 0.050f
        val gap = w * 0.025f
        val topY = h * 0.028f
        val btnH = h * 0.076f
        val lockSize = btnH

        // 1. Two Header Buttons: Main Back Button (Left ~82%) and Circular Lock Button (Right ~18%)
        val lockRect = RectF(w - padX - lockSize, topY, w - padX, topY + btnH)
        val backRect = RectF(padX, topY, lockRect.left - gap, topY + btnH)
        val backRadius = backRect.height() * 0.5f

        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = p.cardBg
        }
        val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = p.buttonBorder
        }
        canvas.drawRoundRect(backRect, backRadius, backRadius, pillPaint)
        canvas.drawRoundRect(backRect, backRadius, backRadius, pillBorder)

        val s = snapshot.strings
        val backLabel = if (snapshot.isDetailLocked) s.backToListPlain else s.formatBackToList(snapshot.detailRemainingSeconds)
        val backTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = backRect.height() * 0.44f
            color = p.textPrimary
            textAlign = Paint.Align.CENTER
        }
        val backBase = backRect.centerY() - (backTextPaint.descent() + backTextPaint.ascent()) * 0.5f
        canvas.drawText(backLabel, backRect.centerX(), backBase, backTextPaint)

        // Draw Right Circular Lock Button (Padlock on/off)
        val lockRadius = lockSize * 0.5f
        val lockCx = lockRect.centerX()
        val lockCy = lockRect.centerY()
        val isLocked = snapshot.isDetailLocked

        val lockBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (isLocked) Color.argb(65, 245, 197, 24) else p.cardBg
        }
        val lockBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (isLocked) 2.5f else 2f
            color = if (isLocked) p.accentTime else p.buttonBorder
        }
        canvas.drawCircle(lockCx, lockCy, lockRadius, lockBgPaint)
        canvas.drawCircle(lockCx, lockCy, lockRadius, lockBorderPaint)

        drawPadlockIcon(
            canvas = canvas,
            cx = lockCx,
            cy = lockCy,
            size = lockSize * 0.50f,
            isLocked = isLocked,
            color = if (isLocked) p.accentTime else p.textSecondary,
        )

        // 2. Category Icon + Title + Subtitle (Larger typography)
        val badgeR = min(w * 0.088f, h * 0.050f)
        val badgeCy = h * 0.165f
        drawWaypointBadge(canvas, w * 0.5f, badgeCy, badgeR, point.category, point.isPassed, p)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = h * 0.054f
            color = p.textPrimary
            textAlign = Paint.Align.CENTER
        }
        val maxTitleW = w - padX * 2f
        if (titlePaint.measureText(point.name) > maxTitleW) {
            titlePaint.textSize = h * 0.044f
        }
        val titleText = TextUtils.ellipsize(point.name, titlePaint, maxTitleW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(titleText, w * 0.5f, h * 0.268f, titlePaint)

        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mediumSans
            textSize = h * 0.030f
            color = p.textMuted
            textAlign = Paint.Align.CENTER
        }
        val subText = TextUtils.ellipsize(point.subtitle, subPaint, maxTitleW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(subText, w * 0.5f, h * 0.310f, subPaint)

        // 3. Massive Hero Distance (Larger typography)
        val dist = CueFormatter.formatDistance(point.deltaDistanceM, snapshot.isMetricDistance)
        val heroNumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = h * 0.116f
            color = p.textPrimary
        }
        val heroUnitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = h * 0.058f
            color = p.textSecondary
        }
        val numW = heroNumPaint.measureText(dist.value)
        val unitStr = " ${dist.unit}"
        val unitW = heroUnitPaint.measureText(unitStr)
        val heroStartX = (w - (numW + unitW)) * 0.5f
        val heroBaseline = h * 0.432f
        canvas.drawText(dist.value, heroStartX, heroBaseline, heroNumPaint)
        canvas.drawText(unitStr, heroStartX + numW, heroBaseline, heroUnitPaint)

        val distCaptionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = h * 0.025f
            color = p.textSecondary
            letterSpacing = 0.08f
            textAlign = Paint.Align.CENTER
        }
        val caption = if (point.isPassed) s.passedAgoCaption else s.distanceRemainingCaption
        canvas.drawText(caption, w * 0.5f, h * 0.468f, distCaptionPaint)

        // 4. 2x2 Metric Cards Grid (Larger typography)
        val gridTop = h * 0.500f
        val gridBottom = h * 0.770f
        val cardGap = w * 0.03f
        val cardW = (w - padX * 2f - cardGap) * 0.5f
        val cardH = (gridBottom - gridTop - cardGap) * 0.5f

        val etaText = if (point.isPassed) s.cardPassedValue else CueFormatter.formatEtaClock(point.etaEpochMillis, snapshot.is24HourFormat)
        val eteText = if (point.isPassed) s.cardCompletedValue else CueFormatter.formatEte(point.eteSeconds)
        val ascText = CueFormatter.formatAscentCardValue(point.ascentFromRiderM, snapshot.isMetricElevation)
        val gradeText = String.format(Locale.US, "%.1f%%", point.averageGradePct)

        drawMetricCard(
            canvas, RectF(padX, gridTop, padX + cardW, gridTop + cardH),
            label = s.cardEtaLabel, value = etaText, valueColor = if (point.isPassed) p.passedGreen else p.accentTime, p = p
        )
        drawMetricCard(
            canvas, RectF(padX + cardW + cardGap, gridTop, w - padX, gridTop + cardH),
            label = s.cardEstTimeLabel, value = eteText, valueColor = p.textPrimary, p = p
        )
        drawMetricCard(
            canvas, RectF(padX, gridTop + cardH + cardGap, padX + cardW, gridBottom),
            label = s.cardAscentLabel, value = ascText, valueColor = p.textPrimary, p = p
        )
        drawMetricCard(
            canvas, RectF(padX + cardW + cardGap, gridTop + cardH + cardGap, w - padX, gridBottom),
            label = s.cardAvgGradeLabel, value = gradeText, valueColor = p.textPrimary, p = p
        )

        // 5. Mini Elevation Sparkline at Bottom
        val sparkRect = RectF(padX, h * 0.805f, w - padX, h * 0.960f)
        drawElevationSparkline(canvas, sparkRect, point, p)
    }

    private fun drawPadlockIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        isLocked: Boolean,
        color: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
        }
        val bodyW = size * 0.72f
        val bodyH = size * 0.54f
        val bodyTop = cy - size * 0.05f
        val bodyRect = RectF(cx - bodyW * 0.5f, bodyTop, cx + bodyW * 0.5f, bodyTop + bodyH)

        // Lock body
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(bodyRect, 4f, 4f, paint)

        // Keyhole
        val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (color == Color.WHITE) Color.BLACK else Color.WHITE
            style = Paint.Style.FILL
        }
        val holeCy = bodyTop + bodyH * 0.45f
        canvas.drawCircle(cx, holeCy, bodyH * 0.16f, holePaint)
        canvas.drawRect(cx - 1.5f, holeCy, cx + 1.5f, holeCy + bodyH * 0.30f, holePaint)

        // Shackle (arch)
        val shacklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = size * 0.12f
            strokeCap = Paint.Cap.ROUND
        }
        val shackleW = bodyW * 0.56f
        val shackleH = size * 0.42f

        if (isLocked) {
            val shackleRect = RectF(
                cx - shackleW * 0.5f,
                bodyTop - shackleH,
                cx + shackleW * 0.5f,
                bodyTop + shackleH * 0.2f
            )
            canvas.drawArc(shackleRect, 180f, 180f, false, shacklePaint)
            canvas.drawLine(cx - shackleW * 0.5f, bodyTop - shackleH * 0.5f, cx - shackleW * 0.5f, bodyTop + 1f, shacklePaint)
            canvas.drawLine(cx + shackleW * 0.5f, bodyTop - shackleH * 0.5f, cx + shackleW * 0.5f, bodyTop + 1f, shacklePaint)
        } else {
            val openLift = size * 0.12f
            val shackleRect = RectF(
                cx - shackleW * 0.5f,
                bodyTop - shackleH - openLift,
                cx + shackleW * 0.5f,
                bodyTop + shackleH * 0.2f - openLift
            )
            canvas.drawArc(shackleRect, 180f, 180f, false, shacklePaint)
            canvas.drawLine(cx - shackleW * 0.5f, bodyTop - shackleH * 0.5f - openLift, cx - shackleW * 0.5f, bodyTop + 1f, shacklePaint)
            canvas.drawLine(cx + shackleW * 0.5f, bodyTop - shackleH * 0.5f - openLift, cx + shackleW * 0.5f, bodyTop - openLift * 0.5f, shacklePaint)
        }
    }

    private fun drawMetricCard(
        canvas: Canvas,
        rect: RectF,
        label: String,
        value: String,
        valueColor: Int,
        p: Palette,
    ) {
        val r = rect.height() * 0.20f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = p.cardBg
        }
        canvas.drawRoundRect(rect, r, r, fill)

        if (!p.isNight) {
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = p.divider
            }
            canvas.drawRoundRect(rect, r, r, border)
        }

        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = rect.height() * 0.25f
            color = if (label == "ETA" && valueColor == p.accentTime) p.accentTime else p.textSecondary
            textAlign = Paint.Align.CENTER
        }
        val valPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = rect.height() * 0.46f
            color = valueColor
            textAlign = Paint.Align.CENTER
        }
        val maxW = rect.width() * 0.90f
        if (valPaint.measureText(value) > maxW) {
            valPaint.textSize = rect.height() * 0.36f
        }
        canvas.drawText(label, rect.centerX(), rect.top + rect.height() * 0.34f, labelPaint)
        canvas.drawText(value, rect.centerX(), rect.top + rect.height() * 0.78f, valPaint)
    }

    private fun drawElevationSparkline(
        canvas: Canvas,
        rect: RectF,
        point: CuePoint,
        p: Palette,
    ) {
        val samples = point.sparklineElevationsM
        if (samples.size < 2) return

        val minE = samples.minOrNull() ?: 0f
        val maxE = samples.maxOrNull() ?: 1f
        val spanE = max(35f, maxE - minE)

        val accent = if (p.isNight) point.category.badgeColorDark else point.category.badgeColorLight
        val plotTop = rect.top + rect.height() * 0.18f
        val plotBottom = rect.bottom - rect.height() * 0.06f
        val plotH = (plotBottom - plotTop).coerceAtLeast(10f)

        val linePath = Path()
        val fillPath = Path()

        samples.forEachIndexed { idx, elev ->
            val x = rect.left + (idx.toFloat() / (samples.size - 1)) * rect.width()
            val norm = ((elev - minE) / spanE).coerceIn(0f, 1f)
            val y = plotBottom - norm * plotH
            if (idx == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, plotBottom)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(rect.right, plotBottom)
        fillPath.close()

        val topAlphaColor = (accent and 0x00FFFFFF) or (0x88 shl 24)
        val bottomAlphaColor = (accent and 0x00FFFFFF) or (0x08 shl 24)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, plotTop, 0f, plotBottom,
                topAlphaColor, bottomAlphaColor,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(fillPath, fillPaint)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(3.5f, rect.height() * 0.04f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = accent
        }
        canvas.drawPath(linePath, strokePaint)

        // End flag / pin marker at right tip
        val endNorm = ((samples.last() - minE) / spanE).coerceIn(0f, 1f)
        val endY = plotBottom - endNorm * plotH
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2.5f, rect.height() * 0.025f)
            color = accent
        }
        val poleTop = endY - rect.height() * 0.16f
        canvas.drawLine(rect.right, plotBottom, rect.right, poleTop, pinPaint)
        val flagPath = Path().apply {
            moveTo(rect.right, poleTop)
            lineTo(rect.right - rect.width() * 0.045f, poleTop + rect.height() * 0.05f)
            lineTo(rect.right, poleTop + rect.height() * 0.10f)
            close()
        }
        canvas.drawPath(flagPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = accent
        })
    }

    // -------------------------------------------------------------------------
    // SINGLE DATA FIELD (Wide 1-column vs Compact 2-column)
    // -------------------------------------------------------------------------

    private fun drawWideSingleField(
        canvas: Canvas,
        w: Float,
        h: Float,
        point: CuePoint,
        snapshot: CueSheetSnapshot,
        p: Palette,
    ) {
        val padX = w * 0.035f
        val headerH = h * 0.27f
        val accent = if (p.isNight) point.category.badgeColorDark else point.category.badgeColorLight

        // Top tinted header strip: "PRÓXIMO WAYPOINT" ... "ETA 10:02"
        val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = (accent and 0x00FFFFFF) or (if (p.isNight) 0x40 shl 24 else 0x28 shl 24)
        }
        canvas.drawRect(0f, 0f, w, headerH, headerBgPaint)

        val s = snapshot.strings
        val hdrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = condensedBold
            textSize = headerH * 0.60f
            color = p.textPrimary
        }
        val hdrBase = headerH * 0.5f - (hdrPaint.descent() + hdrPaint.ascent()) * 0.5f
        canvas.drawText(s.nextWaypointHeader, padX, hdrBase, hdrPaint)

        val etaClock = CueFormatter.formatEtaClock(point.etaEpochMillis, snapshot.is24HourFormat)
        val etaHdrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = headerH * 0.58f
            color = if (p.isNight) p.accentTime else p.textPrimary
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("ETA $etaClock", w - padX, hdrBase, etaHdrPaint)

        // Body area
        val bodyTop = headerH
        val bodyH = h - headerH
        val iconR = min(bodyH * 0.34f, w * 0.075f)
        val iconCx = padX + iconR
        val iconCy = bodyTop + bodyH * 0.50f
        drawWaypointBadge(canvas, iconCx, iconCy, iconR, point.category, false, p)

        // Right huge distance
        val dist = CueFormatter.formatDistance(point.deltaDistanceM, snapshot.isMetricDistance)
        val unitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = bodyH * 0.30f
            color = p.textSecondary
            textAlign = Paint.Align.RIGHT
        }
        val distPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = bodyH * 0.58f
            color = p.textPrimary
            textAlign = Paint.Align.RIGHT
        }
        val unitStr = " ${dist.unit}"
        val unitW = unitPaint.measureText(unitStr)
        val distBase = bodyTop + bodyH * 0.52f - (distPaint.descent() + distPaint.ascent()) * 0.38f
        canvas.drawText(unitStr, w - padX, distBase, unitPaint)
        canvas.drawText(dist.value, w - padX - unitW, distBase, distPaint)

        val rightUsedW = distPaint.measureText(dist.value) + unitW + w * 0.04f
        val textLeft = iconCx + iconR + w * 0.03f
        val availNameW = (w - padX - rightUsedW - textLeft).coerceAtLeast(w * 0.18f)

        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = bodyH * 0.34f
            color = p.textPrimary
        }
        if (namePaint.measureText(point.name) > availNameW) {
            namePaint.textSize = bodyH * 0.28f
        }
        val clippedName = TextUtils.ellipsize(point.name, namePaint, availNameW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(clippedName, textLeft, bodyTop + bodyH * 0.48f, namePaint)

        val elevStr = CueFormatter.formatElevationDelta(point.ascentFromRiderM, point.descentFromRiderM, snapshot.isMetricElevation, s)
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mediumSans
            textSize = bodyH * 0.24f
            color = p.textSecondary
        }
        canvas.drawText(elevStr, textLeft, bodyTop + bodyH * 0.80f, subPaint)
    }

    private fun drawCompactSingleField(
        canvas: Canvas,
        w: Float,
        h: Float,
        point: CuePoint,
        snapshot: CueSheetSnapshot,
        p: Palette,
    ) {
        val padX = w * 0.05f
        // Top line: Badge + Waypoint Name
        val topRowCy = h * 0.25f
        val iconR = min(h * 0.15f, w * 0.10f)
        val iconCx = padX + iconR
        drawWaypointBadge(canvas, iconCx, topRowCy, iconR, point.category, false, p)

        val nameLeft = iconCx + iconR + w * 0.04f
        val availW = (w - padX - nameLeft).coerceAtLeast(20f)
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = h * 0.21f
            color = p.textPrimary
        }
        val clippedName = TextUtils.ellipsize(point.name, namePaint, availW, TextUtils.TruncateAt.END).toString()
        val nameBase = topRowCy - (namePaint.descent() + namePaint.ascent()) * 0.5f
        canvas.drawText(clippedName, nameLeft, nameBase, namePaint)

        // Bottom hero: Huge Distance (right-aligned like native Karoo numeric fields)
        val dist = CueFormatter.formatDistance(point.deltaDistanceM, snapshot.isMetricDistance)
        val unitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = h * 0.22f
            color = p.textSecondary
            textAlign = Paint.Align.RIGHT
        }
        val distPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = h * 0.44f
            color = p.textPrimary
            textAlign = Paint.Align.RIGHT
        }
        val unitStr = " ${dist.unit}"
        val unitW = unitPaint.measureText(unitStr)
        val maxNumW = w - padX * 2f - unitW
        if (distPaint.measureText(dist.value) > maxNumW) {
            distPaint.textSize = h * 0.35f
        }
        val distBase = h * 0.84f
        canvas.drawText(unitStr, w - padX, distBase, unitPaint)
        canvas.drawText(dist.value, w - padX - unitW, distBase, distPaint)
    }

    // -------------------------------------------------------------------------
    // EMPTY STATES
    // -------------------------------------------------------------------------

    private fun drawEmptyFullState(
        canvas: Canvas,
        w: Float,
        h: Float,
        snapshot: CueSheetSnapshot,
        p: Palette,
    ) {
        val s = snapshot.strings
        val isCompactH = h < 360f
        val refH = max(h, w * 0.85f)
        val badgeR = if (isCompactH) min(w, h) * 0.11f else min(w, h) * 0.10f
        val badgeCy = if (isCompactH) h * 0.28f else h * 0.34f
        drawWaypointBadge(canvas, w * 0.5f, badgeCy, badgeR, CueCategory.GENERIC_WAYPOINT, false, p)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (refH * 0.052f).coerceIn(18f, h * 0.12f)
            color = p.textPrimary
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(s.noActiveRouteTitle, w * 0.5f, if (isCompactH) h * 0.48f else h * 0.49f, titlePaint)

        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mediumSans
            textSize = (refH * 0.032f).coerceIn(13f, h * 0.075f)
            color = p.textSecondary
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(s.noActiveRouteLine1, w * 0.5f, if (isCompactH) h * 0.58f else h * 0.55f, subPaint)
        canvas.drawText(s.noActiveRouteLine2, w * 0.5f, if (isCompactH) h * 0.66f else h * 0.59f, subPaint)

        val pacePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (refH * 0.030f).coerceIn(12f, h * 0.070f)
            color = p.accentTime
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(s.formatBaseFlatPace(snapshot.learnedFlatSpeedKmh), w * 0.5f, if (isCompactH) h * 0.78f else h * 0.68f, pacePaint)
    }

    private fun drawEmptySingleState(
        canvas: Canvas,
        w: Float,
        h: Float,
        isWideSlot: Boolean,
        snapshot: CueSheetSnapshot,
        p: Palette,
    ) {
        val s = snapshot.strings
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = condensedBold
            textSize = h * 0.22f
            color = p.textMuted
        }
        canvas.drawText(s.nextWaypointHeader, w * 0.05f, h * 0.28f, labelPaint)

        val dashPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = h * 0.38f
            color = p.textSecondary
            textAlign = if (isWideSlot) Paint.Align.RIGHT else Paint.Align.LEFT
        }
        val x = if (isWideSlot) w * 0.95f else w * 0.05f
        canvas.drawText(s.noRouteShort, x, h * 0.78f, dashPaint)
    }

    // -------------------------------------------------------------------------
    // VECTOR CATEGORY BADGES (crisp geometric icons at any resolution)
    // -------------------------------------------------------------------------

    private fun drawWaypointBadge(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        category: CueCategory,
        isPassed: Boolean,
        p: Palette,
    ) {
        val bgColor = when {
            isPassed -> p.passedGreen
            p.isNight -> category.badgeColorDark
            else -> category.badgeColorLight
        }
        val fgColor = if (!isPassed && category == CueCategory.FINISH && p.isNight) {
            0xFF111827.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = bgColor
        }
        canvas.drawCircle(cx, cy, r, circlePaint)

        val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fgColor
        }
        val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2.5f, r * 0.16f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = fgColor
        }

        if (isPassed) {
            // Crisp Checkmark ✓
            val path = Path().apply {
                moveTo(cx - r * 0.38f, cy + r * 0.02f)
                lineTo(cx - r * 0.10f, cy + r * 0.30f)
                lineTo(cx + r * 0.42f, cy - r * 0.28f)
            }
            canvas.drawPath(path, iconStroke)
            return
        }

        when (category) {
            CueCategory.WATER -> {
                // Water droplet
                val drop = Path().apply {
                    moveTo(cx, cy - r * 0.52f)
                    cubicTo(cx + r * 0.45f, cy + r * 0.02f, cx + r * 0.38f, cy + r * 0.48f, cx, cy + r * 0.48f)
                    cubicTo(cx - r * 0.38f, cy + r * 0.48f, cx - r * 0.45f, cy + r * 0.02f, cx, cy - r * 0.52f)
                    close()
                }
                canvas.drawPath(drop, iconFill)
            }

            CueCategory.SUMMIT_CLIMB -> {
                // Twin mountain peaks
                val mtn = Path().apply {
                    moveTo(cx - r * 0.52f, cy + r * 0.38f)
                    lineTo(cx - r * 0.12f, cy - r * 0.42f)
                    lineTo(cx + r * 0.14f, cy + r * 0.08f)
                    lineTo(cx + r * 0.30f, cy - r * 0.18f)
                    lineTo(cx + r * 0.54f, cy + r * 0.38f)
                    close()
                }
                canvas.drawPath(mtn, iconFill)
            }

            CueCategory.FOOD_COFFEE -> {
                // Coffee cup + handle
                val cupRect = RectF(cx - r * 0.40f, cy - r * 0.25f, cx + r * 0.24f, cy + r * 0.30f)
                canvas.drawRoundRect(cupRect, r * 0.10f, r * 0.10f, iconFill)
                val handleRect = RectF(cx + r * 0.15f, cy - r * 0.16f, cx + r * 0.44f, cy + r * 0.16f)
                canvas.drawArc(handleRect, -90f, 180f, false, iconStroke)
                canvas.drawLine(cx - r * 0.46f, cy + r * 0.42f, cx + r * 0.36f, cy + r * 0.42f, iconStroke)
            }

            CueCategory.DANGER_CAUTION -> {
                // Exclamation mark !
                canvas.drawLine(cx, cy - r * 0.42f, cx, cy + r * 0.08f, iconStroke.apply { strokeWidth = max(3f, r * 0.20f) })
                canvas.drawCircle(cx, cy + r * 0.36f, r * 0.11f, iconFill)
            }

            CueCategory.AID_CONTROL -> {
                // Cross +
                val arm = r * 0.42f
                val thick = r * 0.16f
                canvas.drawRoundRect(RectF(cx - thick, cy - arm, cx + thick, cy + arm), thick * 0.4f, thick * 0.4f, iconFill)
                canvas.drawRoundRect(RectF(cx - arm, cy - thick, cx + arm, cy + thick), thick * 0.4f, thick * 0.4f, iconFill)
            }

            CueCategory.FINISH -> {
                // Flagpole + checkered flag
                val poleX = cx - r * 0.34f
                canvas.drawLine(poleX, cy - r * 0.46f, poleX, cy + r * 0.48f, iconStroke)
                val flagRect = RectF(poleX, cy - r * 0.44f, cx + r * 0.42f, cy + r * 0.08f)
                canvas.drawRect(flagRect, iconStroke)
                val midX = (flagRect.left + flagRect.right) * 0.5f
                val midY = (flagRect.top + flagRect.bottom) * 0.5f
                canvas.drawRect(flagRect.left, flagRect.top, midX, midY, iconFill)
                canvas.drawRect(midX, midY, flagRect.right, flagRect.bottom, iconFill)
            }

            CueCategory.BIKE_SERVICE -> {
                // Gear / wheel spokes
                canvas.drawCircle(cx, cy, r * 0.38f, iconStroke)
                canvas.drawCircle(cx, cy, r * 0.13f, iconFill)
            }

            CueCategory.REST_CAMPING -> {
                // Shelter / Tent A-frame
                val tent = Path().apply {
                    moveTo(cx, cy - r * 0.44f)
                    lineTo(cx - r * 0.48f, cy + r * 0.38f)
                    lineTo(cx + r * 0.48f, cy + r * 0.38f)
                    close()
                }
                canvas.drawPath(tent, iconStroke)
            }

            CueCategory.GENERIC_WAYPOINT -> {
                // Map Pin
                canvas.drawCircle(cx, cy - r * 0.12f, r * 0.26f, iconStroke)
                val tip = Path().apply {
                    moveTo(cx - r * 0.22f, cy + r * 0.02f)
                    lineTo(cx, cy + r * 0.48f)
                    lineTo(cx + r * 0.22f, cy + r * 0.02f)
                    close()
                }
                canvas.drawPath(tip, iconFill)
            }
        }
    }
}
