package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.ShotCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotCheckTest {
    private val live = doubleArrayOf(150.0, 140.0, 120.0)

    @Test fun sameSceneSlightlyBrighterIsFine() =
        assertFalse(ShotCheck.looksWrong(live, doubleArrayOf(170.0, 160.0, 140.0)))

    @Test fun smallWhiteBalanceShiftIsFine() =
        assertFalse(ShotCheck.looksWrong(live, doubleArrayOf(145.0, 140.0, 128.0)))

    @Test fun greenShotIsCaught() =
        assertTrue(ShotCheck.looksWrong(live, doubleArrayOf(70.0, 160.0, 60.0)))

    @Test fun blackShotIsCaught() =
        assertTrue(ShotCheck.looksWrong(live, doubleArrayOf(8.0, 9.0, 7.0)))

    @Test fun darkViewIsNotJudged() =
        assertFalse(ShotCheck.looksWrong(doubleArrayOf(5.0, 6.0, 5.0), doubleArrayOf(2.0, 9.0, 2.0)))
}
