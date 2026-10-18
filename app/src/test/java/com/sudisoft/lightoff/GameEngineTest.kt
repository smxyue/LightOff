package com.sudisoft.lightoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * GameEngine 单元测试（纯 Kotlin，无 Android 依赖）。
 */
class GameEngineTest {

    private fun engine(n: Int = 8, ratio: Int = 10, countTarget: Boolean = true) =
        GameEngine().apply { configure(n, ratio, countTarget) }

    @Test
    fun newGameGeneratesSolvableBoardWithPar() {
        for (n in listOf(3, 5, 8, 12, 16, 24)) {
            for (ratio in listOf(5, 10, 50)) {
                val e = engine(n, ratio)
                e.newGame()
                assertTrue("n=$n r=$ratio 应可解", e.isSolvable())
                assertTrue("n=$n r=$ratio 至少 1 盏灯", e.litCount() >= 1)
                assertNotNull("n=$n r=$ratio 应算出 par", e.par)
                assertTrue("n=$n r=$ratio par 应 >= 1", e.par!! >= 1)
                assertEquals(0, e.steps)
                assertFalse(e.won)
                assertFalse(e.editMode)
                assertTrue(e.active)
            }
        }
    }

    @Test
    fun pressTogglesCrossAndWinsWhenCleared() {
        val e = engine(5)
        e.newGame()
        // 编辑模式摆一个「十字」灯组（中心 + 四邻），按中心即可全部熄灭
        e.setEditMode(true)
        e.editorClear()
        for (k in intArrayOf(12, 7, 17, 11, 13)) e.press(k)
        assertEquals(5, e.litCount())
        assertTrue(e.startCustom())
        assertTrue(e.press(12))
        assertTrue("按中心后应全灭", e.won)
        assertEquals(1, e.steps)
        assertFalse("胜利后不再接受落子", e.active)
    }

    @Test
    fun pressTogglesOrthogonalNeighborsNotDiagonal() {
        val e = engine(8)
        e.newGame()
        e.setEditMode(true)
        e.editorClear()
        e.press(27)
        // 编辑模式：只翻转该格自身（方便自由摆灯）
        for (i in e.lights.indices) {
            assertEquals("编辑模式格子 $i", i == 27, e.lights[i])
        }
        // 开始挑战后：落子翻转该格 + 上下左右（不含对角）。
        // 27 自身原为亮 → 被熄灭；四邻原为灭 → 被点亮
        assertTrue(e.startCustom())
        e.press(27)
        val expected = setOf(19, 35, 26, 28)
        for (i in e.lights.indices) {
            assertEquals("对局格子 $i", i in expected, e.lights[i])
        }
        assertFalse(e.lights[18]); assertFalse(e.lights[36])
        assertFalse(e.lights[20]); assertFalse(e.lights[34])
    }

    @Test
    fun undoRestoresPreviousState() {
        val e = engine(8)
        e.newGame()
        val before = e.lights.copyOf()
        val lit0 = e.litCount()
        assertTrue(e.press(10))
        assertEquals(lit0 + 5 - 2 * (listOf(10, 9, 11, 2, 18).count { before[it] }), e.litCount())
        assertTrue(e.undo())
        assertEquals(lit0, e.litCount())
        for (i in before.indices) assertEquals(before[i], e.lights[i])
        assertEquals(0, e.steps)
    }

    @Test
    fun hintMarksAssistedAndAutoPressesClearBoard() {
        val e = engine(8)
        e.newGame()
        val hint = e.hintIndex()
        assertNotNull("应给出提示", hint)
        assertTrue("提示应标记协助", e.assisted)

        val e2 = engine(8)
        e2.newGame()
        val presses = e2.autoPresses()
        assertNotNull("应给出自动求解序列", presses)
        assertTrue("自动求解应标记协助", e2.assisted)
        for (k in presses!!) e2.press(k)
        assertTrue("按完自动序列应全灭", e2.won)
    }

    @Test
    fun shuffleKeepsSolvableAndLit() {
        val e = engine(8)
        e.newGame()
        e.press(3)
        e.shuffle()
        assertTrue("洗牌后仍应可解", e.isSolvable())
        assertTrue("洗牌后至少 1 盏灯", e.litCount() >= 1)
    }

    @Test
    fun editModeFlow() {
        val e = engine(6)
        e.newGame()
        e.setEditMode(true)
        e.editorFill()
        assertTrue("随机填充后应有灯", e.litCount() >= 1)
        // 摆一个确定可解的布局：空盘 + 按两格
        e.editorClear()
        e.press(0)
        e.press(1)
        assertTrue(e.startCustom())
        assertFalse(e.editMode)
        assertTrue(e.active)
        assertEquals(0, e.steps)

        // 无解布局无法开始（5x5 角落单灯：安静模式判定不可解）
        val e2 = engine(5)
        e2.setEditMode(true)
        e2.editorClear()
        e2.press(0)
        assertFalse("5x5 角落单灯不可解", e2.startCustom())
        // 全灭布局无法开始
        e2.editorClear()
        assertFalse(e2.startCustom())
    }

    @Test
    fun snapshotRestoreRoundTrip() {
        val e = engine(10)
        e.newGame()
        e.press(5)
        e.press(7)
        val snap = e.snapshot()
        val e2 = GameEngine()
        e2.restore(snap)
        assertEquals(e.n, e2.n)
        assertEquals(e.steps, e2.steps)
        assertEquals(e.par, e2.par)
        assertEquals(e.genMode, e2.genMode)
        for (i in e.lights.indices) assertEquals(e.lights[i], e2.lights[i])
    }

    @Test
    fun generationAlwaysSucceedsAcrossSizes() {
        for (n in 2..MAX_N) {
            val e = engine(n, 10, countTarget = true)
            e.newGame()
            assertTrue("n=$n 应可解", e.isSolvable())
            assertTrue("n=$n 应有灯", e.litCount() >= 1)
        }
        // 严格比例模式（不允许微调）也必须给出可解局面
        for (n in 2..MAX_N) {
            val e = engine(n, 10, countTarget = false)
            e.newGame()
            assertTrue("n=$n 严格模式应可解", e.isSolvable())
            assertTrue("n=$n 严格模式应有灯", e.litCount() >= 1)
        }
    }

    @Test
    fun reconfigureTakesEffectOnNextNewGame() {
        // 复现过的 bug：设置里改规模后开新局无效——engine.configure 只在 onCreate
        // 调用过一次，newGame 用的还是旧 n。这里锁定「重新 configure 后立即生效」。
        val e = engine(8, 10)
        e.newGame()
        assertEquals(8, e.n)
        assertEquals(64, e.lights.size)

        e.configure(5, 30, countTarget = false)
        e.newGame()
        assertEquals(5, e.n)
        assertEquals(25, e.lights.size)
        assertEquals(30, e.ratio)
        assertFalse(e.countTarget)
        assertTrue("新规模应可解", e.isSolvable())
        assertTrue("新规模应有灯", e.litCount() >= 1)
        assertNotNull(e.par)

        // 再改一次（含越界值纠正）
        e.configure(99, 10, countTarget = true)
        e.newGame()
        assertEquals(MAX_N, e.n)
        assertEquals(MAX_N * MAX_N, e.lights.size)
    }

    @Test
    fun pressRejectedAfterWin() {
        val e = engine(5)
        e.newGame()
        e.setEditMode(true)
        e.editorClear()
        // 摆十字灯组，按中心取胜
        for (k in intArrayOf(12, 7, 17, 11, 13)) e.press(k)
        e.startCustom()
        e.press(12)
        assertTrue(e.won)
        assertFalse("胜利后不应接受落子", e.press(0))
        assertNull("胜利后不应有提示", e.hintIndex())
        assertNull("胜利后不应有自动序列", e.autoPresses())
        assertFalse("胜利后不能撤销", e.undo())
    }
}
