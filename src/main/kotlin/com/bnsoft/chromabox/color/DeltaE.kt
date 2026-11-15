package com.bnsoft.chromabox.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** How different two colors look (C-03.4). */
object DeltaE {
    /** CIEDE2000 of two colors, in CIE Lab (D50, as CSS Color 4 uses); alpha is ignored. Under 1 is barely visible. */
    fun ciede2000(a: ChromaColor, b: ChromaColor): Double = ciede2000(ColorSpaces.toLab(a), ColorSpaces.toLab(b))

    /** CIEDE2000 (Sharma, Wu and Dalal, 2005), with kL = kC = kH = 1. */
    fun ciede2000(lab1: Lab, lab2: Lab): Double {
        val c1 = hypot(lab1.a, lab1.b)
        val c2 = hypot(lab2.a, lab2.b)
        val cMean7 = ((c1 + c2) / 2).pow(7)
        val g = 0.5 * (1 - sqrt(cMean7 / (cMean7 + POW_25_7)))
        val a1 = (1 + g) * lab1.a
        val a2 = (1 + g) * lab2.a
        val cp1 = hypot(a1, lab1.b)
        val cp2 = hypot(a2, lab2.b)
        val hp1 = hue(lab1.b, a1)
        val hp2 = hue(lab2.b, a2)

        val dL = lab2.l - lab1.l
        val dC = cp2 - cp1
        val dhp = when {
            cp1 * cp2 == 0.0 -> 0.0
            abs(hp2 - hp1) <= 180 -> hp2 - hp1
            hp2 - hp1 > 180 -> hp2 - hp1 - 360
            else -> hp2 - hp1 + 360
        }
        val dH = 2 * sqrt(cp1 * cp2) * sin(Math.toRadians(dhp / 2))

        val lMean = (lab1.l + lab2.l) / 2
        val cpMean = (cp1 + cp2) / 2
        val hpMean = when {
            cp1 * cp2 == 0.0 -> hp1 + hp2
            abs(hp1 - hp2) <= 180 -> (hp1 + hp2) / 2
            hp1 + hp2 < 360 -> (hp1 + hp2 + 360) / 2
            else -> (hp1 + hp2 - 360) / 2
        }
        val t = 1 - 0.17 * cos(Math.toRadians(hpMean - 30)) + 0.24 * cos(Math.toRadians(2 * hpMean)) +
            0.32 * cos(Math.toRadians(3 * hpMean + 6)) - 0.20 * cos(Math.toRadians(4 * hpMean - 63))
        val dTheta = 30 * exp(-((hpMean - 275) / 25).pow(2))
        val cpMean7 = cpMean.pow(7)
        val rC = 2 * sqrt(cpMean7 / (cpMean7 + POW_25_7))
        val lMean50 = (lMean - 50).pow(2)
        val sL = 1 + 0.015 * lMean50 / sqrt(20 + lMean50)
        val sC = 1 + 0.045 * cpMean
        val sH = 1 + 0.015 * cpMean * t
        val rT = -sin(Math.toRadians(2 * dTheta)) * rC

        val l = dL / sL
        val c = dC / sC
        val h = dH / sH
        return sqrt(l * l + c * c + h * h + rT * c * h)
    }

    private fun hue(b: Double, a: Double): Double {
        if (a == 0.0 && b == 0.0) return 0.0
        val degrees = Math.toDegrees(atan2(b, a))
        return if (degrees < 0) degrees + 360 else degrees
    }

    private val POW_25_7 = 25.0.pow(7)
}
