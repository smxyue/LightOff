/*
 * Lights Out 求解器
 * 棋盘 n*n，按下格子 k 会翻转 k 及其上下左右（不含对角）相邻格。
 * 求 x 使得 A·x = b (mod 2)，A[r][c]=1 表示按 c 会影响 r。
 * 由于 4 邻域关系对称：A[r][c] = 1 <=> c ∈ pressMask(r)，故方程系数行可直接用 pressMask(r)。
 * 用 BigInt 位掩码做 Gauss-Jordan 消元，n<=16（256 个未知数）时依然是毫秒级。
 */
(function (root, factory) {
  const mod = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = mod;
  else root.LOSolver = mod;
})(typeof self !== 'undefined' ? self : this, function () {
  const maskCache = new Map();

  const idxOf = (n, r, c) => r * n + c;

  /** 按下索引 k 的格子会被翻转的格子集合（BigInt 位掩码） */
  function pressMaskBig(n, k) {
    const masks = pressMasks(n);
    return masks[k];
  }

  /** 每个格子的按下影响掩码，按 n 缓存 */
  function pressMasks(n) {
    let m = maskCache.get(n);
    if (m) return m;
    m = new Array(n * n);
    for (let r = 0; r < n; r++) {
      for (let c = 0; c < n; c++) {
        let v = 0n;
        const self = BigInt(idxOf(n, r, c));
        v |= 1n << self;
        if (r > 0) v |= 1n << BigInt(idxOf(n, r - 1, c));
        if (r < n - 1) v |= 1n << BigInt(idxOf(n, r + 1, c));
        if (c > 0) v |= 1n << BigInt(idxOf(n, r, c - 1));
        if (c < n - 1) v |= 1n << BigInt(idxOf(n, r, c + 1));
        m[idxOf(n, r, c)] = v;
      }
    }
    maskCache.set(n, m);
    return m;
  }

  function popcount(big) {
    let v = big;
    let n = 0;
    while (v > 0n) {
      v &= v - 1n;
      n++;
    }
    return n;
  }

  /** lights: 类数组（0/1），返回 BigInt 位掩码 */
  function toBits(lights) {
    let b = 0n;
    for (let i = 0; i < lights.length; i++) if (lights[i]) b |= 1n << BigInt(i);
    return b;
  }

  function fromBits(n, bits) {
    const out = new Uint8Array(n * n);
    for (let i = 0; i < out.length; i++) out[i] = Number((bits >> BigInt(i)) & 1n);
    return out;
  }

  /**
   * 解方程，返回 {solution:Uint8Array, presses:number[], free:number, basis:BigInt[]}
   * 无解返回 null。free>0 表示存在多解（含自由变量置 0 得到的一个特解）。
   */
  function solveRaw(n, lights) {
    const N = n * n;
    const masks = pressMasks(n);
    const rhsBit = BigInt(N);
    const coeffMask = (1n << rhsBit) - 1n;

    // 每行：低位是系数（影响该格的按键集合），第 N 位是右端项
    const rows = new Array(N);
    for (let r = 0; r < N; r++) {
      rows[r] = masks[r] | ((BigInt(lights[r] ? 1 : 0)) << rhsBit);
    }

    const where = new Array(N).fill(-1);
    let pivotRow = 0;
    for (let col = 0; col < N && pivotRow < N; col++) {
      const bit = 1n << BigInt(col);
      let sel = -1;
      for (let i = pivotRow; i < N; i++) {
        if ((rows[i] & bit) !== 0n) { sel = i; break; }
      }
      if (sel < 0) continue;
      const tmp = rows[pivotRow]; rows[pivotRow] = rows[sel]; rows[sel] = tmp;
      for (let i = 0; i < N; i++) {
        if (i !== pivotRow && (rows[i] & bit) !== 0n) rows[i] ^= rows[pivotRow];
      }
      where[col] = pivotRow;
      pivotRow++;
    }

    // 0 = 1 即为矛盾 -> 无解
    for (let i = 0; i < N; i++) {
      if ((rows[i] & coeffMask) === 0n && ((rows[i] >> rhsBit) & 1n) === 1n) return null;
    }

    // 特解（自由变量取 0）
    let x = 0n;
    for (let col = 0; col < N; col++) {
      if (where[col] >= 0 && ((rows[where[col]] >> rhsBit) & 1n) === 1n) x |= 1n << BigInt(col);
    }

    // 零空间基：每个自由列 f 对应一个向量
    const basis = [];
    for (let col = 0; col < N; col++) {
      if (where[col] !== -1) continue;
      let z = 1n << BigInt(col);
      for (let p = 0; p < N; p++) {
        if (where[p] >= 0 && ((rows[where[p]] >> BigInt(col)) & 1n) === 1n) z |= 1n << BigInt(p);
      }
      basis.push(z);
    }
    return { x, basis, free: basis.length };
  }

  /**
   * 在等价解（特解 + 零空间）中寻找按压次数较少的解。
   * 零空间维数 <= 20 时直接穷举全部组合，得到真正最少的按压集合；
   * 否则用贪心（反复异或能使权重下降的基向量）。
   */
  function reduceWeight(x, basis, greedyOnly) {
    if (!basis.length) return { x, w: popcount(x) };
    if (!greedyOnly && basis.length <= 20) {
      let bestX = x;
      let best = popcount(x);
      const limit = 1 << basis.length;
      for (let m = 1; m < limit; m++) {
        let cand = x;
        for (let b = 0; b < basis.length; b++) if (m & (1 << b)) cand ^= basis[b];
        const w = popcount(cand);
        if (w < best) { best = w; bestX = cand; }
      }
      return { x: bestX, w: best };
    }
    let best = popcount(x);
    let passes = 16;
    while (passes-- > 0) {
      let improved = false;
      for (const z of basis) {
        const cand = x ^ z;
        const w = popcount(cand);
        if (w < best) { x = cand; best = w; improved = true; }
      }
      if (!improved) break;
    }
    return { x, w: best };
  }

  /**
   * 求解当前局面。
   * @returns null 无解；否则 {presses, lights:Uint8Array, par, free}
   */
  function solve(n, lights, opts) {
    const minimal = !opts || opts.minimal !== false;
    const raw = solveRaw(n, lights);
    if (!raw) return null;
    let x = raw.x;
    if (minimal) x = reduceWeight(x, raw.basis).x;
    const presses = [];
    for (let i = 0; i < n * n; i++) if ((x >> BigInt(i)) & 1n) presses.push(i);
    return { presses, solution: fromBits(n, x), par: presses.length, free: raw.free };
  }

  function isSolvable(n, lights) {
    return solveRaw(n, lights) !== null;
  }

  /* ---------- 安静模式 / 快速可解判定 / parity 修复 ---------- */

  const quietCache = new Map();

  /**
   * 安静模式（quiet patterns）= 系数矩阵的零空间基。
   * 矩阵 A = I + 网格邻接，是对称的，故 b 可解 ⟺ b 与每个安静模式的点积为 0 (mod 2)。
   * 对全零棋盘求解即得零空间基，按 n 缓存。
   */
  function quietPatterns(n) {
    let q = quietCache.get(n);
    if (q) return q;
    const raw = solveRaw(n, new Uint8Array(n * n));
    q = raw ? raw.basis : [];
    quietCache.set(n, q);
    return q;
  }

  /** 快速可解判定：与 isSolvable 等价，但只需 k 次 popcount（k = 零空间维数） */
  function isSolvableFast(n, lights) {
    const basis = quietPatterns(n);
    if (!basis.length) return true;
    const b = toBits(lights);
    for (let i = 0; i < basis.length; i++) {
      if (popcount(b & basis[i]) & 1) return false;
    }
    return true;
  }

  /**
   * parity 修复：返回需要翻转的格子下标数组，翻转后 lights 必然可解。
   * 原理：格子 i 对应安静模式列向量 col_i；当前缺陷 d = 各安静模式奇偶性组成的向量。
   * 求集合 S 使 XOR_{i∈S} col_i = d，即 k×N 的 GF(2) 线性系统，高斯消元一步得解（|S| ≤ k）。
   * 无需修复（已可解或零空间为 0）时返回 []。
   */
  function fixParity(n, lights) {
    const basis = quietPatterns(n);
    const k = basis.length;
    if (!k) return [];
    const b = toBits(lights);
    let d = 0;
    for (let j = 0; j < k; j++) {
      if (popcount(b & basis[j]) & 1) d |= (1 << j);
    }
    if (!d) return [];
    const N = n * n;
    const rhsBit = BigInt(N);
    const rows = new Array(k);
    for (let j = 0; j < k; j++) {
      rows[j] = basis[j] | (BigInt((d >> j) & 1) << rhsBit);
    }
    const where = new Array(N).fill(-1);
    let pivotRow = 0;
    for (let col = 0; col < N && pivotRow < k; col++) {
      const bit = 1n << BigInt(col);
      let sel = -1;
      for (let i = pivotRow; i < k; i++) {
        if ((rows[i] & bit) !== 0n) { sel = i; break; }
      }
      if (sel < 0) continue;
      const tmp = rows[pivotRow]; rows[pivotRow] = rows[sel]; rows[sel] = tmp;
      for (let i = 0; i < k; i++) {
        if (i !== pivotRow && (rows[i] & bit) !== 0n) rows[i] ^= rows[pivotRow];
      }
      where[col] = pivotRow;
      pivotRow++;
    }
    // d 必在列空间中（d = XOR of cols over support(b)），不会出现矛盾行
    let x = 0n;
    for (let col = 0; col < N; col++) {
      if (where[col] >= 0 && ((rows[where[col]] >> rhsBit) & 1n) === 1n) x |= 1n << BigInt(col);
    }
    const flips = [];
    for (let i = 0; i < N; i++) if ((x >> BigInt(i)) & 1n) flips.push(i);
    return flips;
  }

  /** 纯函数版：返回按压 k 后的新棋盘 */
  function applyPressTo(lights, n, k) {
    const out = Uint8Array.from(lights);
    const masks = pressMasks(n);
    const m = masks[k];
    for (let i = 0; i < out.length; i++) if ((m >> BigInt(i)) & 1n) out[i] ^= 1;
    return out;
  }

  function applyPress(lights, n, k) {
    const masks = pressMasks(n);
    const m = masks[k];
    for (let i = 0; i < lights.length; i++) if ((m >> BigInt(i)) & 1n) lights[i] ^= 1;
    return lights;
  }

  function isCleared(lights) {
    for (let i = 0; i < lights.length; i++) if (lights[i]) return false;
    return true;
  }

  function litCount(lights) {
    let c = 0;
    for (let i = 0; i < lights.length; i++) c += lights[i];
    return c;
  }

  return {
    idxOf,
    pressMasks,
    pressMaskBig,
    popcount,
    toBits,
    fromBits,
    solve,
    solveRaw,
    isSolvable,
    isSolvableFast,
    quietPatterns,
    fixParity,
    applyPress,
    applyPressTo,
    isCleared,
    litCount,
  };
});
