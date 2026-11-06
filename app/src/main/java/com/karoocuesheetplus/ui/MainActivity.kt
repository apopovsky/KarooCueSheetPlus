package com.karoocuesheetplus.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.karoocuesheetplus.data.CueSettingsStore
import com.karoocuesheetplus.domain.CueListWindowBuilder
import com.karoocuesheetplus.domain.CueSheetSettings
import com.karoocuesheetplus.domain.CueStrings
import com.karoocuesheetplus.domain.DemoRouteProvider
import com.karoocuesheetplus.domain.SurfacePreference
import com.karoocuesheetplus.domain.ThemePreference
import com.karoocuesheetplus.render.CueSheetRenderer
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: CueSettingsStore
    private lateinit var previewFullImage: ImageView
    private lateinit var previewSingleImage: ImageView

    private var previewManualIndex: Int? = 0
    private var previewDetailId: String? = null
    private var previewDetailLocked: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = CueSettingsStore.getInstance(this)
        val strings = CueStrings.forLocale()
        val isEs = strings.langCode == "es"

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0D10"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14)
            setPadding(pad, dp(16), pad, dp(76))
        }
        scroll.addView(root)

        // App Header
        root.addView(TextView(this).apply {
            text = "KAROO CUE SHEET+"
            setTextColor(Color.parseColor("#F5C518"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = if (isEs) {
                "Waypoints, POIs, Cimas y ETA inteligente por terreno y pendiente"
            } else {
                "Waypoints, POIs, Climbs & smart terrain-aware ETA"
            }
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, dp(12))
        })

        // Interactive Live Preview Card
        val previewCard = createCardContainer()
        previewCard.addView(
            sectionHeader(
                if (isEs) "VISTA PREVIA INTERACTIVA (TOCA FILAS O ▲/▼)"
                else "INTERACTIVE PREVIEW (TAP ROWS OR ▲/▼)"
            )
        )

        previewFullImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(390),
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(10)
            }
            scaleType = ImageView.ScaleType.FIT_XY
            setupInteractivePreviewTouch(this)
        }
        previewCard.addView(previewFullImage)

        previewSingleImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(88),
            )
            scaleType = ImageView.ScaleType.FIT_XY
        }
        previewCard.addView(previewSingleImage)
        root.addView(previewCard)

        // Settings Card
        val configCard = createCardContainer().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        configCard.addView(sectionHeader(if (isEs) "CONFIGURACIÓN DE LA EXTENSIÓN" else "EXTENSION SETTINGS"))

        val climbsSwitch = addSwitchRow(
            parent = configCard,
            title = if (isEs) "Incluir Cimas (Climbs)" else "Include Climbs",
            subtitle = if (isEs) "Añade automáticamente los puertos detectados en la ruta"
            else "Automatically add detected route climbs to the list",
            initial = settingsStore.settings.value.includeClimbs,
        ) { checked ->
            settingsStore.update { it.copy(includeClimbs = checked) }
        }

        val globalPoisSwitch = addSwitchRow(
            parent = configCard,
            title = if (isEs) "Incluir POIs Globales cercanos" else "Include nearby Global POIs",
            subtitle = if (isEs) "Muestra POIs guardados en tu cuenta a menos de 250 m de la ruta"
            else "Show account POIs within 250 m of the active route",
            initial = settingsStore.settings.value.includeGlobalPois,
        ) { checked ->
            settingsStore.update { it.copy(includeGlobalPois = checked) }
        }

        val collapsePoisSwitch = addSwitchRow(
            parent = configCard,
            title = if (isEs) "Colapsar waypoints en circuitos" else "Collapse circuit waypoints",
            subtitle = if (isEs) "En circuitos o pistas, muestra solo el próximo paso y oculta vueltas futuras"
            else "On multi-lap tracks, shows only the next pass and hides future repeated laps",
            initial = settingsStore.settings.value.collapseCircuitPois,
        ) { checked ->
            settingsStore.update { it.copy(collapseCircuitPois = checked) }
        }

        val demoSwitch = addSwitchRow(
            parent = configCard,
            title = if (isEs) "Modo Simulación (Demo en vivo)" else "Live Demo Mode",
            subtitle = if (isEs) "Simula una ruta con puertos en tus pantallas de datos para probar sin GPS"
            else "Simulate an active mountain route on your data pages without GPS",
            initial = settingsStore.settings.value.demoModeEnabled,
        ) { checked ->
            settingsStore.update { it.copy(demoModeEnabled = checked) }
        }

        // Surface / Bike profile row (Auto by default!)
        val surfaceValueText = addStepperRow(
            parent = configCard,
            title = if (isEs) "Perfil de Bici / Terreno" else "Bike / Terrain Profile",
            subtitle = if (isEs) "Auto detecta Ruta, Gravel o MTB según tu perfil activo en Karoo"
            else "Auto detects Road, Gravel or MTB from your active Karoo Ride Profile",
            onMinus = { cycleSurface(-1) },
            onPlus = { cycleSurface(+1) },
            minusLabel = "◀",
            plusLabel = "▶",
        )

        // Theme selector row
        val themeValueText = addStepperRow(
            parent = configCard,
            title = if (isEs) "Tema de color" else "Color Theme",
            subtitle = if (isEs) "Auto sigue el modo Día/Noche del perfil en Karoo OS"
            else "Auto follows Karoo OS Day/Night profile setting",
            onMinus = { cycleTheme(-1) },
            onPlus = { cycleTheme(+1) },
            minusLabel = "◀",
            plusLabel = "▶",
        )

        // Flat Seed Speed row
        val speedValueText = addStepperRow(
            parent = configCard,
            title = if (isEs) "Velocidad semilla en llano" else "Initial flat seed speed",
            subtitle = if (isEs) "Referencia inicial en el km 0 (luego aprende llano, subida y bajada)"
            else "Initial km 0 reference (then learns flat, climb & descent pace live)",
            onMinus = {
                settingsStore.update { s ->
                    s.copy(defaultFlatSpeedKmh = (s.defaultFlatSpeedKmh - 1.0).coerceIn(14.0, 42.0))
                }
            },
            onPlus = {
                settingsStore.update { s ->
                    s.copy(defaultFlatSpeedKmh = (s.defaultFlatSpeedKmh + 1.0).coerceIn(14.0, 42.0))
                }
            },
        )

        // Auto-return seconds row
        val timeoutValueText = addStepperRow(
            parent = configCard,
            title = if (isEs) "Auto-retorno a lista en vivo" else "Auto-return timeout",
            subtitle = if (isEs) "Segundos antes de cerrar detalle o volver al próximo punto"
            else "Seconds before closing detail view or returning to active point",
            onMinus = {
                settingsStore.update { s ->
                    s.copy(autoReturnSeconds = (s.autoReturnSeconds - 2).coerceIn(6, 30))
                }
            },
            onPlus = {
                settingsStore.update { s ->
                    s.copy(autoReturnSeconds = (s.autoReturnSeconds + 2).coerceIn(6, 30))
                }
            },
        )

        // Refresh interval seconds row
        val refreshIntervalValueText = addStepperRow(
            parent = configCard,
            title = if (isEs) "Intervalo de actualización" else "Refresh interval",
            subtitle = if (isEs) "Frecuencia de cálculo en vivo (5s ahorra batería frente a 1s)"
            else "Live recalculation frequency (5s saves battery compared to 1s)",
            onMinus = {
                settingsStore.update { s ->
                    s.copy(refreshIntervalSeconds = (s.refreshIntervalSeconds - 1).coerceIn(1, 30))
                }
            },
            onPlus = {
                settingsStore.update { s ->
                    s.copy(refreshIntervalSeconds = (s.refreshIntervalSeconds + 1).coerceIn(1, 30))
                }
            },
        )

        root.addView(configCard)

        // Setup instructions card
        val helpCard = createCardContainer().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        helpCard.addView(
            sectionHeader(
                if (isEs) "CÓMO AÑADIR A TUS PANTALLAS EN KAROO"
                else "HOW TO ADD TO YOUR KAROO PAGES"
            )
        )
        helpCard.addView(TextView(this).apply {
            text = if (isEs) {
                "1. Ve a Perfiles → Páginas → Añadir Página.\n" +
                    "2. Para la lista completa, elige plantilla de 1 campo y selecciona Karoo Cue Sheet+ → \"Cue Sheet+ (Lista)\".\n" +
                    "3. Para llevar el aviso compacto junto a tus vatios/pulso, añade \"Próximo Waypoint\" en cualquier celda de 1 o 2 columnas."
            } else {
                "1. Go to Profiles → Pages → Add Page.\n" +
                    "2. For the full-page view, pick a 1-field layout and select Karoo Cue Sheet+ → \"Cue Sheet+ (List)\".\n" +
                    "3. For a compact alert alongside power/HR, add \"Next Waypoint\" to any 1-column or 2-column slot."
            }
            setTextColor(Color.parseColor("#D1D5DB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(helpCard)

        // Root container with floating exit/back button (matches WPrimeExtension back FAB)
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0D10"))
        }
        container.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )

        val backFab = FrameLayout(this).apply {
            val fabW = dp(52)
            val fabH = dp(50)
            layoutParams = FrameLayout.LayoutParams(fabW, fabH).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = dp(12)
                leftMargin = 0
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                val r = dp(25).toFloat()
                cornerRadii = floatArrayOf(
                    0f, 0f,       // top-left (flat against bezel)
                    r, r,         // top-right
                    r, r,         // bottom-right
                    0f, 0f        // bottom-left (flat against bezel)
                )
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            isClickable = true
            isFocusable = true
            elevation = dp(8).toFloat()

            val arrowView = TextView(context).apply {
                text = "←"
                setTextColor(Color.parseColor("#F5C518"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    rightMargin = dp(4)
                }
            }
            addView(arrowView)

            setOnClickListener {
                finish()
            }
        }
        container.addView(backFab)

        setContentView(container)

        lifecycleScope.launch {
            settingsStore.settings.collect { s ->
                val curStrings = CueStrings.forLocale()
                val curEs = curStrings.langCode == "es"
                if (climbsSwitch.isChecked != s.includeClimbs) climbsSwitch.isChecked = s.includeClimbs
                if (globalPoisSwitch.isChecked != s.includeGlobalPois) globalPoisSwitch.isChecked = s.includeGlobalPois
                if (collapsePoisSwitch.isChecked != s.collapseCircuitPois) collapsePoisSwitch.isChecked = s.collapseCircuitPois
                if (demoSwitch.isChecked != s.demoModeEnabled) demoSwitch.isChecked = s.demoModeEnabled
                surfaceValueText.text = curStrings.surfaceLabel(s.surfacePreference)
                themeValueText.text = when (s.themePreference) {
                    ThemePreference.AUTO -> "Auto"
                    ThemePreference.DARK -> if (curEs) "Noche" else "Night"
                    ThemePreference.LIGHT -> if (curEs) "Día" else "Day"
                }
                speedValueText.text = String.format(Locale.US, "%d km/h", s.defaultFlatSpeedKmh.roundToInt())
                timeoutValueText.text = "${s.autoReturnSeconds} s"
                refreshIntervalValueText.text = "${s.refreshIntervalSeconds} s"
                refreshInteractivePreview(s)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInteractivePreviewTouch(view: ImageView) {
        view.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP && v.width > 0 && v.height > 0) {
                val relX = event.x / v.width.toFloat()
                val relY = event.y / v.height.toFloat()
                val currentSettings = settingsStore.settings.value
                val snap = DemoRouteProvider.buildDemoSnapshot(
                    settings = currentSettings,
                    manualStartIndex = previewManualIndex,
                    selectedDetailId = previewDetailId,
                    detailRemainingSeconds = if (previewDetailLocked) 0 else currentSettings.autoReturnSeconds,
                    isDetailLocked = previewDetailLocked,
                )

                if (previewDetailId != null) {
                    if (relY <= 0.11f && relX > 0.80f) {
                        previewDetailLocked = !previewDetailLocked
                    } else if (relY <= 0.11f || !previewDetailLocked) {
                        previewDetailId = null
                        previewDetailLocked = false
                    }
                } else if (relY <= 0.11f) {
                    if (relX in 0.55f..0.775f) {
                        previewManualIndex = CueListWindowBuilder.computeNextScrollIndex(
                            snap.allPoints, previewManualIndex, -1
                        )
                    } else if (relX > 0.775f) {
                        previewManualIndex = CueListWindowBuilder.computeNextScrollIndex(
                            snap.allPoints, previewManualIndex, +1
                        )
                    }
                } else {
                    val rowIdx = (((relY - 0.10f) / 0.18f).toInt()).coerceIn(0, 4)
                    val tapped = snap.window.rows.getOrNull(rowIdx)
                    if (tapped != null) {
                        previewDetailId = tapped.id
                        previewDetailLocked = false
                    }
                }
                refreshInteractivePreview(currentSettings)
            }
            true
        }
    }

    private fun refreshInteractivePreview(settings: CueSheetSettings) {
        val snap = DemoRouteProvider.buildDemoSnapshot(
            settings = settings,
            manualStartIndex = previewManualIndex,
            selectedDetailId = previewDetailId,
            detailRemainingSeconds = if (previewDetailLocked) 0 else settings.autoReturnSeconds,
            isDetailLocked = previewDetailLocked,
        )
        val isNight = when (settings.themePreference) {
            ThemePreference.DARK -> true
            ThemePreference.LIGHT -> false
            ThemePreference.AUTO -> {
                val nightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMask != Configuration.UI_MODE_NIGHT_NO
            }
        }
        previewFullImage.setImageBitmap(CueSheetRenderer.renderFullPage(snap, 420, 680, isNight))
        previewSingleImage.setImageBitmap(CueSheetRenderer.renderSingleField(snap, 420, 130, isWideSlot = true, isNightMode = isNight))
    }

    private fun cycleTheme(delta: Int) {
        val values = ThemePreference.entries
        settingsStore.update { s ->
            val nextIdx = (s.themePreference.ordinal + delta + values.size) % values.size
            s.copy(themePreference = values[nextIdx])
        }
    }

    private fun cycleSurface(delta: Int) {
        val values = SurfacePreference.entries
        settingsStore.update { s ->
            val nextIdx = (s.surfacePreference.ordinal + delta + values.size) % values.size
            s.copy(surfacePreference = values[nextIdx])
        }
    }

    private fun createCardContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161A20"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#2A303B"))
            }
        }
    }

    private fun sectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun addSwitchRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        initial: Boolean,
        onChanged: (Boolean) -> Unit,
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(10)
            }
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }
        row.addView(textCol)
        row.addView(sw)
        parent.addView(row)
        parent.addView(dividerView())
        return sw
    }

    private fun addStepperRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        onMinus: () -> Unit,
        onPlus: () -> Unit,
        minusLabel: String = "−",
        plusLabel: String = "+",
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(10))
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        row.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, dp(8))
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnMinus = pillButton(minusLabel, onMinus)
        val valView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F5C518"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val btnPlus = pillButton(plusLabel, onPlus)

        controls.addView(btnMinus)
        controls.addView(valView)
        controls.addView(btnPlus)
        row.addView(controls)
        parent.addView(row)
        parent.addView(dividerView())
        return valView
    }

    private fun pillButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(42))
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#252B36"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#4B5563"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dividerView(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#242933"))
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).roundToInt()
}
