/* ============================================================
 * 灭灯 Lights Out —— app.js
 * 规则：n×n 棋盘随机亮灯（默认 10%）。点击一格时，该格与
 * 上下左右相邻格（不含对角）同时翻转；全部熄灭即获胜。
 * 统计：按格子规模累计胜利次数，保存在浏览器 localStorage。
 * 依赖：solver.js → window.LOSolver（可解性判定 / 求解 / 翻转）
 * ============================================================ */
(function () {
  'use strict';

  var LOS = window.LOSolver;
  function $(id) { return document.getElementById(id); }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  var MAX_N = 24; // 自定义规模上限（2-24，求解与渲染均可胜任）

  /* ---------------- 元素引用 ---------------- */
  var el = {
    board: $('board'), boardWrap: $('boardWrap'), fx: $('fx'),
    chipSize: $('chipSize'), chipLit: $('chipLit'), chipSteps: $('chipSteps'),
    chipPar: $('chipPar'), chipTime: $('chipTime'), chipAssist: $('chipAssist'),
    chipMode: $('chipMode'), chipSolvable: $('chipSolvable'),
    winOverlay: $('winOverlay'), winEmoji: $('winEmoji'), winTitle: $('winTitle'), winLine: $('winLine'),
    btnNextRound: $('btnNextRound'), btnCloseWin: $('btnCloseWin'),
    btnNew: $('btnNew'), btnUndo: $('btnUndo'), btnHint: $('btnHint'), btnAuto: $('btnAuto'),
    btnShuffle: $('btnShuffle'), btnSound: $('btnSound'),
    btnEditToggle: $('btnEditToggle'), btnEditorRandom: $('btnEditorRandom'),
    btnEditorClear: $('btnEditorClear'), btnStartCustom: $('btnStartCustom'),
    valN: $('valN'), inN: $('inN'), inNCustom: $('inNCustom'),
    valRatio: $('valRatio'), inRatio: $('inRatio'),
    valSpeed: $('valSpeed'), inSpeed: $('inSpeed'),
    inCountTarget: $('inCountTarget'), solvNote: $('solvNote'),
    statsBody: $('statsBody'), fWins: $('fWins'), fGames: $('fGames'), fRate: $('fRate'),
    fBest: $('fBest'), fFast: $('fFast'), fAvg: $('fAvg'), statScope: $('statScope'),
    btnExportCsv: $('btnExportCsv'), btnExportJson: $('btnExportJson'),
    btnImportJson: $('btnImportJson'), btnClearSize: $('btnClearSize'), btnClearAll: $('btnClearAll'),
    fileImport: $('fileImport'), toast: $('toast'),
    statTotalWins: $('statTotalWins'), statSizeWins: $('statSizeWins'),
    statSizeWinsLabel: $('statSizeWinsLabel'), statSizePar: $('statSizePar'),
    statSizeBest: $('statSizeBest'), statSizeBestLabel: $('statSizeBestLabel')
  };

  /* ---------------- 状态 ---------------- */
  var state = {
    n: 8, ratio: 10, countTarget: true, sound: true, autoStepMs: 220,
    lights: null, steps: 0, elapsed: 0, startTs: 0, timerId: 0,
    history: [], assisted: false, won: false, active: false, engaged: false,
    editMode: false, autoRunning: false, autoToken: 0,
    par: null, genMode: 'exact', focusIdx: 0
  };
  var cells = [];

  /* ---------------- 统计存储 ---------------- */
  var STATS_KEY = 'lightsout.stats.v1';
  var SETTINGS_KEY = 'lightsout.settings.v1';
  var stats = { v: 1, sizes: {} };

  function loadStats() {
    try {
      var raw = localStorage.getItem(STATS_KEY);
      if (raw) {
        var p = JSON.parse(raw);
        if (p && typeof p === 'object' && p.sizes && typeof p.sizes === 'object') stats = p;
      }
    } catch (e) { /* 忽略损坏数据 */ }
  }
  function saveStats() {
    try { localStorage.setItem(STATS_KEY, JSON.stringify(stats)); } catch (e) {}
  }
  function ensureSize(n) {
    var k = String(n);
    if (!stats.sizes[k]) {
      stats.sizes[k] = { wins: 0, games: 0, sumSteps: 0, bestSteps: null, bestTimeMs: null };
    }
    return stats.sizes[k];
  }
  function loadSettings() {
    try {
      var raw = localStorage.getItem(SETTINGS_KEY);
      if (raw) {
        var p = JSON.parse(raw);
        if (p) {
          state.n = clamp(+p.n || 8, 2, MAX_N);
          state.ratio = clamp(+p.ratio || 10, 1, 60);
          state.countTarget = p.countTarget !== false;
          state.sound = p.sound !== false;
          state.autoStepMs = clamp(+p.autoStepMs || 220, 40, 1000);
        }
      }
    } catch (e) {}
  }
  function saveSettings() {
    try {
      localStorage.setItem(SETTINGS_KEY, JSON.stringify({
        n: state.n, ratio: state.ratio, countTarget: state.countTarget,
        sound: state.sound, autoStepMs: state.autoStepMs
      }));
    } catch (e) {}
  }

  /* ---------------- 小工具 ---------------- */
  function fmtClock(ms) {
    var s = Math.max(0, Math.floor(ms / 1000));
    var m = Math.floor(s / 60), ss = s % 60;
    return (m < 10 ? '0' : '') + m + ':' + (ss < 10 ? '0' : '') + ss;
  }
  function fmtSec(ms) { return (ms / 1000).toFixed(1) + 's'; }

  var toastTimer = 0;
  function toast(msg, type) {
    el.toast.textContent = msg;
    el.toast.className = 'toast show' + (type ? ' ' + type : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.toast.className = 'toast'; }, 2600);
  }

  function download(name, text, mime) {
    var blob = new Blob([text], { type: mime });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 0);
  }

  function shake() {
    el.board.classList.remove('shake');
    void el.board.offsetWidth;
    el.board.classList.add('shake');
  }

  /* ---------------- 局面生成（保证可解） ---------------- */
  function generateLights(n, ratio, countTarget) {
    var N = n * n;
    var target = Math.max(1, Math.min(N, Math.round(N * ratio / 100)));
    // 严格比例模式多试几次（快速判定下每次成本极低）；目标模式少量尝试即可
    var maxAttempts = countTarget ? 60 : 4000;
    var i, j, t;
    for (var attempt = 0; attempt < maxAttempts; attempt++) {
      // 部分洗牌，精确选出 target 个格子
      var order = new Array(N);
      for (i = 0; i < N; i++) order[i] = i;
      for (i = N - 1; i > 0; i--) {
        j = (Math.random() * (i + 1)) | 0;
        t = order[i]; order[i] = order[j]; order[j] = t;
      }
      var lights = new Uint8Array(N);
      for (i = 0; i < target; i++) lights[order[i]] = 1;
      if (LOS.isSolvableFast(n, lights)) return { lights: lights, mode: 'exact' };
      if (countTarget) {
        // 比例当目标：无解则用安静模式做 parity 修复（翻转 ≤ 零空间维数 个格子）
        var flips = LOS.fixParity(n, lights);
        if (flips && flips.length) {
          for (i = 0; i < flips.length; i++) lights[flips[i]] ^= 1;
          if (LOS.litCount(lights) >= 1 && LOS.isSolvableFast(n, lights)) {
            return { lights: lights, mode: 'fix' };
          }
        }
      }
      // 否则重摇，寻找精确比例的可解局面
    }
    // 兜底：由随机按键打乱出局面（必可解）
    var l3 = new Uint8Array(N);
    var presses = 2 * n + ((Math.random() * (2 * n + 1)) | 0);
    for (i = 0; i < presses; i++) LOS.applyPress(l3, n, (Math.random() * N) | 0);
    if (!LOS.litCount(l3)) l3[(Math.random() * N) | 0] = 1;
    return { lights: l3, mode: 'scramble' };
  }

  /* ---------------- 最少步数（par） ----------------
   * 在等价解（特解 + 零空间）中找权重最小者。
   * 零空间维数 ≤16 时穷举；否则贪心（大棋盘上的一个优秀解）。 */
  function computePar(n, lights) {
    var raw = LOS.solveRaw(n, lights);
    if (!raw) return null;
    var i, b, m;
    if (!raw.basis.length) return LOS.popcount(raw.x);
    if (raw.basis.length <= 16) {
      var bestX = raw.x, best = LOS.popcount(raw.x);
      var limit = 1 << raw.basis.length;
      for (m = 1; m < limit; m++) {
        var cand = raw.x;
        for (b = 0; b < raw.basis.length; b++) if (m & (1 << b)) cand ^= raw.basis[b];
        var w = LOS.popcount(cand);
        if (w < best) { best = w; bestX = cand; }
      }
      return best;
    }
    var x = raw.x;
    best = LOS.popcount(x);
    for (var pass = 0; pass < 32; pass++) {
      var improved = false;
      for (i = 0; i < raw.basis.length; i++) {
        w = LOS.popcount(x ^ raw.basis[i]);
        if (w < best) { x ^= raw.basis[i]; best = w; improved = true; }
      }
      if (!improved) break;
    }
    return best;
  }

  /* ---------------- 音效 ---------------- */
  var audio = null;
  function ensureAudio() {
    if (!state.sound) return null;
    if (!audio) {
      var AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return null;
      audio = new AC();
    }
    if (audio.state === 'suspended') audio.resume();
    return audio;
  }
  function tone(freq, dur, type, vol, delay) {
    var ac = ensureAudio();
    if (!ac) return;
    dur = dur || 0.07; type = type || 'square'; vol = vol || 0.03; delay = delay || 0;
    var t0 = ac.currentTime + delay;
    var osc = ac.createOscillator(), g = ac.createGain();
    osc.type = type;
    osc.frequency.setValueAtTime(freq, t0);
    g.gain.setValueAtTime(vol, t0);
    g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
    osc.connect(g); g.connect(ac.destination);
    osc.start(t0); osc.stop(t0 + dur + 0.02);
  }
  var sfx = {
    press: function (on) { tone(on ? 540 : 400, 0.06, 'square', 0.028); },
    undo: function () { tone(300, 0.09, 'triangle', 0.03); },
    win: function () { [523, 659, 784, 1047].forEach(function (f, i) { tone(f, 0.18, 'triangle', 0.045, i * 0.11); }); },
    bad: function () { tone(170, 0.16, 'sawtooth', 0.028); }
  };

  /* ---------------- 胜利粒子特效 ---------------- */
  var particles = [], fxRAF = 0;
  function fxBurst() {
    var w = el.boardWrap.clientWidth, h = el.boardWrap.clientHeight;
    var dpr = Math.min(2, window.devicePixelRatio || 1);
    el.fx.width = Math.max(1, w * dpr);
    el.fx.height = Math.max(1, h * dpr);
    var ctx = el.fx.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    var colors = ['#ffd166', '#2fe3c0', '#7cc4ff', '#ffffff', '#ff9f43'];
    for (var i = 0; i < 90; i++) {
      var a = Math.random() * Math.PI * 2;
      var sp = 2 + Math.random() * 6;
      particles.push({
        x: w / 2 + (Math.random() - 0.5) * w * 0.5,
        y: h / 2 + (Math.random() - 0.5) * h * 0.4,
        vx: Math.cos(a) * sp, vy: Math.sin(a) * sp - 2,
        g: 0.12, life: 1, decay: 0.008 + Math.random() * 0.012,
        size: 2 + Math.random() * 4,
        color: colors[(Math.random() * colors.length) | 0]
      });
    }
    cancelAnimationFrame(fxRAF);
    var step = function () {
      ctx.clearRect(0, 0, w, h);
      particles = particles.filter(function (p) { return p.life > 0; });
      for (var i = 0; i < particles.length; i++) {
        var p = particles[i];
        p.x += p.vx; p.y += p.vy; p.vy += p.g; p.vx *= 0.99; p.life -= p.decay;
        ctx.globalAlpha = Math.max(0, p.life);
        ctx.fillStyle = p.color;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.globalAlpha = 1;
      if (particles.length) fxRAF = requestAnimationFrame(step);
      else ctx.clearRect(0, 0, w, h);
    };
    step();
  }

  /* ---------------- 棋盘渲染 ---------------- */
  function renderBoard() {
    el.board.style.setProperty('--n', String(state.n));
    var frag = document.createDocumentFragment();
    cells = [];
    var N = state.n * state.n;
    for (var i = 0; i < N; i++) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'cell';
      b.setAttribute('role', 'gridcell');
      b.setAttribute('aria-label', '第 ' + (Math.floor(i / state.n) + 1) + ' 行第 ' + (i % state.n + 1) + ' 列');
      b.dataset.i = String(i);
      frag.appendChild(b);
      cells.push(b);
    }
    el.board.innerHTML = '';
    el.board.appendChild(frag);
    refreshAllCells();
  }
  function refreshAllCells() {
    for (var i = 0; i < cells.length; i++) cells[i].classList.toggle('lit', !!state.lights[i]);
  }
  function ripple(i) {
    var cell = cells[i];
    if (!cell) return;
    var sp = document.createElement('span');
    sp.className = 'ripple';
    cell.appendChild(sp);
    setTimeout(function () { sp.remove(); }, 450);
  }

  /* ---------------- 计时 ---------------- */
  function startTimer() {
    if (state.timerId) return;
    state.startTs = Date.now() - state.elapsed;
    state.timerId = setInterval(function () {
      state.elapsed = Date.now() - state.startTs;
      el.chipTime.textContent = fmtClock(state.elapsed);
    }, 250);
  }
  function stopTimer() {
    if (state.timerId) { clearInterval(state.timerId); state.timerId = 0; }
  }

  /* ---------------- 状态栏 ---------------- */
  function updateChips() {
    el.chipSize.textContent = state.n + '×' + state.n;
    el.chipLit.textContent = LOS.litCount(state.lights);
    el.chipSteps.textContent = state.steps;
    schedulePar();
  }
  function updateUndoBtn() { el.btnUndo.disabled = !(state.active && !state.won && state.history.length); }
  function updateAssistChip() { el.chipAssist.classList.toggle('hidden', !state.assisted); }

  var parTimer = 0;
  function schedulePar() {
    clearTimeout(parTimer);
    var run = function () {
      state.par = computePar(state.n, state.lights);
      var txt = state.par == null ? '–' : String(state.par);
      el.chipPar.textContent = txt;
      el.statSizePar.textContent = txt;
      updateSolvNote(state.genMode);
    };
    if (state.n <= 10) run();
    else parTimer = setTimeout(run, 200);
  }

  function updateSolvNote(mode) {
    var lit = LOS.litCount(state.lights);
    var pct = (100 * lit / (state.n * state.n)).toFixed(1);
    var txt;
    if (mode === 'fix') {
      txt = '目标 ' + state.ratio + '% 的纯随机布局恰好无解，已微调至 ' + lit + ' 格（' + pct + '%），保证可解。';
    } else if (mode === 'scramble') {
      txt = '该规模下未找到精确比例的布局，已改用随机按键打乱生成（保证可解，当前 ' + lit + ' 格）。';
    } else {
      txt = '已随机点亮 ' + lit + ' 格（目标 ' + state.ratio + '%），数学上保证可解。';
    }
    if (state.par != null) txt += ' 最少 ' + state.par + ' 步可以完成。';
    el.solvNote.textContent = txt;
  }

  function updateSolvableChip() {
    var ok = LOS.isSolvableFast(state.n, state.lights);
    el.chipSolvable.textContent = ok ? '布局可解 ✓' : '布局无解 ✗';
    el.chipSolvable.classList.toggle('good', ok);
    el.chipSolvable.classList.toggle('bad', !ok);
  }

  /* ---------------- 顶部统计 ---------------- */
  function updateHero() {
    var total = 0;
    Object.keys(stats.sizes).forEach(function (k) { total += stats.sizes[k].wins || 0; });
    var cur = stats.sizes[String(state.n)] || { wins: 0, bestSteps: null };
    el.statTotalWins.textContent = total;
    el.statSizeWins.textContent = cur.wins;
    el.statSizeWinsLabel.textContent = state.n + '×' + state.n + ' 胜利';
    el.statSizePar.textContent = state.par == null ? '–' : String(state.par);
    el.statSizeBestLabel.textContent = state.n + '×' + state.n + ' 最佳';
    el.statSizeBest.textContent = cur.bestSteps == null ? '–' : cur.bestSteps + ' 步';
  }

  /* ---------------- 对局流程 ---------------- */
  function newGame() {
    cancelAuto();
    clearHint();
    hideOverlay();
    var gen = generateLights(state.n, state.ratio, state.countTarget);
    state.lights = gen.lights;
    state.genMode = gen.mode;
    state.steps = 0; state.elapsed = 0; state.history = [];
    state.assisted = false; state.won = false; state.active = true; state.engaged = false;
    state.editMode = false;
    el.chipMode.classList.add('hidden');
    el.btnStartCustom.classList.add('hidden');
    el.btnEditToggle.textContent = '编辑棋盘';
    stopTimer();
    el.chipTime.textContent = '00:00';
    renderBoard();
    updateUndoBtn();
    updateAssistChip();
    updateChips();
    updateHero();
    updateSolvNote(gen.mode);
    updateSolvableChip();
  }

  function markEngaged() {
    if (state.engaged) return;
    state.engaged = true;
    ensureSize(state.n).games++;
    saveStats();
    updateStatsUI();
    startTimer();
  }

  function doPress(i) {
    markEngaged();
    state.history.push({ lights: Uint8Array.from(state.lights), steps: state.steps });
    if (state.history.length > 400) state.history.shift();
    LOS.applyPress(state.lights, state.n, i);
    state.steps++;
    clearHint();
    ripple(i);
    sfx.press(!!state.lights[i]);
    refreshAllCells();
    updateUndoBtn();
    updateChips();
    checkWin();
  }

  function undo() {
    if (state.editMode || !state.active || state.won || !state.history.length) return;
    cancelAuto();
    var snap = state.history.pop();
    state.lights = snap.lights;
    state.steps = snap.steps;
    refreshAllCells();
    updateUndoBtn();
    updateChips();
    sfx.undo();
  }

  function checkWin() {
    if (state.editMode || !state.active || state.won) return;
    if (!LOS.isCleared(state.lights)) return;
    state.won = true;
    state.active = false;
    stopTimer();
    var st = ensureSize(state.n);
    if (!state.assisted) {
      st.wins++;
      st.sumSteps += state.steps;
      if (st.bestSteps == null || state.steps < st.bestSteps) st.bestSteps = state.steps;
      if (st.bestTimeMs == null || state.elapsed < st.bestTimeMs) st.bestTimeMs = state.elapsed;
    }
    saveStats();
    updateStatsUI();
    updateHero();
    showWinOverlay();
    sfx.win();
    fxBurst();
  }

  function showWinOverlay() {
    var parTxt = state.par != null ? ' / 最少可以做到 ' + state.par + ' 步' : '';
    var perfect = (!state.assisted && state.par != null && state.steps === state.par) ? ' · 完美通关' : '';
    var assistTxt = state.assisted ? ' · 使用过协助，不计入胜利' : '';
    el.winEmoji.textContent = state.assisted ? '🛠️' : '🎉';
    el.winTitle.textContent = state.assisted ? '全部熄灭！（协助模式）' : '全部熄灭！';
    el.winLine.textContent = '用时 ' + fmtClock(state.elapsed) + ' · 步数 ' + state.steps + parTxt + perfect + assistTxt;
    el.winOverlay.classList.remove('hidden');
  }
  function hideOverlay() { el.winOverlay.classList.add('hidden'); }

  /* ---------------- 提示 / 自动求解 ---------------- */
  var hintTimer = 0;
  function clearHint() {
    clearTimeout(hintTimer);
    for (var i = 0; i < cells.length; i++) cells[i].classList.remove('hint');
  }
  function markAssisted() {
    if (!state.assisted) { state.assisted = true; updateAssistChip(); }
  }
  function hint() {
    if (state.editMode || !state.active || state.won) return;
    var sol = LOS.solve(state.n, state.lights, { minimal: false });
    if (!sol) { toast('当前局面无解，可以「洗牌」或开一局新的', 'warn'); shake(); sfx.bad(); return; }
    if (!sol.presses.length) return;
    markEngaged();
    markAssisted();
    clearHint();
    var k = sol.presses[0];
    if (cells[k]) cells[k].classList.add('hint');
    hintTimer = setTimeout(clearHint, 3000);
    toast('提示：试试标出的格子');
  }

  function autoSolve() {
    if (state.editMode) return;
    if (state.autoRunning) { cancelAuto(); toast('已停止自动求解'); return; }
    if (!state.active || state.won) { toast('本局已结束，先开一局新的', 'warn'); return; }
    var sol = LOS.solve(state.n, state.lights, { minimal: false });
    if (!sol || !sol.presses.length) { toast('当前局面无解', 'warn'); shake(); sfx.bad(); return; }
    markEngaged();
    markAssisted();
    state.autoRunning = true;
    el.btnAuto.textContent = '停止求解';
    var token = ++state.autoToken;
    var queue = sol.presses.slice();
    var tick = function () {
      if (token !== state.autoToken) return;
      if (!queue.length || state.won) {
        state.autoRunning = false;
        el.btnAuto.textContent = '自动求解';
        checkWin();
        return;
      }
      doPress(queue.shift());
      setTimeout(tick, state.autoStepMs);
    };
    setTimeout(tick, 160);
  }
  function cancelAuto() {
    state.autoToken++;
    state.autoRunning = false;
    el.btnAuto.textContent = '自动求解';
  }

  function shuffleBoard() {
    if (state.editMode) return;
    cancelAuto();
    markEngaged();
    var N = state.n * state.n;
    var presses = state.n + ((Math.random() * (state.n * 2 + 1)) | 0);
    for (var p = 0; p < presses; p++) LOS.applyPress(state.lights, state.n, (Math.random() * N) | 0);
    if (!LOS.litCount(state.lights)) state.lights[(Math.random() * N) | 0] = 1;
    state.history.length = 0;
    refreshAllCells();
    updateUndoBtn();
    updateChips();
    toast('已重新打乱（仍保证可解）');
  }

  /* ---------------- 编辑模式 ---------------- */
  function setEditMode(on) {
    cancelAuto();
    clearHint();
    hideOverlay();
    state.editMode = on;
    el.chipMode.classList.toggle('hidden', !on);
    el.btnStartCustom.classList.toggle('hidden', !on);
    el.btnEditToggle.textContent = on ? '退出编辑' : '编辑棋盘';
    if (on) {
      state.active = false;
      state.won = false;
      stopTimer();
      updateSolvableChip();
      toast('编辑模式：点击格子自由摆灯');
    } else {
      updateSolvableChip();
    }
  }
  function editToggleCell(i) {
    state.lights[i] ^= 1;
    ripple(i);
    sfx.press(!!state.lights[i]);
    refreshAllCells();
    updateChips();
    updateSolvableChip();
  }
  function editorFill() {
    var N = state.n * state.n;
    var target = Math.max(1, Math.min(N, Math.round(N * state.ratio / 100)));
    var order = new Array(N), i, j, t;
    for (i = 0; i < N; i++) order[i] = i;
    for (i = N - 1; i > 0; i--) {
      j = (Math.random() * (i + 1)) | 0;
      t = order[i]; order[i] = order[j]; order[j] = t;
    }
    state.lights.fill(0);
    for (i = 0; i < target; i++) state.lights[order[i]] = 1;
    refreshAllCells();
    updateChips();
    updateSolvableChip();
  }
  function startCustom() {
    if (!LOS.isSolvableFast(state.n, state.lights)) {
      toast('这个布局无解，换一种摆法', 'warn'); shake(); sfx.bad(); return;
    }
    if (!LOS.litCount(state.lights)) {
      toast('棋盘已经是全灭状态，先点亮几盏', 'warn'); return;
    }
    setEditMode(false);
    state.steps = 0; state.elapsed = 0; state.history = [];
    state.assisted = false; state.won = false; state.active = true; state.engaged = false;
    stopTimer();
    el.chipTime.textContent = '00:00';
    refreshAllCells();
    updateUndoBtn();
    updateAssistChip();
    updateChips();
    el.solvNote.textContent = '自定义布局 · 已保证可解' + (state.par != null ? ' · 最少 ' + state.par + ' 步' : '');
    updateSolvableChip();
    toast('挑战开始！');
  }

  /* ---------------- 统计表 ---------------- */
  function updateStatsUI() {
    var keys = Object.keys(stats.sizes).map(Number).filter(isFinite).sort(function (a, b) { return a - b; });
    var html = '';
    if (!keys.length) {
      html = '<tr class="empty"><td colspan="7">还没有对局记录 —— 赢一局就会按规模记下来</td></tr>';
    }
    keys.forEach(function (n) {
      var s = stats.sizes[String(n)];
      var rate = s.games ? (100 * s.wins / s.games).toFixed(1) + '%' : '–';
      html += '<tr class="' + (n === state.n ? 'cur' : '') + '">' +
        '<td>' + n + '×' + n + '</td>' +
        '<td class="win">' + s.wins + '</td>' +
        '<td>' + s.games + '</td>' +
        '<td>' + rate + '</td>' +
        '<td>' + (s.bestSteps == null ? '–' : s.bestSteps) + '</td>' +
        '<td>' + (s.bestTimeMs == null ? '–' : fmtSec(s.bestTimeMs)) + '</td>' +
        '<td>' + (s.wins ? (s.sumSteps / s.wins).toFixed(1) : '–') + '</td></tr>';
    });
    el.statsBody.innerHTML = html;

    var tw = 0, tg = 0, ts = 0, best = null, fast = null;
    keys.forEach(function (n) {
      var s = stats.sizes[String(n)];
      tw += s.wins; tg += s.games; ts += s.sumSteps;
      if (s.bestSteps != null && (best == null || s.bestSteps < best)) best = s.bestSteps;
      if (s.bestTimeMs != null && (fast == null || s.bestTimeMs < fast)) fast = s.bestTimeMs;
    });
    el.fWins.textContent = tw;
    el.fGames.textContent = tg;
    el.fRate.textContent = tg ? (100 * tw / tg).toFixed(1) + '%' : '–';
    el.fBest.textContent = best == null ? '–' : best;
    el.fFast.textContent = fast == null ? '–' : fmtSec(fast);
    el.fAvg.textContent = tw ? (ts / tw).toFixed(1) : '–';
  }

  /* ---------------- 导入导出 ---------------- */
  el.btnExportCsv.addEventListener('click', function () {
    var keys = Object.keys(stats.sizes).map(Number).sort(function (a, b) { return a - b; });
    var lines = ['\uFEFF规模,胜利,局数,胜率(%),最少步,最快(秒),平均步数'];
    keys.forEach(function (n) {
      var s = stats.sizes[String(n)];
      lines.push([
        n + 'x' + n, s.wins, s.games,
        s.games ? (100 * s.wins / s.games).toFixed(1) : '',
        s.bestSteps == null ? '' : s.bestSteps,
        s.bestTimeMs == null ? '' : (s.bestTimeMs / 1000).toFixed(1),
        s.wins ? (s.sumSteps / s.wins).toFixed(1) : ''
      ].join(','));
    });
    download('lightsout-stats.csv', lines.join('\r\n'), 'text/csv');
    toast('已导出 CSV');
  });

  el.btnExportJson.addEventListener('click', function () {
    download('lightsout-stats.json', JSON.stringify(stats, null, 2), 'application/json');
    toast('已备份记录');
  });

  el.btnImportJson.addEventListener('click', function () { el.fileImport.click(); });
  el.fileImport.addEventListener('change', function () {
    var f = el.fileImport.files && el.fileImport.files[0];
    if (!f) return;
    var reader = new FileReader();
    reader.onload = function () {
      try {
        var p = JSON.parse(reader.result);
        if (!p || typeof p !== 'object' || !p.sizes || typeof p.sizes !== 'object') throw new Error('bad format');
        if (!confirm('导入将覆盖当前全部统计记录，确定继续？')) return;
        stats = { v: 1, sizes: {} };
        Object.keys(p.sizes).forEach(function (k) {
          var s = p.sizes[k] || {};
          stats.sizes[k] = {
            wins: +s.wins || 0, games: +s.games || 0, sumSteps: +s.sumSteps || 0,
            bestSteps: s.bestSteps == null ? null : +s.bestSteps,
            bestTimeMs: s.bestTimeMs == null ? null : +s.bestTimeMs
          };
        });
        saveStats();
        updateStatsUI();
        updateHero();
        toast('记录已恢复');
      } catch (err) {
        toast('文件解析失败：不是有效的记录备份', 'bad');
      }
      el.fileImport.value = '';
    };
    reader.readAsText(f);
  });

  el.btnClearSize.addEventListener('click', function () {
    var k = String(state.n);
    if (!stats.sizes[k]) { toast('当前规模还没有记录'); return; }
    if (!confirm('确定清除 ' + state.n + '×' + state.n + ' 的统计记录？')) return;
    delete stats.sizes[k];
    saveStats();
    updateStatsUI();
    updateHero();
    toast('已清除本规模记录');
  });

  el.btnClearAll.addEventListener('click', function () {
    if (!confirm('确定清除全部统计记录？此操作不可恢复')) return;
    stats = { v: 1, sizes: {} };
    saveStats();
    updateStatsUI();
    updateHero();
    toast('已清空全部记录');
  });

  /* ---------------- 设置 ---------------- */
  function syncSettingsUI() {
    el.valN.textContent = String(state.n);
    el.inN.value = String(state.n);
    el.inNCustom.value = String(state.n);
    el.valRatio.textContent = state.ratio + '%';
    el.inRatio.value = String(state.ratio);
    el.valSpeed.textContent = state.autoStepMs + 'ms/步';
    el.inSpeed.value = String(state.autoStepMs);
    el.inCountTarget.checked = state.countTarget;
    syncPresetActive();
  }
  function syncPresetActive() {
    document.querySelectorAll('.presets [data-n]').forEach(function (b) {
      b.classList.toggle('active', +b.dataset.n === state.n);
    });
    document.querySelectorAll('.presets [data-r]').forEach(function (b) {
      b.classList.toggle('active', +b.dataset.r === state.ratio);
    });
    document.querySelectorAll('.presets [data-s]').forEach(function (b) {
      b.classList.toggle('active', +b.dataset.s === state.autoStepMs);
    });
  }
  function updateSoundBtn() {
    el.btnSound.textContent = state.sound ? '🔊' : '🔇';
    el.btnSound.title = state.sound ? '音效：开' : '音效：关';
  }

  el.inN.addEventListener('input', function () {
    state.n = clamp(+el.inN.value || 8, 2, MAX_N);
    el.valN.textContent = String(state.n);
    syncPresetActive();
  });
  el.inN.addEventListener('change', function () { saveSettings(); newGame(); });
  el.inNCustom.addEventListener('input', function () {
    var v = parseInt(el.inNCustom.value, 10);
    if (isFinite(v) && v >= 2 && v <= MAX_N) {
      state.n = v;
      el.valN.textContent = String(v);
      el.inN.value = String(v);
      syncPresetActive();
    }
  });
  el.inNCustom.addEventListener('change', function () {
    var v = parseInt(el.inNCustom.value, 10);
    state.n = clamp(isFinite(v) ? v : state.n, 2, MAX_N);
    syncSettingsUI();
    saveSettings();
    newGame();
  });
  el.inRatio.addEventListener('input', function () {
    state.ratio = clamp(+el.inRatio.value || 10, 1, 60);
    el.valRatio.textContent = state.ratio + '%';
    syncPresetActive();
  });
  el.inRatio.addEventListener('change', function () { saveSettings(); newGame(); });
  el.inSpeed.addEventListener('input', function () {
    state.autoStepMs = clamp(+el.inSpeed.value || 220, 40, 1000);
    el.valSpeed.textContent = state.autoStepMs + 'ms/步';
    syncPresetActive();
  });
  el.inSpeed.addEventListener('change', function () { saveSettings(); });
  el.inCountTarget.addEventListener('change', function () {
    state.countTarget = el.inCountTarget.checked;
    saveSettings();
    newGame();
  });

  document.querySelectorAll('.presets [data-n]').forEach(function (b) {
    b.addEventListener('click', function () {
      state.n = clamp(+b.dataset.n, 2, MAX_N);
      syncSettingsUI();
      saveSettings();
      newGame();
    });
  });
  document.querySelectorAll('.presets [data-r]').forEach(function (b) {
    b.addEventListener('click', function () {
      state.ratio = clamp(+b.dataset.r, 1, 60);
      syncSettingsUI();
      saveSettings();
      newGame();
    });
  });
  document.querySelectorAll('.presets [data-s]').forEach(function (b) {
    b.addEventListener('click', function () {
      state.autoStepMs = clamp(+b.dataset.s, 40, 1000);
      syncSettingsUI();
      saveSettings();
    });
  });

  /* ---------------- 按钮 ---------------- */
  el.btnNew.addEventListener('click', function () { newGame(); });
  el.btnUndo.addEventListener('click', undo);
  el.btnHint.addEventListener('click', hint);
  el.btnAuto.addEventListener('click', autoSolve);
  el.btnShuffle.addEventListener('click', shuffleBoard);
  el.btnSound.addEventListener('click', function () {
    state.sound = !state.sound;
    saveSettings();
    updateSoundBtn();
    if (state.sound) sfx.press(true);
  });
  el.btnEditToggle.addEventListener('click', function () { setEditMode(!state.editMode); });
  el.btnEditorRandom.addEventListener('click', editorFill);
  el.btnEditorClear.addEventListener('click', function () {
    state.lights.fill(0);
    refreshAllCells();
    updateChips();
    updateSolvableChip();
  });
  el.btnStartCustom.addEventListener('click', startCustom);
  el.btnNextRound.addEventListener('click', function () { newGame(); });
  el.btnCloseWin.addEventListener('click', hideOverlay);

  /* ---------------- 棋盘点击 ---------------- */
  function onCellClick(e) {
    var cell = e.target.closest ? e.target.closest('.cell') : null;
    if (!cell) return;
    var i = +cell.dataset.i;
    state.focusIdx = i;
    if (state.editMode) { editToggleCell(i); return; }
    if (!state.active || state.won || state.autoRunning) return;
    doPress(i);
  }
  el.board.addEventListener('click', onCellClick);

  /* ---------------- 键盘 ---------------- */
  document.addEventListener('keydown', function (e) {
    var t = e.target;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
    var key = e.key;
    var lower = key.toLowerCase();
    if (lower === 'n') { e.preventDefault(); newGame(); return; }
    if (lower === 'z') { e.preventDefault(); undo(); return; }
    if (lower === 'h') { e.preventDefault(); hint(); return; }
    if (key === 'Escape') { hideOverlay(); return; }
    if (key.indexOf('Arrow') === 0) {
      e.preventDefault();
      var n = state.n, i = state.focusIdx;
      var r = Math.floor(i / n), c = i % n;
      if (key === 'ArrowLeft') c = Math.max(0, c - 1);
      else if (key === 'ArrowRight') c = Math.min(n - 1, c + 1);
      else if (key === 'ArrowUp') r = Math.max(0, r - 1);
      else if (key === 'ArrowDown') r = Math.min(n - 1, r + 1);
      state.focusIdx = r * n + c;
      var cell = cells[state.focusIdx];
      if (cell) cell.focus();
    }
  });

  /* ---------------- 启动 ---------------- */
  function init() {
    loadStats();
    loadSettings();
    syncSettingsUI();
    updateSoundBtn();
    el.statScope.textContent = '记录保存在本机浏览器';
    newGame();
    updateStatsUI();
    updateHero();
  }
  init();
})();
