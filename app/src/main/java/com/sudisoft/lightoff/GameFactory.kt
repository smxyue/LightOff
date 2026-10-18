package com.sudisoft.lightoff

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 局面生成：按比例随机亮灯，数学上保证可解。
 *
 * - exact：纯随机局面恰好可解（精确命中目标比例）
 * - fix：纯随机局面无解时，用安静模式做 parity 修复（翻转不超过零空间维数个格子，比例基本不变）
 * - scramble：兜底，由随机按键打乱出局面（必可解，比例不精确）
 */
object GameFactory {

    class Generated(val lights: BooleanArray, val mode: String)

    fun generate(n: Int, ratio: Int, countTarget: Boolean): Generated {
        val N = n * n
        val target = maxOf(1, minOf(N, (N * ratio / 100.0).roundToInt()))
        val maxAttempts = if (countTarget) 60 else 4000
        val order = IntArray(N)
        for (attempt in 0 until maxAttempts) {
            // 部分洗牌，精确选出 target 个格子
            for (i in 0 until N) order[i] = i
            for (i in N - 1 downTo 1) {
                val j = Random.nextInt(i + 1)
                val t = order[i]; order[i] = order[j]; order[j] = t
            }
            val lights = BooleanArray(N)
            for (i in 0 until target) lights[order[i]] = true
            if (LightsSolver.isSolvableFast(n, lights)) return Generated(lights, "exact")
            if (countTarget) {
                val flips = LightsSolver.fixParity(n, lights)
                if (flips.isNotEmpty()) {
                    for (i in flips) lights[i] = !lights[i]
                    if (LightsSolver.litCount(lights) >= 1 && LightsSolver.isSolvableFast(n, lights)) {
                        return Generated(lights, "fix")
                    }
                }
            }
            // 否则重摇，寻找精确比例的可解局面
        }
        // 兜底：由随机按键打乱出局面（必可解）
        val lights = BooleanArray(N)
        val presses = 2 * n + Random.nextInt(2 * n + 1)
        repeat(presses) { LightsSolver.applyPress(lights, n, Random.nextInt(N)) }
        if (LightsSolver.litCount(lights) == 0) lights[Random.nextInt(N)] = true
        return Generated(lights, "scramble")
    }

    /** 编辑模式随机填充 */
    fun fillRandom(n: Int, ratio: Int): BooleanArray {
        val N = n * n
        val target = maxOf(1, minOf(N, (N * ratio / 100.0).roundToInt()))
        val order = IntArray(N) { it }
        for (i in N - 1 downTo 1) {
            val j = Random.nextInt(i + 1)
            val t = order[i]; order[i] = order[j]; order[j] = t
        }
        val lights = BooleanArray(N)
        for (i in 0 until target) lights[order[i]] = true
        return lights
    }
}
