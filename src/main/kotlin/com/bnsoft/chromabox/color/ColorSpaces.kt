package com.bnsoft.chromabox.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Hue in degrees [0, 360), or NaN when achromatic; saturation and lightness 0..1. */
data class Hsl(val h: Double, val s: Double, val l: Double)

/** Hue in degrees [0, 360), or NaN when achromatic; whiteness and blackness 0..1. */
data class Hwb(val h: Double, val w: Double, val b: Double)

/** CIE Lab (D50): lightness 0..100. */
data class Lab(val l: Double, val a: Double, val b: Double)

/** CIE LCH (D50): hue in degrees [0, 360), or NaN when achromatic. */
data class Lch(val l: Double, val c: Double, val h: Double)

/** OKLab: lightness 0..1. */
data class Oklab(val l: Double, val a: Double, val b: Double)

/** OKLCH: lightness 0..1; hue in degrees [0, 360), or NaN when achromatic. */
data class Oklch(val l: Double, val c: Double, val h: Double)

/** Gamma-encoded RGB in a non-sRGB space (display-p3); channels nominally 0..1, not clamped. */
data class Rgb(val r: Double, val g: Double, val b: Double)

/**
 * Conversions between [ChromaColor] and other CSS color spaces, following the CSS Color 4 sample code.
 *
 * `toX` functions drop alpha (read it from the [ChromaColor]); `fromX` functions take it as a parameter.
 * Hues come back as `Double.NaN` for achromatic colors so callers can keep the hue the user had;
 * a NaN hue passed to `fromX` is treated as 0.
 */
object ColorSpaces {
    private const val HUE_EPSILON = 1e-6
    private const val CHROMA_EPSILON = 1e-4

    // region sRGB transfer

    /** sRGB gamma-encoded channel to linear light; extended to negative values by symmetry. */
    fun srgbToLinear(c: Double): Double {
        val a = abs(c)
        return if (a <= 0.04045) c / 12.92 else sign(c) * ((a + 0.055) / 1.055).pow(2.4)
    }

    /** Linear-light channel to sRGB gamma-encoded; extended to negative values by symmetry. */
    fun linearToSrgb(c: Double): Double {
        val a = abs(c)
        return if (a > 0.0031308) sign(c) * (1.055 * a.pow(1 / 2.4) - 0.055) else 12.92 * c
    }

    // endregion

    // region HSL / HWB

    fun toHsl(color: ChromaColor): Hsl {
        val (r, g, b) = color
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val l = (min + max) / 2
        if (abs(d) < HUE_EPSILON) return Hsl(Double.NaN, 0.0, l)
        var s = if (l == 0.0 || l == 1.0) 0.0 else (max - l) / min(l, 1 - l)
        var h = when (max) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        // Out-of-gamut input can yield negative saturation; flip the hue instead.
        if (s < 0) {
            h += 180
            s = -s
        }
        return Hsl(normalizeHue(h), s, l)
    }

    fun fromHsl(hsl: Hsl, alpha: Double = 1.0): ChromaColor {
        val h = hueOrZero(hsl.h)
        val a = hsl.s * min(hsl.l, 1 - hsl.l)
        fun f(n: Int): Double {
            val k = (n + h / 30) % 12
            return hsl.l - a * max(-1.0, minOf(k - 3, 9 - k, 1.0))
        }
        return ChromaColor(f(0), f(8), f(4), alpha)
    }

    fun toHwb(color: ChromaColor): Hwb {
        val white = minOf(color.r, color.g, color.b)
        val black = 1 - maxOf(color.r, color.g, color.b)
        val h = if (white + black >= 1 - HUE_EPSILON) Double.NaN else toHsl(color).h
        return Hwb(h, white, black)
    }

    fun fromHwb(hwb: Hwb, alpha: Double = 1.0): ChromaColor {
        val sum = hwb.w + hwb.b
        if (sum >= 1) {
            val gray = hwb.w / sum
            return ChromaColor(gray, gray, gray, alpha)
        }
        val pure = fromHsl(Hsl(hwb.h, 1.0, 0.5))
        fun mix(c: Double) = c * (1 - sum) + hwb.w
        return ChromaColor(mix(pure.r), mix(pure.g), mix(pure.b), alpha)
    }

    // endregion

    // region CIE Lab / LCH

    fun toLab(color: ChromaColor): Lab {
        val (x, y, z) = mul(D65_TO_D50, srgbToXyz(color))
        val f = doubleArrayOf(x / D50_WHITE[0], y / D50_WHITE[1], z / D50_WHITE[2]).map {
            if (it > LAB_EPSILON) cbrt(it) else (LAB_KAPPA * it + 16) / 116
        }
        return Lab(116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2]))
    }

    fun fromLab(lab: Lab, alpha: Double = 1.0): ChromaColor {
        val f1 = (lab.l + 16) / 116
        val f0 = lab.a / 500 + f1
        val f2 = f1 - lab.b / 200
        val x = if (f0.pow(3) > LAB_EPSILON) f0.pow(3) else (116 * f0 - 16) / LAB_KAPPA
        val y = if (lab.l > LAB_KAPPA * LAB_EPSILON) f1.pow(3) else lab.l / LAB_KAPPA
        val z = if (f2.pow(3) > LAB_EPSILON) f2.pow(3) else (116 * f2 - 16) / LAB_KAPPA
        val xyzD50 = doubleArrayOf(x * D50_WHITE[0], y * D50_WHITE[1], z * D50_WHITE[2])
        return xyzToSrgb(mul(D50_TO_D65, xyzD50), alpha)
    }

    fun toLch(color: ChromaColor): Lch {
        val lab = toLab(color)
        val (c, h) = toPolar(lab.a, lab.b)
        return Lch(lab.l, c, h)
    }

    fun fromLch(lch: Lch, alpha: Double = 1.0): ChromaColor {
        val (a, b) = fromPolar(lch.c, lch.h)
        return fromLab(Lab(lch.l, a, b), alpha)
    }

    // endregion

    // region OKLab / OKLCH

    fun toOklab(color: ChromaColor): Oklab {
        val lms = mul(XYZ_TO_LMS, srgbToXyz(color)).map { cbrt(it) }.toDoubleArray()
        val (l, a, b) = mul(LMS_TO_OKLAB, lms)
        return Oklab(l, a, b)
    }

    fun fromOklab(lab: Oklab, alpha: Double = 1.0): ChromaColor {
        val lms = mul(OKLAB_TO_LMS, doubleArrayOf(lab.l, lab.a, lab.b)).map { it * it * it }.toDoubleArray()
        return xyzToSrgb(mul(LMS_TO_XYZ, lms), alpha)
    }

    fun toOklch(color: ChromaColor): Oklch {
        val lab = toOklab(color)
        val (c, h) = toPolar(lab.a, lab.b)
        return Oklch(lab.l, c, h)
    }

    fun fromOklch(lch: Oklch, alpha: Double = 1.0): ChromaColor {
        val (a, b) = fromPolar(lch.c, lch.h)
        return fromOklab(Oklab(lch.l, a, b), alpha)
    }

    // endregion

    // region display-p3

    fun toDisplayP3(color: ChromaColor): Rgb {
        val (r, g, b) = mul(XYZ_TO_LINEAR_P3, srgbToXyz(color)).map { linearToSrgb(it) }
        return Rgb(r, g, b)
    }

    fun fromDisplayP3(p3: Rgb, alpha: Double = 1.0): ChromaColor {
        val linear = doubleArrayOf(srgbToLinear(p3.r), srgbToLinear(p3.g), srgbToLinear(p3.b))
        return xyzToSrgb(mul(LINEAR_P3_TO_XYZ, linear), alpha)
    }

    // endregion

    // region helpers

    /** Gamma-encoded sRGB to CIE XYZ (D65). */
    private fun srgbToXyz(color: ChromaColor) = mul(
        LINEAR_SRGB_TO_XYZ,
        doubleArrayOf(srgbToLinear(color.r), srgbToLinear(color.g), srgbToLinear(color.b)),
    )

    private fun xyzToSrgb(xyz: DoubleArray, alpha: Double): ChromaColor {
        val (r, g, b) = mul(XYZ_TO_LINEAR_SRGB, xyz).map { linearToSrgb(it) }
        return ChromaColor(r, g, b, alpha)
    }

    private fun toPolar(a: Double, b: Double): Pair<Double, Double> {
        val c = hypot(a, b)
        val h = if (c < CHROMA_EPSILON) Double.NaN else normalizeHue(Math.toDegrees(atan2(b, a)))
        return c to h
    }

    private fun fromPolar(c: Double, h: Double): Pair<Double, Double> {
        val rad = Math.toRadians(hueOrZero(h))
        return c * cos(rad) to c * sin(rad)
    }

    private fun normalizeHue(h: Double): Double {
        val n = h % 360
        return if (n < 0) n + 360 else n
    }

    private fun hueOrZero(h: Double) = if (h.isNaN()) 0.0 else normalizeHue(h)

    private fun mul(m: Array<DoubleArray>, v: DoubleArray) = DoubleArray(3) { i ->
        m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2]
    }

    // endregion

    // region constants (CSS Color 4 §18 sample code)

    private const val LAB_EPSILON = 216.0 / 24389
    private const val LAB_KAPPA = 24389.0 / 27

    private val D50_WHITE = doubleArrayOf(0.3457 / 0.3585, 1.0, (1.0 - 0.3457 - 0.3585) / 0.3585)

    private val LINEAR_SRGB_TO_XYZ = arrayOf(
        doubleArrayOf(506752.0 / 1228815, 87881.0 / 245763, 12673.0 / 70218),
        doubleArrayOf(87098.0 / 409605, 175762.0 / 245763, 12673.0 / 175545),
        doubleArrayOf(7918.0 / 409605, 87881.0 / 737289, 1001167.0 / 1053270),
    )

    private val XYZ_TO_LINEAR_SRGB = arrayOf(
        doubleArrayOf(12831.0 / 3959, -329.0 / 214, -1974.0 / 3959),
        doubleArrayOf(-851781.0 / 878810, 1648619.0 / 878810, 36519.0 / 878810),
        doubleArrayOf(705.0 / 12673, -2585.0 / 12673, 705.0 / 667),
    )

    private val LINEAR_P3_TO_XYZ = arrayOf(
        doubleArrayOf(608311.0 / 1250200, 189793.0 / 714400, 198249.0 / 1000160),
        doubleArrayOf(35783.0 / 156275, 247089.0 / 357200, 198249.0 / 2500400),
        doubleArrayOf(0.0, 32229.0 / 714400, 5220557.0 / 5000800),
    )

    private val XYZ_TO_LINEAR_P3 = arrayOf(
        doubleArrayOf(446124.0 / 178915, -333277.0 / 357830, -72051.0 / 178915),
        doubleArrayOf(-14852.0 / 17905, 63121.0 / 35810, 423.0 / 17905),
        doubleArrayOf(11844.0 / 330415, -50337.0 / 660830, 316169.0 / 330415),
    )

    // Bradford chromatic adaptation.
    private val D65_TO_D50 = arrayOf(
        doubleArrayOf(1.0479297925449969, 0.022946870601609652, -0.05019226628920524),
        doubleArrayOf(0.02962780877005599, 0.9904344267538799, -0.017073799063418826),
        doubleArrayOf(-0.009243040646204504, 0.015055191490298152, 0.7518742814281371),
    )

    private val D50_TO_D65 = arrayOf(
        doubleArrayOf(0.955473421488075, -0.02309845494876471, 0.06325924320057072),
        doubleArrayOf(-0.0283697093338637, 1.0099953980813041, 0.021041441191917323),
        doubleArrayOf(0.012314014864481998, -0.020507649298898964, 1.330365926242124),
    )

    private val XYZ_TO_LMS = arrayOf(
        doubleArrayOf(0.8190224379967030, 0.3619062600528904, -0.1288737815209879),
        doubleArrayOf(0.0329836539323885, 0.9292868615863434, 0.0361446663506424),
        doubleArrayOf(0.0481771893596242, 0.2642395317527308, 0.6335478284694309),
    )

    private val LMS_TO_OKLAB = arrayOf(
        doubleArrayOf(0.2104542683093140, 0.7936177747023054, -0.0040720430116193),
        doubleArrayOf(1.9779985324311684, -2.4285922420485799, 0.4505937096174110),
        doubleArrayOf(0.0259040424655478, 0.7827717124575296, -0.8086757549230774),
    )

    private val OKLAB_TO_LMS = arrayOf(
        doubleArrayOf(1.0, 0.3963377773761749, 0.2158037573099136),
        doubleArrayOf(1.0, -0.1055613458156586, -0.0638541728258133),
        doubleArrayOf(1.0, -0.0894841775298119, -1.2914855480194092),
    )

    private val LMS_TO_XYZ = arrayOf(
        doubleArrayOf(1.2268798758459243, -0.5578149944602171, 0.2813910456659647),
        doubleArrayOf(-0.0405757452148008, 1.1122868032803170, -0.0717110580655164),
        doubleArrayOf(-0.0763729366746601, -0.4214933324022432, 1.5869240198367816),
    )

    // endregion
}
