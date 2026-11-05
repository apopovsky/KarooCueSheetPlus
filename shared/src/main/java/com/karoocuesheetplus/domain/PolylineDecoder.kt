package com.karoocuesheetplus.domain

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Decoder and encoder for Google Encoded Polylines used by Hammerhead Karoo (`karoo-ext`):
 * - `routePolyline`: `(lat, lng)` encoded at precision 5 (`1e5`).
 * - `routeElevationPolyline`: `(distanceM, elevationM)` encoded at precision 1 (`1e1`).
 */
object PolylineDecoder {

    fun decodeLatLng(encoded: String?, precision: Int = 5): List<LatLngPoint> {
        if (encoded.isNullOrBlank()) return emptyList()
        val pairs = decodeRawPairs(encoded, precision)
        return pairs.map { (lat, lng) -> LatLngPoint(lat = lat, lng = lng) }
    }

    fun decodeElevation(encoded: String?, precision: Int = 1): List<ElevationSample> {
        if (encoded.isNullOrBlank()) return emptyList()
        val pairs = decodeRawPairs(encoded, precision)
        if (pairs.isEmpty()) return emptyList()

        val result = ArrayList<ElevationSample>(pairs.size)
        var lastDist = -1.0
        for ((dist, elev) in pairs) {
            val cleanDist = dist.coerceAtLeast(if (lastDist < 0.0) 0.0 else lastDist)
            if (result.isEmpty() || cleanDist - lastDist >= 0.5) {
                result.add(ElevationSample(distanceM = cleanDist, elevationM = elev))
                lastDist = cleanDist
            }
        }
        return result
    }

    fun encodePairs(pairs: List<Pair<Double, Double>>, precision: Int): String {
        if (pairs.isEmpty()) return ""
        val factor = 10.0.pow(precision)
        val sb = StringBuilder()
        var prevFirst = 0
        var prevSecond = 0
        for ((first, second) in pairs) {
            val iFirst = (first * factor).roundToInt()
            val iSecond = (second * factor).roundToInt()
            encodeSignedValue(iFirst - prevFirst, sb)
            encodeSignedValue(iSecond - prevSecond, sb)
            prevFirst = iFirst
            prevSecond = iSecond
        }
        return sb.toString()
    }

    private fun decodeRawPairs(encoded: String, precision: Int): List<Pair<Double, Double>> {
        val factor = 10.0.pow(precision)
        val result = ArrayList<Pair<Double, Double>>()
        var index = 0
        val len = encoded.length
        var first = 0
        var second = 0

        try {
            while (index < len) {
                val r1 = decodeNextInt(encoded, index) ?: break
                index = r1.second
                val r2 = decodeNextInt(encoded, index) ?: break
                index = r2.second

                first += r1.first
                second += r2.first
                result.add(Pair(first / factor, second / factor))
            }
        } catch (_: Exception) {
            // Return valid prefix if trailing characters are malformed
        }
        return result
    }

    private fun decodeNextInt(encoded: String, startIndex: Int): Pair<Int, Int>? {
        var index = startIndex
        val len = encoded.length
        var result = 0
        var shift = 0
        var b: Int
        do {
            if (index >= len) return null
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        val delta = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
        return Pair(delta, index)
    }

    private fun encodeSignedValue(value: Int, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toChar())
    }
}
