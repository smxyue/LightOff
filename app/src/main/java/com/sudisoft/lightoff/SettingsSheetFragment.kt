package com.sudisoft.lightoff

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.util.Locale

/**
 * 设置与统计底部弹层：
 * 对局设置（规模 / 亮灯比例 / 自动求解速度 / 比例目标开关）+ 按规模统计表 + 数据管理。
 */
class SettingsSheetFragment : BottomSheetDialogFragment() {

    /** 规模 / 比例 / 开关变化后回调（宿主重开一局） */
    var onGameSettingsChanged: (() -> Unit)? = null

    /** 统计数据变化后回调（宿主刷新顶部统计） */
    var onStatsChanged: (() -> Unit)? = null

    private lateinit var settings: SettingsStore
    private lateinit var stats: StatsStore

    private lateinit var sliderN: Slider
    private lateinit var valN: TextView
    private lateinit var chipsN: com.google.android.material.chip.ChipGroup
    private lateinit var sliderRatio: Slider
    private lateinit var valRatio: TextView
    private lateinit var chipsRatio: com.google.android.material.chip.ChipGroup
    private lateinit var sliderSpeed: Slider
    private lateinit var valSpeed: TextView
    private lateinit var chipsSpeed: com.google.android.material.chip.ChipGroup
    private lateinit var switchCountTarget: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var statsTable: LinearLayout

    /** 同步 chip 选中态时屏蔽回调，避免反馈环 */
    private var syncing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsStore(requireContext())
        stats = StatsStore(requireContext())

        sliderN = view.findViewById(R.id.sliderN)
        valN = view.findViewById(R.id.valN)
        chipsN = view.findViewById(R.id.chipsN)
        sliderRatio = view.findViewById(R.id.sliderRatio)
        valRatio = view.findViewById(R.id.valRatio)
        chipsRatio = view.findViewById(R.id.chipsRatio)
        sliderSpeed = view.findViewById(R.id.sliderSpeed)
        valSpeed = view.findViewById(R.id.valSpeed)
        chipsSpeed = view.findViewById(R.id.chipsSpeed)
        switchCountTarget = view.findViewById(R.id.switchCountTarget)
        statsTable = view.findViewById(R.id.statsTable)

        setupSizeControls()
        setupRatioControls()
        setupSpeedControls()
        setupSwitch()
        setupStatsButtons()
        refreshStatsTable()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    /* ---------------- 规模 ---------------- */

    private fun setupSizeControls() {
        sliderN.value = settings.n.toFloat()
        valN.text = settings.n.toString()
        syncChips(chipsN, settings.n)

        sliderN.addOnChangeListener { _, value, _ ->
            valN.text = value.toInt().toString()
        }
        sliderN.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                val v = slider.value.toInt()
                if (v != settings.n) {
                    settings.n = v
                    syncChips(chipsN, v)
                    onGameSettingsChanged?.invoke()
                }
            }
        })
        wireChips(chipsN) { v ->
            settings.n = v
            sliderN.value = v.toFloat()
            syncChips(chipsN, v)
            onGameSettingsChanged?.invoke()
        }
    }

    /* ---------------- 亮灯比例 ---------------- */

    private fun setupRatioControls() {
        sliderRatio.value = settings.ratio.toFloat()
        valRatio.text = "${settings.ratio}%"
        syncChips(chipsRatio, settings.ratio)

        sliderRatio.addOnChangeListener { _, value, _ ->
            valRatio.text = "${value.toInt()}%"
        }
        sliderRatio.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                val v = slider.value.toInt()
                if (v != settings.ratio) {
                    settings.ratio = v
                    syncChips(chipsRatio, v)
                    onGameSettingsChanged?.invoke()
                }
            }
        })
        wireChips(chipsRatio) { v ->
            settings.ratio = v
            sliderRatio.value = v.toFloat()
            syncChips(chipsRatio, v)
            onGameSettingsChanged?.invoke()
        }
    }

    /* ---------------- 自动求解速度 ---------------- */

    private fun setupSpeedControls() {
        sliderSpeed.value = settings.autoStepMs.toFloat()
        valSpeed.text = getString(R.string.sheet_speed_value, settings.autoStepMs)
        syncChips(chipsSpeed, settings.autoStepMs)

        sliderSpeed.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            settings.autoStepMs = v
            valSpeed.text = getString(R.string.sheet_speed_value, v)
            syncChips(chipsSpeed, v)
        }
        wireChips(chipsSpeed) { v ->
            settings.autoStepMs = v
            sliderSpeed.value = v.toFloat()
            valSpeed.text = getString(R.string.sheet_speed_value, v)
            syncChips(chipsSpeed, v)
        }
    }

    private fun setupSwitch() {
        switchCountTarget.isChecked = settings.countTarget
        switchCountTarget.setOnCheckedChangeListener { _, checked ->
            if (checked != settings.countTarget) {
                settings.countTarget = checked
                onGameSettingsChanged?.invoke()
            }
        }
    }

    /* ---------------- 统计与数据 ---------------- */

    private fun setupStatsButtons() {
        requireView().findViewById<com.google.android.material.button.MaterialButton>(R.id.btnExportCsv)
            .setOnClickListener {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.sheet_export_subject))
                    putExtra(Intent.EXTRA_TEXT, stats.csv())
                }
                startActivity(Intent.createChooser(intent, getString(R.string.sheet_export_subject)))
            }
        requireView().findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClearSize)
            .setOnClickListener {
                val n = settings.n
                if (stats.all().containsKey(n)) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(getString(R.string.sheet_clear_size_confirm, n))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            stats.clearSize(n)
                            refreshStatsTable()
                            onStatsChanged?.invoke()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    android.widget.Toast.makeText(
                        requireContext(), R.string.toast_no_size_record, android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        requireView().findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClearAll)
            .setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.sheet_clear_all_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        stats.clearAll()
                        refreshStatsTable()
                        onStatsChanged?.invoke()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
    }

    private fun refreshStatsTable() {
        statsTable.removeAllViews()
        val all = stats.all()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        fun cell(
            value: String,
            weight: Float,
            bold: Boolean = false,
            colorRes: Int = R.color.ink_dim,
            alignEnd: Boolean = true
        ): TextView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            gravity = if (alignEnd) Gravity.END else Gravity.START
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), colorRes))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            text = value
            setPadding(dp(3), dp(4), dp(3), dp(4))
        }

        // 表头
        val header = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(cell(getString(R.string.sheet_col_size), 1f, bold = true, colorRes = R.color.ink_faint, alignEnd = false))
        header.addView(cell(getString(R.string.sheet_col_wins), 1f, bold = true, colorRes = R.color.ink_faint))
        header.addView(cell(getString(R.string.sheet_col_games), 1f, bold = true, colorRes = R.color.ink_faint))
        header.addView(cell(getString(R.string.sheet_col_rate), 1f, bold = true, colorRes = R.color.ink_faint))
        header.addView(cell(getString(R.string.sheet_col_best), 1f, bold = true, colorRes = R.color.ink_faint))
        header.addView(cell(getString(R.string.sheet_col_fast), 1f, bold = true, colorRes = R.color.ink_faint))
        header.addView(cell(getString(R.string.sheet_col_avg), 1f, bold = true, colorRes = R.color.ink_faint))
        statsTable.addView(header)

        if (all.isEmpty()) {
            statsTable.addView(
                TextView(requireContext()).apply {
                    text = getString(R.string.sheet_stats_empty)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_faint))
                    setPadding(dp(3), dp(10), dp(3), dp(10))
                }
            )
            return
        }

        var tw = 0; var tg = 0; var ts = 0
        var best: Int? = null; var fast: Long? = null
        for ((n, s) in all) {
            tw += s.wins; tg += s.games; ts += s.sumSteps
            if (s.bestSteps != null && (best == null || s.bestSteps!! < best!!)) best = s.bestSteps
            if (s.bestTimeMs != null && (fast == null || s.bestTimeMs!! < fast!!)) fast = s.bestTimeMs

            val cur = n == settings.n
            val rate = if (s.games > 0) String.format(Locale.US, "%.1f%%", 100.0 * s.wins / s.games) else "–"
            val fastTxt = s.bestTimeMs?.let { String.format(Locale.US, "%.1fs", it / 1000.0) } ?: "–"
            val avgTxt = if (s.wins > 0) String.format(Locale.US, "%.1f", 1.0 * s.sumSteps / s.wins) else "–"

            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(cell("${n}×${n}", 1f, bold = true, colorRes = if (cur) R.color.amber else R.color.ink, alignEnd = false))
            row.addView(cell(s.wins.toString(), 1f, bold = true, colorRes = R.color.amber))
            row.addView(cell(s.games.toString(), 1f))
            row.addView(cell(rate, 1f))
            row.addView(cell(s.bestSteps?.toString() ?: "–", 1f))
            row.addView(cell(fastTxt, 1f))
            row.addView(cell(avgTxt, 1f))
            statsTable.addView(row)
        }

        // 合计
        val rate = if (tg > 0) String.format(Locale.US, "%.1f%%", 100.0 * tw / tg) else "–"
        val fastTxt = fast?.let { String.format(Locale.US, "%.1fs", it / 1000.0) } ?: "–"
        val avgTxt = if (tw > 0) String.format(Locale.US, "%.1f", 1.0 * ts / tw) else "–"
        val footer = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(cell(getString(R.string.sheet_footer_total), 1f, bold = true, colorRes = R.color.ink_faint, alignEnd = false))
        footer.addView(cell(tw.toString(), 1f, bold = true, colorRes = R.color.ink))
        footer.addView(cell(tg.toString(), 1f, bold = true, colorRes = R.color.ink))
        footer.addView(cell(rate, 1f, bold = true, colorRes = R.color.ink))
        footer.addView(cell(best?.toString() ?: "–", 1f, bold = true, colorRes = R.color.ink))
        footer.addView(cell(fastTxt, 1f, bold = true, colorRes = R.color.ink))
        footer.addView(cell(avgTxt, 1f, bold = true, colorRes = R.color.ink))
        statsTable.addView(footer)
    }

    /* ---------------- chip 工具 ---------------- */

    private fun wireChips(group: com.google.android.material.chip.ChipGroup, onPick: (Int) -> Unit) {
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            val tag = chip.tag?.toString()?.toIntOrNull() ?: continue
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked && !syncing) onPick(tag)
            }
        }
    }

    private fun syncChips(group: com.google.android.material.chip.ChipGroup, value: Int) {
        syncing = true
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            val tag = chip.tag?.toString()?.toIntOrNull() ?: continue
            chip.isChecked = tag == value
        }
        syncing = false
    }
}
