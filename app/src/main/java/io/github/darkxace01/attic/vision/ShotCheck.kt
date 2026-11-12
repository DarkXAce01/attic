package io.github.darkxace01.attic.vision

import kotlin.math.hypot

/**
 * Catches a full-resolution shot that doesn't look like what the camera was showing: on some phones the
 * first shot after the camera starts comes out before its colour balance has settled (typically green),
 * or nearly black. The live frames are right by then, so the shot is compared with the latest one.
 */
object ShotCheck {
    /** How far apart the colour balances may be (distance between r and g shares of the total). */
    const val MAX_CAST = 0.06

    /** The shot may be this much darker or lighter than the live frame before it's taken to be wrong. */
    const val MAX_BRIGHTNESS_RATIO = 2.5

    /** True when [shot] looks like a different picture from [live]; both are mean R, G, B (0..255). */
    fun looksWrong(live: DoubleArray, shot: DoubleArray): Boolean {
        val ls = live[0] + live[1] + live[2]
        val ss = shot[0] + shot[1] + shot[2]
        // Too dark to judge a colour from: only a frame that is black when the view isn't counts.
        if (ls < 30) return false
        if (ss < 1) return true
        val ratio = ls / ss
        if (ratio > MAX_BRIGHTNESS_RATIO || ratio < 1 / MAX_BRIGHTNESS_RATIO) return true
        val dr = live[0] / ls - shot[0] / ss
        val dg = live[1] / ls - shot[1] / ss
        return hypot(dr, dg) > MAX_CAST
    }
}
