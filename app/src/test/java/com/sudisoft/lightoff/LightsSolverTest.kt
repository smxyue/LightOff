package com.sudisoft.lightoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * LightsSolver 单元测试（JVM，无 Android 依赖）。
 */
class LightsSolverTest {

    /** 由随机按键打乱出的局面必然可解，且解按完可以全灭 */
    @Test
    fun scrambledBoardsAreSolvableAndSolutionsClear() {
        for (n in 2..24) {
            val N = n * n
            for (trial in 0 until 5) {
                val lights = BooleanArray(N)
                val presses = 2 * n + Random.nextInt(2 * n + 1)
                repeat(presses) { LightsSolver.applyPress(lights, n, Random.nextInt(N)) }
                assertTrue("n=$n 按键打乱局面应可解", LightsSolver.isSolvableFast(n, lights))
                val sol = LightsSolver.solve(n, lights)
                assertNotNull("n=$n 应返回解", sol)
                if (LightsSolver.litCount(lights) == 0) {
                    assertEquals("全灭棋盘应为空解", 0, sol!!.size)
                    continue
                }
                assertTrue("n=$n 应返回非空解", sol!!.isNotEmpty())
                val work = lights.copyOf()
                for (k in sol) LightsSolver.applyPress(work, n, k)
                assertTrue("n=$n 按完解应全灭", LightsSolver.isCleared(work))
            }
        }
    }

    /** 点击翻转只影响自身与上下左右，不含对角 */
    @Test
    fun pressTogglesSelfAndOrthogonalNeighborsOnly() {
        val n = 8
        val lights = BooleanArray(n * n)
        LightsSolver.applyPress(lights, n, 27) // 第 4 行第 4 列
        val expected = setOf(27, 19, 35, 26, 28)
        for (i in lights.indices) {
            assertEquals("格子 $i 状态", i in expected, lights[i])
        }
        assertFalse(lights[18]); assertFalse(lights[36])
        assertFalse(lights[20]); assertFalse(lights[34])
    }

    /** isSolvableFast 与 solveRaw 判定完全一致 */
    @Test
    fun fastSolvabilityMatchesFullCheck() {
        for (n in 2..24) {
            val N = n * n
            for (trial in 0 until 300) {
                val lights = BooleanArray(N)
                val k = 1 + Random.nextInt(minOf(N, 3 * n))
                repeat(k) { lights[Random.nextInt(N)] = true }
                val slow = LightsSolver.solveRaw(n, lights) != null
                val fast = LightsSolver.isSolvableFast(n, lights)
                assertEquals("n=$n 两种判定应一致", slow, fast)
            }
        }
    }

    /** fixParity：修复后必然可解，且翻转数不超过零空间维数 */
    @Test
    fun fixParityProducesSolvableBoards() {
        for (n in 2..24) {
            val N = n * n
            val nullity = LightsSolver.solveRaw(n, BooleanArray(N))!!.free
            for (trial in 0 until 100) {
                val lights = BooleanArray(N)
                val k = 1 + Random.nextInt(minOf(N, 3 * n))
                repeat(k) { lights[Random.nextInt(N)] = true }
                if (LightsSolver.isSolvableFast(n, lights)) {
                    assertEquals(0, LightsSolver.fixParity(n, lights).size)
                    continue
                }
                val flips = LightsSolver.fixParity(n, lights)
                assertTrue("n=$n 应返回非空修复", flips.isNotEmpty())
                assertTrue("n=$n 翻转数 $flips 不应超过零空间维数 $nullity", flips.size <= nullity)
                val fixed = lights.copyOf()
                for (i in flips) fixed[i] = !fixed[i]
                assertTrue("n=$n 修复后应可解", LightsSolver.isSolvableFast(n, fixed))
                assertTrue("n=$n 修复后完整判定也应可解", LightsSolver.solveRaw(n, fixed) != null)
                var diff = 0
                for (i in lights.indices) if (lights[i] != fixed[i]) diff++
                assertEquals("修复只应翻转指定格子", flips.size, diff)
            }
        }
    }

    /** computePar 与最小解步数一致，且 par 步内确有解可全灭 */
    @Test
    fun computeParMatchesMinimalSolution() {
        for (n in listOf(3, 5, 6, 8, 10, 12)) {
            val N = n * n
            for (trial in 0 until 10) {
                val lights = BooleanArray(N)
                val presses = 1 + Random.nextInt(3 * n)
                repeat(presses) { LightsSolver.applyPress(lights, n, Random.nextInt(N)) }
                if (LightsSolver.litCount(lights) == 0) continue
                val par = LightsSolver.computePar(n, lights)
                val solMin = LightsSolver.solve(n, lights, minimal = true)
                assertNotNull(solMin)
                assertEquals("n=$n par 应与最小解一致", solMin!!.size, par)
                val work = lights.copyOf()
                for (k in solMin) LightsSolver.applyPress(work, n, k)
                assertTrue("n=$n 最小解应可全灭", LightsSolver.isCleared(work))
            }
        }
    }

    /** 无解局面：solveRaw 返回 null、solve 返回 null、computePar 返回 null */
    @Test
    fun unsolvableBoardsReturnNull() {
        // 5x5 角落单灯局面不可解（零空间维数 2，安静模式判定）
        val n = 5
        val lights = BooleanArray(25).also { it[0] = true }
        assertFalse(LightsSolver.isSolvableFast(n, lights))
        assertNull(LightsSolver.solveRaw(n, lights))
        assertNull(LightsSolver.solve(n, lights))
        assertNull(LightsSolver.computePar(n, lights))
        val flips = LightsSolver.fixParity(n, lights)
        assertTrue(flips.isNotEmpty())
        val fixed = lights.copyOf()
        for (i in flips) fixed[i] = !fixed[i]
        assertTrue(LightsSolver.isSolvableFast(n, fixed))
        assertTrue(LightsSolver.solveRaw(n, fixed) != null)
    }

    /** 零棋盘：可解、空解、par 为 0 */
    @Test
    fun clearedBoardIsSolvableWithEmptySolution() {
        val lights = BooleanArray(64)
        assertTrue(LightsSolver.isCleared(lights))
        assertTrue(LightsSolver.isSolvableFast(8, lights))
        assertEquals(0, LightsSolver.solve(8, lights)!!.size)
        assertEquals(0, LightsSolver.computePar(8, lights))
    }
}
