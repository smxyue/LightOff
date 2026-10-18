package com.sudisoft.lightoff

import java.util.BitSet

/**
 * 灭灯求解器（与 Web 版 solver.js 等价）。
 *
 * 棋盘 n*n，按下格子 k 会翻转 k 及其上下左右（不含对角）相邻格。
 * 求 x 使 A·x = b (mod 2)，A[r][c]=1 表示按 c 会影响 r。
 * 由于 4 邻域关系对称：A[r][c] = 1 <=> c ∈ pressMask(r)，故系数行可直接用 pressMask(r)。
 *
 * 用 BitSet 位掩码做 Gauss-Jordan 消元，n<=24（576 个未知数）时仍是毫秒级。
 */
object LightsSolver {

    private val maskCache = HashMap<Int, Array<BitSet>>()
    private val quietCache = HashMap<Int, List<BitSet>>()

    fun idxOf(n: Int, r: Int, c: Int): Int = r * n + c

    /** 按下索引 k 的格子会被翻转的格子集合（BitSet 位掩码，位号 = 格子下标） */
    fun pressMasks(n: Int): Array<BitSet> {
        maskCache[n]?.let { return it }
        val masks = Array(n * n) { BitSet(n * n) }
        for (r in 0 until n) {
            for (c in 0 until n) {
                val k = idxOf(n, r, c)
                masks[k].set(k)
                if (r > 0) masks[k].set(idxOf(n, r - 1, c))
                if (r < n - 1) masks[k].set(idxOf(n, r + 1, c))
                if (c > 0) masks[k].set(idxOf(n, r, c - 1))
                if (c < n - 1) masks[k].set(idxOf(n, r, c + 1))
            }
        }
        maskCache[n] = masks
        return masks
    }

    /** 消元结果：特解 x + 零空间基（free = basis.size） */
    class RawSolution(val x: BitSet, val basis: List<BitSet>) {
        val free: Int get() = basis.size
    }

    /**
     * 解方程 A·x = lights (mod 2)。
     * 行布局：位 0..N-1 为系数（影响该格的按键集合），位 N 为右端项。
     * @return null 表示无解；否则特解（自由变量取 0）与零空间基。
     */
    fun solveRaw(n: Int, lights: BooleanArray): RawSolution? {
        val N = n * n
        val masks = pressMasks(n)
        val rows = Array(N) { r ->
            val bs = masks[r].clone() as BitSet
            if (lights[r]) bs.set(N)
            bs
        }

        val where = IntArray(N) { -1 }
        var pivotRow = 0
        for (col in 0 until N) {
            if (pivotRow >= N) break
            var sel = -1
            for (i in pivotRow until N) {
                if (rows[i].get(col)) { sel = i; break }
            }
            if (sel < 0) continue
            val tmp = rows[pivotRow]
            rows[pivotRow] = rows[sel]
            rows[sel] = tmp
            for (i in 0 until N) {
                if (i != pivotRow && rows[i].get(col)) rows[i].xor(rows[pivotRow])
            }
            where[col] = pivotRow
            pivotRow++
        }

        // 系数全 0 而右端为 1 → 矛盾 → 无解
        for (i in 0 until N) {
            if (rows[i].nextSetBit(0) >= N && rows[i].get(N)) return null
        }

        // 特解（自由变量取 0）
        val x = BitSet(N)
        for (col in 0 until N) {
            val wr = where[col]
            if (wr >= 0 && rows[wr].get(N)) x.set(col)
        }

        // 零空间基：每个自由列 f 对应一个向量
        val basis = ArrayList<BitSet>()
        for (col in 0 until N) {
            if (where[col] >= 0) continue
            val z = BitSet(N)
            z.set(col)
            for (p in 0 until N) {
                val wr = where[p]
                if (wr >= 0 && rows[wr].get(col)) z.set(p)
            }
            basis.add(z)
        }
        return RawSolution(x, basis)
    }

    /**
     * 在等价解（特解 + 零空间）中寻找按压次数较少的解。
     * 零空间维数 <= 16 时穷举全部组合，得到真正最少的按压集合；
     * 否则用贪心（反复异或能使权重下降的基向量）。
     */
    private fun reduceWeight(raw: RawSolution): BitSet {
        val basis = raw.basis
        if (basis.isEmpty()) return raw.x.clone() as BitSet
        if (basis.size <= 16) {
            var bestX = raw.x
            var best = raw.x.cardinality()
            val limit = 1 shl basis.size
            for (m in 1 until limit) {
                var cand = raw.x.clone() as BitSet
                for (b in basis.indices) {
                    if (m and (1 shl b) != 0) cand.xor(basis[b])
                }
                val w = cand.cardinality()
                if (w < best) { best = w; bestX = cand }
            }
            return bestX
        }
        var x = raw.x.clone() as BitSet
        var best = x.cardinality()
        repeat(32) {
            var improved = false
            for (z in basis) {
                val cand = x.clone() as BitSet
                cand.xor(z)
                val w = cand.cardinality()
                if (w < best) { x = cand; best = w; improved = true }
            }
            if (!improved) return@repeat
        }
        return x
    }

    /**
     * 求解当前局面。
     * @param minimal 是否在等价解中取按压次数最少者
     * @return null 无解；否则按压格子下标数组。
     */
    fun solve(n: Int, lights: BooleanArray, minimal: Boolean = false): IntArray? {
        val raw = solveRaw(n, lights) ?: return null
        val x = if (minimal) reduceWeight(raw) else raw.x
        val presses = ArrayList<Int>(x.cardinality())
        var i = x.nextSetBit(0)
        while (i >= 0) {
            presses.add(i)
            i = x.nextSetBit(i + 1)
        }
        return presses.toIntArray()
    }

    /** 当前局面的最少步数；无解返回 null。 */
    fun computePar(n: Int, lights: BooleanArray): Int? {
        val raw = solveRaw(n, lights) ?: return null
        return reduceWeight(raw).cardinality()
    }

    /* ---------- 安静模式 / 快速可解判定 / parity 修复 ---------- */

    /**
     * 安静模式（quiet patterns）= 系数矩阵的零空间基。
     * A = I + 网格邻接，是对称的，故 b 可解 ⟺ b 与每个安静模式的点积为 0 (mod 2)。
     * 对全零棋盘求解即得零空间基，按 n 缓存。
     */
    fun quietPatterns(n: Int): List<BitSet> {
        quietCache[n]?.let { return it }
        val raw = solveRaw(n, BooleanArray(n * n))
        val q: List<BitSet> = raw?.basis ?: emptyList()
        quietCache[n] = q
        return q
    }

    /** 快速可解判定：与 solveRaw(n, lights) != null 等价，但只需 k 次 popcount */
    fun isSolvableFast(n: Int, lights: BooleanArray): Boolean {
        val basis = quietPatterns(n)
        if (basis.isEmpty()) return true
        val b = toBits(lights)
        for (q in basis) {
            val t = b.clone() as BitSet
            t.and(q)
            if (t.cardinality() % 2 != 0) return false
        }
        return true
    }

    /**
     * parity 修复：返回需要翻转的格子下标数组，翻转后 lights 必然可解。
     *
     * 原理：格子 i 对应安静模式列向量 col_i；当前缺陷 d = 各安静模式奇偶性组成的向量。
     * 求集合 S 使 XOR_{i∈S} col_i = d，即 k×N 的 GF(2) 线性系统，高斯消元一步得解（|S| <= k）。
     * 无需修复（已可解或零空间为 0）时返回空数组。
     */
    fun fixParity(n: Int, lights: BooleanArray): IntArray {
        val basis = quietPatterns(n)
        val k = basis.size
        if (k == 0) return IntArray(0)
        val b = toBits(lights)
        var d = 0
        for (j in 0 until k) {
            val t = b.clone() as BitSet
            t.and(basis[j])
            if (t.cardinality() % 2 != 0) d = d or (1 shl j)
        }
        if (d == 0) return IntArray(0)

        val N = n * n
        // 增广行：位 0..N-1 系数，位 N 右端项
        val rows = Array(k) { j ->
            val bs = basis[j].clone() as BitSet
            if (d and (1 shl j) != 0) bs.set(N)
            bs
        }
        val where = IntArray(N) { -1 }
        var pivotRow = 0
        for (col in 0 until N) {
            if (pivotRow >= k) break
            var sel = -1
            for (i in pivotRow until k) {
                if (rows[i].get(col)) { sel = i; break }
            }
            if (sel < 0) continue
            val tmp = rows[pivotRow]
            rows[pivotRow] = rows[sel]
            rows[sel] = tmp
            for (i in 0 until k) {
                if (i != pivotRow && rows[i].get(col)) rows[i].xor(rows[pivotRow])
            }
            where[col] = pivotRow
            pivotRow++
        }
        // d 必在列空间中（d = XOR of cols over support(b)），不会出现矛盾行
        val x = BitSet(N)
        for (col in 0 until N) {
            val wr = where[col]
            if (wr >= 0 && rows[wr].get(N)) x.set(col)
        }
        val flips = ArrayList<Int>(x.cardinality())
        var i = x.nextSetBit(0)
        while (i >= 0) {
            flips.add(i)
            i = x.nextSetBit(i + 1)
        }
        return flips.toIntArray()
    }

    /* ---------- 基础操作 ---------- */

    private fun toBits(lights: BooleanArray): BitSet {
        val b = BitSet(lights.size)
        for (i in lights.indices) if (lights[i]) b.set(i)
        return b
    }

    /** 原地翻转：按下 k */
    fun applyPress(lights: BooleanArray, n: Int, k: Int) {
        val m = pressMasks(n)[k]
        var i = m.nextSetBit(0)
        while (i >= 0) {
            lights[i] = !lights[i]
            i = m.nextSetBit(i + 1)
        }
    }

    fun isCleared(lights: BooleanArray): Boolean {
        for (v in lights) if (v) return false
        return true
    }

    fun litCount(lights: BooleanArray): Int {
        var c = 0
        for (v in lights) if (v) c++
        return c
    }
}
