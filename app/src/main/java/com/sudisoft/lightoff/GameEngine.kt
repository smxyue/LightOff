package com.sudisoft.lightoff

import java.io.Serializable
import kotlin.random.Random

/** 自定义规模上限（2–24，求解 / 渲染 / 生成均可胜任） */
const val MAX_N = 24

/**
 * 对局状态机（纯 Kotlin，无 Android 依赖，可单元测试）。
 *
 * 规则：点击一格翻转该格与上下左右相邻格（不含对角），全部熄灭即胜利。
 */
class GameEngine {

    var n: Int = 8
        private set
    var ratio: Int = 10
        private set
    var countTarget: Boolean = true
        private set

    var lights: BooleanArray = BooleanArray(64)
        private set
    var steps: Int = 0
        private set
    var assisted: Boolean = false
        private set
    var won: Boolean = false
        private set
    var active: Boolean = false
        private set
    var editMode: Boolean = false
        private set

    /** 本局（初始局面）的最少步数，由 newGame 计算 */
    var par: Int? = null
        private set

    /** 局面生成方式：exact / fix / scramble */
    var genMode: String = "exact"
        private set

    /** 计时由 UI 层驱动（每 250ms 累加） */
    var elapsedMs: Long = 0L

    private val history = ArrayDeque<Snapshot>()

    private class Snapshot(val lights: BooleanArray, val steps: Int)

    /** 状态快照（旋屏 / 进程恢复，纯数据） */
    class State(
        val n: Int,
        val ratio: Int,
        val countTarget: Boolean,
        val lights: BooleanArray,
        val steps: Int,
        val elapsedMs: Long,
        val assisted: Boolean,
        val won: Boolean,
        val editMode: Boolean,
        val par: Int?,
        val genMode: String
    ) : Serializable

    fun configure(n: Int, ratio: Int, countTarget: Boolean) {
        this.n = n.coerceIn(2, MAX_N)
        this.ratio = ratio.coerceIn(1, 60)
        this.countTarget = countTarget
        if (lights.size != this.n * this.n) lights = BooleanArray(this.n * this.n)
    }

    /**
     * 开新局（含随机生成与最少步数计算，可能耗时数百毫秒，请放后台线程调用）。
     */
    fun newGame() {
        val gen = GameFactory.generate(n, ratio, countTarget)
        lights = gen.lights
        genMode = gen.mode
        steps = 0
        assisted = false
        won = false
        active = true
        editMode = false
        history.clear()
        par = LightsSolver.computePar(n, lights)
    }

    /**
     * 按下一格。
     * 编辑模式下只翻转该格自身；对局中返回 false 表示当前不可落子。
     * 胜利时自动置 won / active=false。
     */
    fun press(k: Int): Boolean {
        if (k !in lights.indices) return false
        if (editMode) {
            lights[k] = !lights[k]
            return true
        }
        if (!active || won) return false
        history.addLast(Snapshot(lights.copyOf(), steps))
        if (history.size > 400) history.removeFirst()
        LightsSolver.applyPress(lights, n, k)
        steps++
        if (LightsSolver.isCleared(lights)) {
            won = true
            active = false
        }
        return true
    }

    fun undo(): Boolean {
        if (editMode || !active || won || history.isEmpty()) return false
        val snap = history.removeLast()
        lights = snap.lights
        steps = snap.steps
        return true
    }

    fun canUndo(): Boolean = !editMode && active && !won && history.isNotEmpty()

    /** 后台线程算完最少步数后回填（对局中随局面变化刷新） */
    fun updatePar(p: Int?) {
        par = p
    }

    /** 标记为自定义布局（影响生成说明文案） */
    fun markCustomLayout() {
        genMode = "custom"
    }

    fun litCount(): Int = LightsSolver.litCount(lights)

    fun isSolvable(): Boolean = LightsSolver.isSolvableFast(n, lights)

    /** 提示：返回建议按下的格子（会标记本局为已协助）；无解返回 null */
    fun hintIndex(): Int? {
        if (editMode || !active || won) return null
        val presses = LightsSolver.solve(n, lights) ?: return null
        if (presses.isEmpty()) return null
        assisted = true
        return presses[0]
    }

    /** 自动求解的按键序列（会标记已协助）；无解返回 null */
    fun autoPresses(): IntArray? {
        if (editMode || !active || won) return null
        val presses = LightsSolver.solve(n, lights) ?: return null
        assisted = true
        return presses
    }

    /** 洗牌：保留步数，重新打乱（仍保证可解） */
    fun shuffle() {
        if (editMode) return
        val presses = n + Random.nextInt(2 * n + 1)
        repeat(presses) { LightsSolver.applyPress(lights, n, Random.nextInt(n * n)) }
        if (LightsSolver.litCount(lights) == 0) lights[Random.nextInt(n * n)] = true
        history.clear()
    }

    /* ---------- 编辑模式 ---------- */

    fun setEditMode(on: Boolean) {
        editMode = on
        if (on) {
            active = false
            won = false
        }
    }

    fun editorFill() {
        lights = GameFactory.fillRandom(n, ratio)
    }

    fun editorClear() {
        lights = BooleanArray(n * n)
    }

    /** 开始自定义挑战；布局无解或全灭返回 false */
    fun startCustom(): Boolean {
        if (!isSolvable() || litCount() == 0) return false
        editMode = false
        active = true
        won = false
        steps = 0
        assisted = false
        history.clear()
        return true
    }

    /* ---------- 状态持久化 ---------- */

    fun snapshot(): State = State(
        n, ratio, countTarget, lights.copyOf(), steps, elapsedMs,
        assisted, won, editMode, par, genMode
    )

    fun restore(s: State) {
        n = s.n
        ratio = s.ratio
        countTarget = s.countTarget
        lights = s.lights.copyOf()
        steps = s.steps
        elapsedMs = s.elapsedMs
        assisted = s.assisted
        won = s.won
        editMode = s.editMode
        active = !s.won && !s.editMode
        par = s.par
        genMode = s.genMode
        history.clear()
    }
}
