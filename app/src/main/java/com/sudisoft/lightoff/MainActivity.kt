package com.sudisoft.lightoff

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 灭灯 Lights Out 主界面。
 *
 * 规则：n×n 棋盘随机亮灯（比例可调），点击格子翻转该格与上下左右相邻格，
 * 全部熄灭即获胜；按规模统计胜利次数。求解、生成等重活放后台线程。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var engine: GameEngine
    private lateinit var stats: StatsStore
    private lateinit var settings: SettingsStore
    private lateinit var sound: SoundKit

    // 视图
    private lateinit var gameView: GameView
    private lateinit var chipSize: Chip
    private lateinit var chipLit: Chip
    private lateinit var chipSteps: Chip
    private lateinit var chipPar: Chip
    private lateinit var chipTime: Chip
    private lateinit var chipAssist: Chip
    private lateinit var chipSolvable: Chip
    private lateinit var btnNew: MaterialButton
    private lateinit var btnUndo: MaterialButton
    private lateinit var btnHint: MaterialButton
    private lateinit var btnAuto: MaterialButton
    private lateinit var btnShuffle: MaterialButton
    private lateinit var btnSound: MaterialButton
    private lateinit var btnEditToggle: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var editRow: View
    private lateinit var statTotalWins: android.widget.TextView
    private lateinit var statSizeWins: android.widget.TextView
    private lateinit var statSizeWinsLabel: android.widget.TextView
    private lateinit var statSizePar: android.widget.TextView
    private lateinit var statSizeBest: android.widget.TextView
    private lateinit var statSizeBestLabel: android.widget.TextView
    private lateinit var solvNote: android.widget.TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var autoJob: Job? = null
    private var parJob: Job? = null
    private var engagedCounted = false

    /** 计时：每 250ms 累加，首次互动时启动 */
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!engagedCounted || engine.won || engine.editMode) return
            engine.elapsedMs += 250L
            chipTime.text = getString(R.string.chip_time, fmtTime(engine.elapsedMs))
            uiHandler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = GameEngine()
        stats = StatsStore(this)
        settings = SettingsStore(this)
        sound = SoundKit().apply { enabled = settings.sound }

        bindViews()

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val restored = savedInstanceState?.getSerializable("engine") as? GameEngine.State
        if (restored != null) {
            engine.restore(restored)
            engagedCounted = savedInstanceState.getBoolean("engagedCounted")
            refreshAll()
        } else {
            engine.configure(settings.n, settings.ratio, settings.countTarget)
            startNewGame()
        }
    }

    private fun bindViews() {
        gameView = findViewById(R.id.gameView)
        chipSize = findViewById(R.id.chipSize)
        chipLit = findViewById(R.id.chipLit)
        chipSteps = findViewById(R.id.chipSteps)
        chipPar = findViewById(R.id.chipPar)
        chipTime = findViewById(R.id.chipTime)
        chipAssist = findViewById(R.id.chipAssist)
        chipSolvable = findViewById(R.id.chipSolvable)
        btnNew = findViewById(R.id.btnNew)
        btnUndo = findViewById(R.id.btnUndo)
        btnHint = findViewById(R.id.btnHint)
        btnAuto = findViewById(R.id.btnAuto)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnSound = findViewById(R.id.btnSound)
        btnEditToggle = findViewById(R.id.btnEditToggle)
        btnSettings = findViewById(R.id.btnSettings)
        editRow = findViewById(R.id.editRow)
        statTotalWins = findViewById(R.id.statTotalWins)
        statSizeWins = findViewById(R.id.statSizeWins)
        statSizeWinsLabel = findViewById(R.id.statSizeWinsLabel)
        statSizePar = findViewById(R.id.statSizePar)
        statSizeBest = findViewById(R.id.statSizeBest)
        statSizeBestLabel = findViewById(R.id.statSizeBestLabel)
        solvNote = findViewById(R.id.solvNote)

        gameView.onCellClickListener = { onCellClicked(it) }

        btnNew.setOnClickListener { startNewGame() }
        btnUndo.setOnClickListener {
            stopAuto()
            if (engine.undo()) {
                refreshAll()
                schedulePar()
            }
        }
        btnHint.setOnClickListener { onHintClicked() }
        btnAuto.setOnClickListener { onAutoClicked() }
        btnShuffle.setOnClickListener { onShuffleClicked() }
        btnSound.setOnClickListener {
            settings.sound = !settings.sound
            sound.enabled = settings.sound
            btnSound.setText(if (settings.sound) R.string.btn_sound_on else R.string.btn_sound_off)
            if (settings.sound) sound.press(true)
        }
        btnEditToggle.setOnClickListener { setEditMode(!engine.editMode) }
        btnSettings.setOnClickListener {
            val sheet = SettingsSheetFragment()
            sheet.onGameSettingsChanged = { startNewGame() }
            sheet.onStatsChanged = { updateStatsHeader() }
            sheet.show(supportFragmentManager, "settings")
        }
        findViewById<MaterialButton>(R.id.btnEditorRandom).setOnClickListener {
            engine.editorFill()
            refreshAll()
        }
        findViewById<MaterialButton>(R.id.btnEditorClear).setOnClickListener {
            engine.editorClear()
            refreshAll()
        }
        findViewById<MaterialButton>(R.id.btnStartCustom).setOnClickListener { onStartCustomClicked() }
    }

    /* ---------------- 对局流程 ---------------- */

    private fun startNewGame() {
        stopAuto()
        stopTimer()
        gameView.showHint(null)
        engagedCounted = false
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                // 每次开新局都从设置同步到引擎：设置弹层里改的规模/比例/开关
                // 只写了 SettingsStore，不同步的话 engine.n 会一直是 onCreate 时的旧值
                engine.configure(settings.n, settings.ratio, settings.countTarget)
                engine.newGame()
            }
            engine.elapsedMs = 0L
            refreshAll()
        }
    }

    private fun onCellClicked(i: Int) {
        if (engine.editMode) {
            engine.press(i)
            sound.press(engine.lights[i])
            refreshAll()
            return
        }
        if (autoJob?.isActive == true) return
        if (!engine.active || engine.won) return
        val ok = engine.press(i)
        if (!ok) return
        ensureEngaged()
        sound.press(engine.lights[i])
        refreshAll()
        if (engine.won) onWin() else schedulePar()
    }

    private fun onHintClicked() {
        if (engine.editMode) return
        if (!engine.active || engine.won) return
        val idx = engine.hintIndex()
        if (idx == null) {
            toast(R.string.toast_unsolvable)
            return
        }
        ensureEngaged()
        gameView.showHint(idx)
        refreshAll()
    }

    private fun onAutoClicked() {
        if (engine.editMode) return
        if (autoJob?.isActive == true) {
            stopAuto()
            toast(R.string.toast_auto_stopped)
            return
        }
        if (!engine.active || engine.won) {
            toast(R.string.toast_game_over)
            return
        }
        val presses = engine.autoPresses()
        if (presses == null || presses.isEmpty()) {
            toast(R.string.toast_unsolvable)
            return
        }
        ensureEngaged()
        btnAuto.setText(R.string.btn_auto_stop)
        autoJob = lifecycleScope.launch {
            for (k in presses) {
                val ok = engine.press(k)
                sound.press(engine.lights[k])
                refreshAll()
                if (ok && engine.won) {
                    onWin()
                    return@launch
                }
                delay(settings.autoStepMs.toLong())
            }
            stopAutoUi()
        }
    }

    private fun onShuffleClicked() {
        if (engine.editMode) return
        stopAuto()
        ensureEngaged()
        engine.shuffle()
        refreshAll()
        schedulePar()
        toast(R.string.toast_shuffled)
    }

    private fun setEditMode(on: Boolean) {
        stopAuto()
        engine.setEditMode(on)
        editRow.visibility = if (on) View.VISIBLE else View.GONE
        btnEditToggle.setText(if (on) R.string.btn_edit_exit else R.string.btn_edit)
        if (on) {
            stopTimer()
        } else if (engagedCounted && !engine.won) {
            startTimer()
        }
        refreshAll()
        if (on) toast(R.string.toast_edit_mode)
    }

    private fun onStartCustomClicked() {
        if (engine.startCustom()) {
            editRow.visibility = View.GONE
            btnEditToggle.setText(R.string.btn_edit)
            stopTimer()
            engine.elapsedMs = 0L
            engagedCounted = false
            engine.markCustomLayout()
            refreshAll()
            toast(R.string.toast_custom_start)
        } else {
            toast(
                if (engine.litCount() == 0) R.string.toast_custom_cleared
                else R.string.toast_custom_unsolvable
            )
        }
    }

    private fun onWin() {
        stopTimer()
        if (!engine.assisted) {
            stats.recordWin(engine.n, engine.steps, engine.elapsedMs)
        }
        refreshAll()
        gameView.startWinParticles()
        if (settings.sound) sound.win()

        val parTxt = engine.par?.let { getString(R.string.win_par, it) } ?: ""
        val perfect = if (!engine.assisted && engine.par == engine.steps) {
            getString(R.string.win_perfect)
        } else ""
        val assistTxt = if (engine.assisted) getString(R.string.win_assisted) else ""
        MaterialAlertDialogBuilder(this)
            .setTitle(if (engine.assisted) R.string.win_title_assisted else R.string.win_title)
            .setMessage(
                getString(
                    R.string.win_line,
                    fmtTime(engine.elapsedMs), engine.steps, parTxt, perfect, assistTxt
                )
            )
            .setPositiveButton(R.string.win_again) { _, _ -> startNewGame() }
            .setNegativeButton(R.string.win_look, null)
            .show()
    }

    /* ---------------- 辅助 ---------------- */

    private fun ensureEngaged() {
        if (engagedCounted) return
        engagedCounted = true
        stats.recordGame(engine.n)
        updateStatsHeader()
        startTimer()
    }

    /** 防抖重算「最少步数」（大棋盘上单次消元约几十毫秒） */
    private fun schedulePar() {
        if (engine.won) return
        parJob?.cancel()
        parJob = lifecycleScope.launch {
            delay(300)
            val p = withContext(Dispatchers.Default) {
                LightsSolver.computePar(engine.n, engine.lights)
            }
            engine.updatePar(p)
            refreshAll()
        }
    }

    private fun stopAuto() {
        autoJob?.cancel()
        autoJob = null
        stopAutoUi()
    }

    private fun stopAutoUi() {
        btnAuto.setText(R.string.btn_auto)
    }

    private fun startTimer() {
        uiHandler.removeCallbacks(tickRunnable)
        uiHandler.post(tickRunnable)
    }

    private fun stopTimer() {
        uiHandler.removeCallbacks(tickRunnable)
    }

    private fun refreshAll() {
        gameView.n = engine.n
        gameView.lights = engine.lights
        chipSize.text = getString(R.string.chip_size, engine.n, engine.n)
        chipLit.text = getString(R.string.chip_lit, engine.litCount())
        chipSteps.text = getString(R.string.chip_steps, engine.steps)
        chipPar.text = getString(R.string.chip_par, engine.par?.toString() ?: "–")
        chipTime.text = getString(R.string.chip_time, fmtTime(engine.elapsedMs))
        chipAssist.visibility = if (engine.assisted) View.VISIBLE else View.GONE
        btnUndo.isEnabled = engine.canUndo()
        btnHint.isEnabled = engine.active && !engine.won
        btnAuto.isEnabled = engine.active && !engine.won

        val ok = engine.isSolvable()
        chipSolvable.text = getString(if (ok) R.string.chip_solvable else R.string.chip_unsolvable)
        chipSolvable.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.ok else R.color.bad)
        )

        updateSolvNote()
        updateStatsHeader()
    }

    private fun updateSolvNote() {
        val parTxt = engine.par?.let { getString(R.string.solv_par, it) } ?: ""
        solvNote.text = when (engine.genMode) {
            "fix" -> getString(R.string.solv_fix, engine.ratio, engine.litCount(), parTxt)
            "scramble" -> getString(R.string.solv_scramble, engine.litCount(), parTxt)
            "custom" -> getString(R.string.solv_custom, parTxt)
            else -> getString(R.string.solv_exact, engine.litCount(), engine.ratio, parTxt)
        }
    }

    private fun updateStatsHeader() {
        val all = stats.all()
        val cur = all[engine.n]
        statTotalWins.text = all.values.sumOf { it.wins }.toString()
        statSizeWins.text = (cur?.wins ?: 0).toString()
        statSizeWinsLabel.text = getString(R.string.stat_size_wins, engine.n, engine.n)
        statSizePar.text = engine.par?.toString() ?: "–"
        statSizeBest.text = cur?.bestSteps?.let { getString(R.string.stat_best_steps, it) } ?: "–"
        statSizeBestLabel.text = getString(R.string.stat_size_best, engine.n, engine.n)
    }

    private fun fmtTime(ms: Long): String =
        String.format(Locale.US, "%02d:%02d", ms / 60000, ms / 1000 % 60)

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    /* ---------------- 生命周期 ---------------- */

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putSerializable("engine", engine.snapshot())
        out.putBoolean("engagedCounted", engagedCounted)
    }

    override fun onPause() {
        super.onPause()
        stopTimer()
        stopAuto()
    }

    override fun onResume() {
        super.onResume()
        if (engagedCounted && !engine.won && !engine.editMode) startTimer()
    }
}
