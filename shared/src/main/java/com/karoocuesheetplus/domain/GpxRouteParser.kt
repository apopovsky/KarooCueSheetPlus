package com.karoocuesheetplus.domain

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import javax.xml.parsers.SAXParserFactory

data class ParsedGpxRoute(
    val routeName: String,
    val indexedPolyline: RouteGeometryEngine.IndexedPolyline,
    val elevationProfile: List<ElevationSample>,
    val routePois: List<RawPoiInput>,
    val climbs: List<ClimbSegment>,
) {
    val totalDistanceM: Double get() = indexedPolyline.totalDistanceM
}

object GpxRouteParser {

    private const val MULTI_PASS_CORRIDOR_M = 65.0
    private const val MULTI_PASS_MIN_SEPARATION_M = 1_200.0
    private const val MAX_WPT_SNAP_CORRIDOR_M = 800.0

    fun parse(inputStream: InputStream, fallbackName: String = "Ruta GPX"): ParsedGpxRoute {
        val raw = parseXml(inputStream)
        val trackPoints = if (raw.trkPoints.isNotEmpty()) raw.trkPoints else raw.rtePoints
        val latLngs = trackPoints.map { LatLngPoint(it.lat, it.lng) }
        val indexed = RouteGeometryEngine.buildIndexedPolyline(latLngs)

        val elevationProfile = buildSmoothedElevationProfile(trackPoints, indexed.cumulativeDistM)
        val climbs = detectClimbs(elevationProfile)

        val pois = raw.waypoints.mapIndexedNotNull { index, wpt ->
            val passDistances = findDistancesAlongRoute(indexed, LatLngPoint(wpt.lat, wpt.lng))
            if (passDistances.isEmpty()) {
                null
            } else {
                RawPoiInput(
                    id = "gpx_wpt_$index",
                    lat = wpt.lat,
                    lng = wpt.lng,
                    type = wpt.type.ifBlank { wpt.sym },
                    name = wpt.name.ifBlank { wpt.desc.takeIf { it.isNotBlank() } },
                    distancesAlongRouteM = passDistances,
                    isGlobal = false,
                )
            }
        }

        val resolvedName = raw.metadataName.ifBlank {
            raw.trackName.ifBlank { fallbackName }
        }

        return ParsedGpxRoute(
            routeName = resolvedName,
            indexedPolyline = indexed,
            elevationProfile = elevationProfile,
            routePois = pois,
            climbs = climbs,
        )
    }

    /**
     * Computes instantaneous terrain slope (%) around [distanceM] using a ~120 m window.
     */
    fun gradeAtDistance(profile: List<ElevationSample>, distanceM: Double): Double {
        if (profile.size < 2) return 0.0
        val total = profile.last().distanceM
        if (total <= 10.0) return 0.0
        val d0 = (distanceM - 60.0).coerceIn(0.0, total)
        val d1 = (distanceM + 60.0).coerceIn(0.0, total)
        val span = d1 - d0
        if (span < 10.0) return 0.0
        val e0 = GradientEtaCalculator.interpolateElevationAt(profile, d0)
        val e1 = GradientEtaCalculator.interpolateElevationAt(profile, d1)
        return (((e1 - e0) / span) * 100.0).coerceIn(-25.0, 25.0)
    }

    private fun findDistancesAlongRoute(
        indexed: RouteGeometryEngine.IndexedPolyline,
        target: LatLngPoint,
    ): List<Double> {
        val pts = indexed.points
        if (pts.size < 2) return emptyList()

        val earthRadiusM = 6_371_000.0
        val cosLat = cos(Math.toRadians(target.lat)).coerceAtLeast(0.05)

        data class Hit(val alongM: Double, val crossM: Double)

        val corridorHits = ArrayList<Hit>()
        var globalBestCross = Double.MAX_VALUE
        var globalBestAlong = 0.0

        for (i in 0 until pts.lastIndex) {
            val a = pts[i]
            val b = pts[i + 1]

            val ax = Math.toRadians(a.lng - target.lng) * cosLat * earthRadiusM
            val ay = Math.toRadians(a.lat - target.lat) * earthRadiusM
            val bx = Math.toRadians(b.lng - target.lng) * cosLat * earthRadiusM
            val by = Math.toRadians(b.lat - target.lat) * earthRadiusM

            val dx = bx - ax
            val dy = by - ay
            val segLenSq = dx * dx + dy * dy
            val t = if (segLenSq <= 1e-6) 0.0 else ((-ax * dx - ay * dy) / segLenSq).coerceIn(0.0, 1.0)

            val projX = ax + t * dx
            val projY = ay + t * dy
            val crossM = hypot(projX, projY)
            val alongM = indexed.cumulativeDistM[i] + t * (indexed.cumulativeDistM[i + 1] - indexed.cumulativeDistM[i])

            if (crossM < globalBestCross) {
                globalBestCross = crossM
                globalBestAlong = alongM
            }
            if (crossM <= MULTI_PASS_CORRIDOR_M) {
                corridorHits.add(Hit(alongM, crossM))
            }
        }

        if (corridorHits.isNotEmpty()) {
            // Cluster hits separated by at least MULTI_PASS_MIN_SEPARATION_M (supports out-and-back / loops)
            val merged = ArrayList<Hit>()
            for (hit in corridorHits) {
                val last = merged.lastOrNull()
                if (last == null || hit.alongM - last.alongM > MULTI_PASS_MIN_SEPARATION_M) {
                    merged.add(hit)
                } else if (hit.crossM < last.crossM) {
                    merged[merged.lastIndex] = hit
                }
            }
            return merged.map { it.alongM }
        }

        return if (globalBestCross <= MAX_WPT_SNAP_CORRIDOR_M) {
            listOf(globalBestAlong)
        } else {
            emptyList()
        }
    }

    private fun buildSmoothedElevationProfile(
        points: List<RawTrkPt>,
        cumulativeDistM: DoubleArray,
    ): List<ElevationSample> {
        if (points.isEmpty() || cumulativeDistM.size != points.size) return emptyList()
        val hasElevation = points.any { it.ele != null }
        if (!hasElevation) return emptyList()

        // Resample at uniform ~40m intervals so quantized 1m GPX elevations produce smooth realistic grades
        val rawSamples = ArrayList<ElevationSample>(points.size)
        var lastEle = points.firstOrNull { it.ele != null }?.ele ?: 0.0
        for (i in points.indices) {
            val e = points[i].ele ?: lastEle
            lastEle = e
            if (rawSamples.isEmpty() || cumulativeDistM[i] - rawSamples.last().distanceM >= 25.0 || i == points.lastIndex) {
                rawSamples.add(ElevationSample(cumulativeDistM[i], e))
            }
        }

        if (rawSamples.size < 5) return rawSamples

        // 5-point triangular smoothing kernel [1, 2, 3, 2, 1] / 9 to remove integer DEM steps while preserving peaks
        val smoothed = ArrayList<ElevationSample>(rawSamples.size)
        for (i in rawSamples.indices) {
            val eM2 = rawSamples[(i - 2).coerceAtLeast(0)].elevationM
            val eM1 = rawSamples[(i - 1).coerceAtLeast(0)].elevationM
            val e0 = rawSamples[i].elevationM
            val eP1 = rawSamples[(i + 1).coerceAtMost(rawSamples.lastIndex)].elevationM
            val eP2 = rawSamples[(i + 2).coerceAtMost(rawSamples.lastIndex)].elevationM
            val avg = (eM2 + 2.0 * eM1 + 3.0 * e0 + 2.0 * eP1 + eP2) / 9.0
            smoothed.add(ElevationSample(rawSamples[i].distanceM, avg))
        }
        return smoothed
    }

    /**
     * Detects sustained climbs along the elevation profile similar to Hammerhead Climber:
     * - Minimum climb gain: 65 m
     * - Minimum length: 800 m
     * - Minimum average grade: 2.8%
     * - Bridges short false-flat/dip interruptions up to 22 m drop or 450 m length
     */
    internal fun detectClimbs(profile: List<ElevationSample>): List<ClimbSegment> {
        if (profile.size < 10) return emptyList()
        val totalDist = profile.last().distanceM
        if (totalDist < 1_000.0) return emptyList()

        val stepM = 100.0
        val count = (totalDist / stepM).toInt() + 1
        val dists = DoubleArray(count) { (it * stepM).coerceAtMost(totalDist) }
        val elevs = DoubleArray(count) { GradientEtaCalculator.interpolateElevationAt(profile, dists[it]) }

        val climbs = ArrayList<ClimbSegment>()
        var i = 0
        while (i < count - 5) {
            // Look for start of an upward gradient (>= 2.0% over 200m)
            val lookAhead = (i + 2).coerceAtMost(count - 1)
            val initSpan = dists[lookAhead] - dists[i]
            val initGain = elevs[lookAhead] - elevs[i]
            if (initSpan <= 1.0 || (initGain / initSpan) < 0.02) {
                i++
                continue
            }

            val startIdx = i
            var peakIdx = lookAhead
            var peakElev = elevs[lookAhead]
            var j = lookAhead + 1

            while (j < count) {
                val e = elevs[j]
                if (e >= peakElev) {
                    peakElev = e
                    peakIdx = j
                } else {
                    val dropFromPeak = peakElev - e
                    val distSincePeak = dists[j] - dists[peakIdx]
                    // End climb if rider descends more than 24m OR doesn't regain peak within 600m
                    if (dropFromPeak > 24.0 || distSincePeak > 600.0) {
                        break
                    }
                }
                j++
            }

            val lengthM = dists[peakIdx] - dists[startIdx]
            val netGainM = elevs[peakIdx] - elevs[startIdx]
            var positiveAscentM = 0.0
            for (k in (startIdx + 1)..peakIdx) {
                val diff = elevs[k] - elevs[k - 1]
                if (diff > 0.0) positiveAscentM += diff
            }
            val avgGradePct = if (lengthM > 1.0) (netGainM / lengthM) * 100.0 else 0.0

            if (lengthM >= 800.0 && netGainM >= 65.0 && avgGradePct >= 2.8) {
                climbs.add(
                    ClimbSegment(
                        startDistanceM = dists[startIdx],
                        lengthM = lengthM,
                        gradePct = avgGradePct,
                        totalElevationM = max(netGainM, positiveAscentM),
                    )
                )
                i = peakIdx + 2
            } else {
                i++
            }
        }

        return climbs
    }

    private data class RawWpt(
        val lat: Double,
        val lng: Double,
        var name: String = "",
        var type: String = "",
        var sym: String = "",
        var desc: String = "",
    )

    private data class RawTrkPt(
        val lat: Double,
        val lng: Double,
        var ele: Double? = null,
    )

    private class RawGpxData(
        var metadataName: String = "",
        var trackName: String = "",
        val waypoints: MutableList<RawWpt> = ArrayList(),
        val trkPoints: MutableList<RawTrkPt> = ArrayList(),
        val rtePoints: MutableList<RawTrkPt> = ArrayList(),
    )

    private fun parseXml(inputStream: InputStream): RawGpxData {
        val result = RawGpxData()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val parser = factory.newSAXParser()

        val handler = object : DefaultHandler() {
            private val tagStack = ArrayDeque<String>()
            private val textBuf = StringBuilder()

            private var currentWpt: RawWpt? = null
            private var currentTrkPt: RawTrkPt? = null
            private var currentRtePt: RawTrkPt? = null

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                val tag = (localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()).lowercase()
                tagStack.addLast(tag)
                textBuf.setLength(0)

                when (tag) {
                    "wpt" -> {
                        val lat = attributes.getValue("lat")?.toDoubleOrNull()
                        val lon = attributes.getValue("lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            currentWpt = RawWpt(lat, lon)
                        }
                    }
                    "trkpt" -> {
                        val lat = attributes.getValue("lat")?.toDoubleOrNull()
                        val lon = attributes.getValue("lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            currentTrkPt = RawTrkPt(lat, lon)
                        }
                    }
                    "rtept" -> {
                        val lat = attributes.getValue("lat")?.toDoubleOrNull()
                        val lon = attributes.getValue("lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            currentRtePt = RawTrkPt(lat, lon)
                        }
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                textBuf.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val tag = (localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()).lowercase()
                val text = textBuf.toString().trim()
                val parent = if (tagStack.size >= 2) tagStack.elementAt(tagStack.size - 2) else ""

                when {
                    currentWpt != null -> {
                        val w = currentWpt!!
                        when (tag) {
                            "name" -> w.name = text
                            "type" -> w.type = text
                            "sym" -> w.sym = text
                            "desc", "cmt" -> if (w.desc.isEmpty()) w.desc = text
                            "wpt" -> {
                                result.waypoints.add(w)
                                currentWpt = null
                            }
                        }
                    }
                    currentTrkPt != null -> {
                        val pt = currentTrkPt!!
                        when (tag) {
                            "ele" -> pt.ele = text.toDoubleOrNull()
                            "trkpt" -> {
                                result.trkPoints.add(pt)
                                currentTrkPt = null
                            }
                        }
                    }
                    currentRtePt != null -> {
                        val pt = currentRtePt!!
                        when (tag) {
                            "ele" -> pt.ele = text.toDoubleOrNull()
                            "rtept" -> {
                                result.rtePoints.add(pt)
                                currentRtePt = null
                            }
                        }
                    }
                    tag == "name" && parent == "metadata" -> {
                        if (result.metadataName.isEmpty()) result.metadataName = text
                    }
                    tag == "name" && (parent == "trk" || parent == "rte") -> {
                        if (result.trackName.isEmpty()) result.trackName = text
                    }
                }

                if (tagStack.isNotEmpty()) {
                    tagStack.removeLast()
                }
                textBuf.setLength(0)
            }
        }

        parser.parse(InputSource(InputStreamReader(inputStream, Charsets.UTF_8)), handler)
        return result
    }
}
