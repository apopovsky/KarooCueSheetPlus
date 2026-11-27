package com.karoocuesheetplus.domain

import java.util.Locale
import kotlin.math.roundToInt

data class CueStrings(
    val langCode: String,
    // Categories
    val catWater: String,
    val catFoodCoffee: String,
    val catSummitClimb: String,
    val catAidControl: String,
    val catDangerCaution: String,
    val catBikeService: String,
    val catRestCamping: String,
    val catGenericWaypoint: String,
    val catFinish: String,
    // Subtitles & Route Generation
    val subRiverCrossing: String,
    val subTechnicalDescent: String,
    val subRockSlabs: String,
    val subRouteAlert: String,
    val globalSuffix: String,
    val finishPointName: String,
    val destinationFallback: String,
    val climbTitleFormat: String,      // e.g. "Climb #%d"
    val climbSubtitleFormat: String,   // e.g. "Climb • %.1f km at %.1f%% (+%dm)"
    // Formatter & Canvas List
    val flatTerrainLabel: String,      // e.g. "→ Flat"
    val demoBadge: String,             // e.g. "DEMO"
    val completedRowRight: String,     // e.g. "✓ Done"
    val passedRowSub: String,          // e.g. "(Passed)"
    // Canvas Detail View
    val backToListFormat: String,      // e.g. "← Back to list (%ds)"
    val backToListPlain: String,       // e.g. "← Back to list"
    val passedAgoCaption: String,      // e.g. "PASSED AGO"
    val distanceRemainingCaption: String, // e.g. "DISTANCE TO GO"
    val cardEtaLabel: String,
    val cardPassedValue: String,       // e.g. "✓ Passed"
    val cardCompletedValue: String,    // e.g. "Completed"
    val cardEstTimeLabel: String,
    val cardAscentLabel: String,
    val cardAvgGradeLabel: String,
    // Canvas Single Field & Empty States
    val nextWaypointHeader: String,    // e.g. "NEXT WAYPOINT"
    val noActiveRouteTitle: String,
    val noActiveRouteLine1: String,
    val noActiveRouteLine2: String,
    val baseFlatPaceFormat: String,    // e.g. "Base flat pace: %.1f km/h"
    val noRouteShort: String,
    // Surface Profiles
    val surfaceAuto: String,
    val surfaceRoad: String,
    val surfaceGravel: String,
    val surfaceMtb: String,
    val surfaceMtbTech: String,
    // Time Display Modes
    val timeModeEtaAndEte: String,
    val timeModeHoursMinutes: String,
    val timeModeMinutesOnly: String,
    // Row Metric Modes
    val rowMetricBoth: String,
    val rowMetricTimeOnly: String,
    val rowMetricDistanceOnly: String,
    // Unit Preferences
    val unitAuto: String,
    val unitMetric: String,
    val unitImperial: String,
    // Category Filter
    val filterHeaderTitle: String,
    val categoryRouteCountFormat: String,
) {
    fun formatClimbTitle(indexOneBased: Int): String =
        String.format(Locale.US, climbTitleFormat, indexOneBased)

    fun formatClimbSubtitle(climb: ClimbSegment): String =
        String.format(
            Locale.US,
            climbSubtitleFormat,
            climb.lengthM / 1000.0,
            climb.gradePct,
            climb.totalElevationM.roundToInt(),
        )

    fun formatBackToList(remainingSeconds: Int): String =
        if (remainingSeconds > 0) String.format(Locale.US, backToListFormat, remainingSeconds) else backToListPlain

    fun formatBaseFlatPace(speedKmh: Double, isMetric: Boolean = true): String =
        String.format(Locale.US, baseFlatPaceFormat, CueFormatter.formatSpeed(speedKmh, isMetric, decimals = 1))

    fun surfaceLabel(pref: SurfacePreference): String = when (pref) {
        SurfacePreference.AUTO -> surfaceAuto
        SurfacePreference.ROAD -> surfaceRoad
        SurfacePreference.GRAVEL -> surfaceGravel
        SurfacePreference.MTB -> surfaceMtb
        SurfacePreference.MTB_TECHNICAL -> surfaceMtbTech
    }

    fun surfaceShortLabel(profile: SurfaceProfile): String = when (profile) {
        SurfaceProfile.ROAD -> surfaceRoad
        SurfaceProfile.GRAVEL -> surfaceGravel
        SurfaceProfile.MTB -> surfaceMtb
        SurfaceProfile.MTB_TECHNICAL -> surfaceMtbTech
    }

    fun timeDisplayModeLabel(mode: TimeDisplayMode): String = when (mode) {
        TimeDisplayMode.ETA_AND_ETE -> timeModeEtaAndEte
        TimeDisplayMode.ETE_HOURS_MINUTES -> timeModeHoursMinutes
        TimeDisplayMode.ETE_MINUTES_ONLY -> timeModeMinutesOnly
    }

    fun rowMetricModeLabel(mode: RowMetricMode): String = when (mode) {
        RowMetricMode.DISTANCE_AND_TIME -> rowMetricBoth
        RowMetricMode.TIME_ONLY -> rowMetricTimeOnly
        RowMetricMode.DISTANCE_ONLY -> rowMetricDistanceOnly
    }

    fun unitPreferenceLabel(pref: UnitPreference): String = when (pref) {
        UnitPreference.AUTO -> unitAuto
        UnitPreference.METRIC -> unitMetric
        UnitPreference.IMPERIAL -> unitImperial
    }

    fun formatCategoryRouteCount(count: Int): String =
        String.format(Locale.US, categoryRouteCountFormat, count)

    companion object {
        val EN = CueStrings(
            langCode = "en",
            catWater = "Water",
            catFoodCoffee = "Food / Coffee",
            catSummitClimb = "Climb / Summit",
            catAidControl = "Aid / Checkpoint",
            catDangerCaution = "Caution",
            catBikeService = "Bike Service",
            catRestCamping = "Rest / Shelter",
            catGenericWaypoint = "Waypoint",
            catFinish = "Route Finish",
            subRiverCrossing = "River Crossing / Ford",
            subTechnicalDescent = "Technical Descent",
            subRockSlabs = "Rock Slabs • Caution",
            subRouteAlert = "Route Alert",
            globalSuffix = "Global",
            finishPointName = "Finish",
            destinationFallback = "Destination",
            climbTitleFormat = "Climb #%d",
            climbSubtitleFormat = "Climb • %.1f km at %.1f%% (+%dm)",
            flatTerrainLabel = "→ Flat",
            demoBadge = "DEMO",
            completedRowRight = "✓ Done",
            passedRowSub = "(Passed)",
            backToListFormat = "← Back to list (%ds)",
            backToListPlain = "← Back to list",
            passedAgoCaption = "PASSED AGO",
            distanceRemainingCaption = "DISTANCE TO GO",
            cardEtaLabel = "ETA",
            cardPassedValue = "✓ Passed",
            cardCompletedValue = "Completed",
            cardEstTimeLabel = "EST. TIME",
            cardAscentLabel = "ASCENT +",
            cardAvgGradeLabel = "AVG GRADE",
            nextWaypointHeader = "NEXT WAYPOINT",
            noActiveRouteTitle = "No Active Route",
            noActiveRouteLine1 = "Load a route to see Waypoints,",
            noActiveRouteLine2 = "Climbs, elevation & smart ETA.",
            baseFlatPaceFormat = "Flat base pace: %s",
            noRouteShort = "No route",
            surfaceAuto = "Auto (Karoo)",
            surfaceRoad = "Road Aero",
            surfaceGravel = "Gravel",
            surfaceMtb = "MTB / XC",
            surfaceMtbTech = "MTB Mud/Tech",
            timeModeEtaAndEte = "ETA + Time",
            timeModeHoursMinutes = "Hours & Min (2h 15m)",
            timeModeMinutesOnly = "Minutes Only (135m)",
            rowMetricBoth = "Distance + Time",
            rowMetricTimeOnly = "Time Only (Large)",
            rowMetricDistanceOnly = "Distance Only (Large)",
            unitAuto = "Auto (Karoo)",
            unitMetric = "Metric (km, km/h)",
            unitImperial = "Imperial (mi, mph)",
            filterHeaderTitle = "← Filter POIs",
            categoryRouteCountFormat = "%d on route",
        )

        val ES = CueStrings(
            langCode = "es",
            catWater = "Agua",
            catFoodCoffee = "Café / Comida",
            catSummitClimb = "Cima / Puerto",
            catAidControl = "Control / Asistencia",
            catDangerCaution = "Precaución",
            catBikeService = "Taller / Bici",
            catRestCamping = "Descanso / Refugio",
            catGenericWaypoint = "Waypoint",
            catFinish = "Fin de Ruta",
            subRiverCrossing = "Cruce de Río / Vado",
            subTechnicalDescent = "Descenso Técnico",
            subRockSlabs = "Zona de Lajas • Precaución",
            subRouteAlert = "Alerta en Ruta",
            globalSuffix = "Global",
            finishPointName = "Meta / Fin Ruta",
            destinationFallback = "Destino",
            climbTitleFormat = "Cima Puerto #%d",
            climbSubtitleFormat = "Puerto • %.1f km al %.1f%% (+%dm)",
            flatTerrainLabel = "→ Llano",
            demoBadge = "DEMO",
            completedRowRight = "✓ Completado",
            passedRowSub = "(Pasado)",
            backToListFormat = "← Volver a la lista (%ds)",
            backToListPlain = "← Volver a la lista",
            passedAgoCaption = "SUPERADO HACE",
            distanceRemainingCaption = "DISTANCIA RESTANTE",
            cardEtaLabel = "ETA",
            cardPassedValue = "✓ Pasado",
            cardCompletedValue = "Completado",
            cardEstTimeLabel = "TIEMPO EST.",
            cardAscentLabel = "DESNIVEL +",
            cardAvgGradeLabel = "PEND. MEDIA",
            nextWaypointHeader = "PRÓXIMO WAYPOINT",
            noActiveRouteTitle = "Sin ruta activa",
            noActiveRouteLine1 = "Carga una ruta para ver Waypoints,",
            noActiveRouteLine2 = "Cimas, desnivel y ETA inteligente.",
            baseFlatPaceFormat = "Ritmo base llano: %s",
            noRouteShort = "Sin ruta",
            surfaceAuto = "Auto (Karoo)",
            surfaceRoad = "Ruta Asfalto",
            surfaceGravel = "Gravel",
            surfaceMtb = "MTB / Ripio",
            surfaceMtbTech = "MTB Barro/Técnico",
            timeModeEtaAndEte = "Hora + Tiempo",
            timeModeHoursMinutes = "Horas y Min (2h 15m)",
            timeModeMinutesOnly = "Solo Minutos (135m)",
            rowMetricBoth = "Distancia + Tiempo",
            rowMetricTimeOnly = "Solo Tiempo (Grande)",
            rowMetricDistanceOnly = "Solo Distancia (Grande)",
            unitAuto = "Auto (Karoo)",
            unitMetric = "Métrico (km, km/h)",
            unitImperial = "Imperial (mi, mph)",
            filterHeaderTitle = "← Filtrar POIs",
            categoryRouteCountFormat = "%d en la ruta",
        )

        val PT = EN.copy(
            langCode = "pt",
            catWater = "Água",
            catFoodCoffee = "Café / Comida",
            catSummitClimb = "Topo / Subida",
            catAidControl = "Apoio / Controle",
            catDangerCaution = "Atenção",
            catBikeService = "Oficina / Bike",
            catRestCamping = "Descanso / Abrigo",
            catFinish = "Fim da Rota",
            subRiverCrossing = "Travessia de Rio",
            subTechnicalDescent = "Descida Técnica",
            subRockSlabs = "Pedras • Atenção",
            subRouteAlert = "Alerta na Rota",
            finishPointName = "Chegada",
            destinationFallback = "Destino",
            climbTitleFormat = "Topo #%d",
            climbSubtitleFormat = "Subida • %.1f km a %.1f%% (+%dm)",
            flatTerrainLabel = "→ Plano",
            completedRowRight = "✓ Concluído",
            passedRowSub = "(Passou)",
            backToListFormat = "← Voltar à lista (%ds)",
            backToListPlain = "← Voltar à lista",
            passedAgoCaption = "PASSOU HÁ",
            distanceRemainingCaption = "DISTÂNCIA RESTANTE",
            cardPassedValue = "✓ Passou",
            cardCompletedValue = "Concluído",
            cardEstTimeLabel = "TEMPO EST.",
            cardAscentLabel = "SUBIDA +",
            cardAvgGradeLabel = "INCL. MÉDIA",
            nextWaypointHeader = "PRÓXIMO WAYPOINT",
            noActiveRouteTitle = "Sem rota ativa",
            noActiveRouteLine1 = "Carregue uma rota para ver POIs,",
            noActiveRouteLine2 = "subidas, altimetria e ETA.",
            baseFlatPaceFormat = "Ritmo plano: %s",
            noRouteShort = "Sem rota",
            surfaceRoad = "Estrada",
            surfaceMtb = "MTB / Terra",
            surfaceMtbTech = "MTB Lama/Técnico",
            timeModeEtaAndEte = "Hora + Tempo",
            timeModeHoursMinutes = "Horas e Min (2h 15m)",
            timeModeMinutesOnly = "Só Minutos (135m)",
            rowMetricBoth = "Distância + Tempo",
            rowMetricTimeOnly = "Só Tempo (Grande)",
            rowMetricDistanceOnly = "Só Distância (Grande)",
            unitMetric = "Métrico (km, km/h)",
            unitImperial = "Imperial (mi, mph)",
            filterHeaderTitle = "← Filtrar POIs",
            categoryRouteCountFormat = "%d na rota",
        )

        val FR = EN.copy(
            langCode = "fr",
            catWater = "Eau",
            catFoodCoffee = "Café / Repas",
            catSummitClimb = "Sommet / Col",
            catAidControl = "Ravito / Contrôle",
            catDangerCaution = "Prudence",
            catBikeService = "Atelier Vélo",
            catRestCamping = "Repos / Abri",
            catFinish = "Arrivée",
            finishPointName = "Arrivée",
            climbTitleFormat = "Sommet #%d",
            climbSubtitleFormat = "Col • %.1f km à %.1f%% (+%dm)",
            flatTerrainLabel = "→ Plat",
            completedRowRight = "✓ Passé",
            passedRowSub = "(Passé)",
            backToListFormat = "← Retour liste (%ds)",
            backToListPlain = "← Retour liste",
            passedAgoCaption = "DÉPASSÉ DEPUIS",
            distanceRemainingCaption = "DISTANCE RESTANTE",
            cardPassedValue = "✓ Passé",
            cardCompletedValue = "Terminé",
            cardEstTimeLabel = "TEMPS EST.",
            cardAscentLabel = "DÉNIVELÉ +",
            cardAvgGradeLabel = "PENTE MOY.",
            nextWaypointHeader = "PROCHAIN POINT",
            noActiveRouteTitle = "Aucun parcours actif",
            noActiveRouteLine1 = "Chargez un parcours pour voir",
            noActiveRouteLine2 = "les POIs, cols et l'ETA.",
            baseFlatPaceFormat = "Allure plat : %s",
            noRouteShort = "Sans route",
            timeModeEtaAndEte = "ETA + Temps",
            timeModeHoursMinutes = "Heures & Min (2h 15m)",
            timeModeMinutesOnly = "Minutes seules (135m)",
            rowMetricBoth = "Distance + Temps",
            rowMetricTimeOnly = "Temps seul (Grand)",
            rowMetricDistanceOnly = "Distance seule (Grand)",
            unitMetric = "Métrique (km, km/h)",
            unitImperial = "Impérial (mi, mph)",
            filterHeaderTitle = "← Filtrer POIs",
            categoryRouteCountFormat = "%d sur la route",
        )

        val IT = EN.copy(
            langCode = "it",
            catWater = "Acqua",
            catFoodCoffee = "Caffè / Cibo",
            catSummitClimb = "Vetta / Salita",
            catAidControl = "Ristoro / Controllo",
            catDangerCaution = "Attenzione",
            catBikeService = "Officina Bici",
            catRestCamping = "Sosta / Rifugio",
            catFinish = "Arrivo",
            finishPointName = "Arrivo",
            climbTitleFormat = "Vetta #%d",
            climbSubtitleFormat = "Salita • %.1f km al %.1f%% (+%dm)",
            flatTerrainLabel = "→ Pianura",
            completedRowRight = "✓ Passato",
            passedRowSub = "(Passato)",
            backToListFormat = "← Torna alla lista (%ds)",
            backToListPlain = "← Torna alla lista",
            passedAgoCaption = "SUPERATO DA",
            distanceRemainingCaption = "DISTANZA RIMANENTE",
            cardPassedValue = "✓ Passato",
            cardCompletedValue = "Completato",
            cardEstTimeLabel = "TEMPO STIM.",
            cardAscentLabel = "DISLIVELLO +",
            cardAvgGradeLabel = "PEND. MEDIA",
            nextWaypointHeader = "PROSSIMO PUNTO",
            noActiveRouteTitle = "Nessun percorso attivo",
            noActiveRouteLine1 = "Carica un percorso per vedere",
            noActiveRouteLine2 = "POI, salite, dislivello ed ETA.",
            baseFlatPaceFormat = "Ritmo pianura: %s",
            noRouteShort = "No percorso",
            timeModeEtaAndEte = "ETA + Tempo",
            timeModeHoursMinutes = "Ore e Min (2h 15m)",
            timeModeMinutesOnly = "Solo Minuti (135m)",
            rowMetricBoth = "Distanza + Tempo",
            rowMetricTimeOnly = "Solo Tempo (Grande)",
            rowMetricDistanceOnly = "Solo Distanza (Grande)",
            unitMetric = "Metrico (km, km/h)",
            unitImperial = "Imperiale (mi, mph)",
            filterHeaderTitle = "← Filtra POI",
            categoryRouteCountFormat = "%d sul percorso",
        )

        val DE = EN.copy(
            langCode = "de",
            catWater = "Wasser",
            catFoodCoffee = "Kaffee / Essen",
            catSummitClimb = "Gipfel / Anstieg",
            catAidControl = "Verpflegung",
            catDangerCaution = "Vorsicht",
            catBikeService = "Werkstatt",
            catRestCamping = "Rast / Hütte",
            catFinish = "Ziel",
            finishPointName = "Ziel",
            climbTitleFormat = "Anstieg #%d",
            climbSubtitleFormat = "Anstieg • %.1f km mit %.1f%% (+%dm)",
            flatTerrainLabel = "→ Flach",
            completedRowRight = "✓ Passiert",
            passedRowSub = "(Passiert)",
            backToListFormat = "← Zurück (%ds)",
            backToListPlain = "← Zurück zur Liste",
            passedAgoCaption = "BEREITS PASSIERT",
            distanceRemainingCaption = "VERBLEIBENDE DISTANZ",
            cardPassedValue = "✓ Passiert",
            cardCompletedValue = "Erledigt",
            cardEstTimeLabel = "FAHRZEIT",
            cardAscentLabel = "ANSTIEG +",
            cardAvgGradeLabel = "Ø STEIGUNG",
            nextWaypointHeader = "NÄCHSTER WEGPUNKT",
            noActiveRouteTitle = "Keine aktive Route",
            noActiveRouteLine1 = "Route laden für Wegpunkte,",
            noActiveRouteLine2 = "Anstiege, Höhenmeter & ETA.",
            baseFlatPaceFormat = "Flachtempo: %s",
            noRouteShort = "Keine Route",
            timeModeEtaAndEte = "ETA + Zeit",
            timeModeHoursMinutes = "Std. & Min. (2h 15m)",
            timeModeMinutesOnly = "Nur Minuten (135m)",
            rowMetricBoth = "Distanz + Zeit",
            rowMetricTimeOnly = "Nur Zeit (Groß)",
            rowMetricDistanceOnly = "Nur Distanz (Groß)",
            unitMetric = "Metrisch (km, km/h)",
            unitImperial = "Imperial (mi, mph)",
            filterHeaderTitle = "← POIs filtern",
            categoryRouteCountFormat = "%d auf Route",
        )

        fun forLocale(locale: Locale = Locale.getDefault()): CueStrings {
            return when (locale.language.lowercase(Locale.ROOT)) {
                "es" -> ES
                "pt" -> PT
                "fr" -> FR
                "it" -> IT
                "de" -> DE
                else -> EN // Universal English fallback
            }
        }
    }
}
