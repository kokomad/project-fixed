package com.elephant.action3d

import org.junit.Assert.assertEquals
import org.junit.Test

class GameLogicTest {

    @Test
    fun testEnemyCountCalculation() {
        val game = Action3DGame()
        assertEquals(7, game.calculateEnemyCount(1))
        assertEquals(10, game.calculateEnemyCount(2))
        assertEquals(19, game.calculateEnemyCount(5))
    }
}
