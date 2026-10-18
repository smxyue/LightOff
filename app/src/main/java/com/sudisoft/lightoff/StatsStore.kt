package com.sudisoft.lightoff

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * 按规模统计的胜利记录（SharedPreferences + JSON 持久化）。
 *
 * 每个规模记录：wins 胜利数 / games 局数 / sumSteps 胜局步数和（算均步）/
 * bestSteps 最少步纪录 / bestTimeMs 最快用时纪录（毫秒）。
 * 局数在每局首次互动时计入；胜利仅在未使用协助时计入。
 */
class StatsStore(context: Context) {

    data class SizeStats(
        var wins: Int = 0,
        var games: Int = 0,
        var sumSteps: Int = 0,
        var bestSteps: Int? = null,
        var bestTimeMs: Long? = null
    )

    private val prefs = context.applicationContext
        .getSharedPreferences("lightsout_stats", Context.MODE_PRIVATE)

    /** 全部记录，按规模升序 */
    fun all(): Map<Int, SizeStats> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return emptyMap()
        }
        val out = LinkedHashMap<Int, SizeStats>()
        for (key in root.keys()) {
            val n = key.toIntOrNull() ?: continue
            val o = root.optJSONObject(key) ?: continue
            val bs = if (o.has("bs")) o.optInt("bs", -1).takeIf { it >= 0 } else null
            val bt = if (o.has("bt")) o.optLong("bt", -1L).takeIf { it >= 0 } else null
            out[n] = SizeStats(o.optInt("w"), o.optInt("g"), o.optInt("ss"), bs, bt)
        }
        return out.toSortedMap()
    }

    private fun ensure(map: MutableMap<Int, SizeStats>, n: Int): SizeStats =
        map.getOrPut(n) { SizeStats() }

    /** 一局首次互动时调用（计入局数） */
    fun recordGame(n: Int) = modify { ensure(it, n).games++ }

    /** 胜利时调用（仅未协助局） */
    fun recordWin(n: Int, steps: Int, timeMs: Long) = modify { m ->
        val s = ensure(m, n)
        s.wins++
        s.sumSteps += steps
        if (s.bestSteps == null || steps < s.bestSteps!!) s.bestSteps = steps
        if (s.bestTimeMs == null || timeMs < s.bestTimeMs!!) s.bestTimeMs = timeMs
    }

    fun clearSize(n: Int) = modify { it.remove(n) }

    fun clearAll() = modify { it.clear() }

    private fun modify(block: (MutableMap<Int, SizeStats>) -> Unit) {
        val map = all().toMutableMap()
        block(map)
        val root = JSONObject()
        for ((n, s) in map) {
            val o = JSONObject()
            o.put("w", s.wins)
            o.put("g", s.games)
            o.put("ss", s.sumSteps)
            s.bestSteps?.let { o.put("bs", it) }
            s.bestTimeMs?.let { o.put("bt", it) }
            root.put(n.toString(), o)
        }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    /** 导出 CSV（带 BOM，方便 Excel 直接打开） */
    fun csv(): String {
        val sb = StringBuilder("\uFEFF规模,胜利,局数,胜率(%),最少步,最快(秒),平均步数\r\n")
        for ((n, s) in all()) {
            val rate = if (s.games > 0) fmt(100.0 * s.wins / s.games) else ""
            val best = s.bestSteps?.toString() ?: ""
            val fast = s.bestTimeMs?.let { fmt(it / 1000.0) } ?: ""
            val avg = if (s.wins > 0) fmt(1.0 * s.sumSteps / s.wins) else ""
            sb.append("${n}x${n},${s.wins},${s.games},$rate,$best,$fast,$avg\r\n")
        }
        return sb.toString()
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)

    companion object {
        private const val KEY = "stats_v1"
    }
}
