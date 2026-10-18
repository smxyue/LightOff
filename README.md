# 灭灯 Lights Out · Android 版

「灭灯」经典翻灯谜题的 Android 原生实现（Kotlin + View 体系 + Material 3）。
与同仓库 Web 版（`../index.html`）规则、求解算法完全一致。

## 玩法

- **棋盘**：n×n 方格（默认 8×8，可自定义 **2–24**），随机分布着亮灯的格子
- **亮灯比例**：默认 10%，可调 1%–60%
- **操作**：点击一格 → 该格与**上下左右**相邻格（**不含对角**）同时翻转
- **胜利**：把所有灯熄灭即获胜，计时从第一次点击开始
- **统计**：按格子规模累计胜利次数（胜利 / 局数 / 胜率 / 最少步 / 最快 / 均步），
  保存在本机，支持导出 CSV

## 功能

| 功能 | 说明 |
| --- | --- |
| 自定义规模 | 滑杆 + 数字输入 + 预设（3/5/6/8/10/12/16/20/24），三种控件同步 |
| 亮灯比例 | 滑杆 + 预设；勾选「当目标」时无解局面会自动微调（比例基本不变） |
| 保证可解 | 每局由随机灯态生成，经数学判定**必然有解**（见下方算法） |
| 最少步数 | 实时显示本局最优解步数（par），胜利时校验是否「完美通关」 |
| 提示 | 标出当前最优解中的一步（使用后本局标记「已协助」，不计胜） |
| 自动求解 | 一键演示完整解法，**速度 40ms–1s 可调**（极速/快速/中速/慢速/演示） |
| 撤销 | 每步可撤销（自动求解中也可随时停下撤销） |
| 洗牌 | 保留步数重新打乱，仍保证可解 |
| 编辑棋盘 | 自己摆灯，实时判定是否有解，满意后「开始挑战」 |
| 音效 | AudioTrack 现场合成（落子/撤销/胜利旋律），可开关 |
| 特效 | 按压涟漪、胜利粒子礼花 |

统计规则：**局数**在每局首次互动时计入；**胜利**仅在未使用提示/自动求解时计入。

## 构建

要求：JDK 17+、Android SDK（API 36.1，即 Android 16）。

```bash
# 用 Android Studio 直接打开本目录（会自动同步 Gradle），或命令行：
./gradlew :app:assembleDebug        # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 运行单元测试（求解器 + 引擎）
```

环境信息（gradle.properties / local.properties 之外无需额外配置）：
- Gradle 8.13 · AGP 8.13.2 · Kotlin 2.2.0 · compileSdk 36.1 · minSdk 24

## 代码结构

```
app/src/main/java/com/sudisoft/lightoff/
├── LightsSolver.kt        求解器（纯 JVM）：BitSet 高斯消元、最少步数、
│                          安静模式快速判定、parity 修复
├── GameFactory.kt         局面生成（保证可解）：exact / fix / scramble 三档
├── GameEngine.kt          对局状态机（纯 JVM，可单测）：落子、撤销、胜利、
│                          编辑模式、提示、自动求解、状态快照
├── GameView.kt            棋盘自绘 View：格子 / 涟漪 / 提示圈 / 粒子
├── MainActivity.kt        界面编排：计时、统计、弹窗、设置联动
├── SettingsSheetFragment.kt  设置与统计底部弹层
├── StatsStore.kt          按规模统计持久化（SharedPreferences + JSON）
├── SettingsStore.kt       游戏设置持久化
└── SoundKit.kt            AudioTrack 合成音效
```

## 算法要点

灭灯是 GF(2) 上的线性系统。设 A 为「按键 → 灯态」的影响矩阵（A = I + 网格邻接，对称），
求解 `A·x = b`：

1. **Gauss-Jordan 消元**：每行用 BitSet 表示（位 0..N-1 为系数，位 N 为右端项），
   n=24（576 未知数）也是毫秒级。出现 `0 = 1` 行即无解。
2. **最少步数（par）**：解集 = 特解 ⊕ 零空间。零空间维数 ≤16 时穷举全部 2^k 个组合
   取权重最小；否则贪心。提示/自动求解直接用任一正确解。
3. **保证可解的生成**：随机点亮目标比例的格子 → 用**安静模式**（零空间基）快速判定
   （k 次 popcount，微秒级）→ 无解时用 parity 修复：求格子集合 S 使
   `XOR_{i∈S} col_i = d`（k×N 的 GF(2) 系统，一步消元，翻转数 ≤ 零空间维数）。
   这套组合让任意规模（2–24）的生成都在几百毫秒内完成且必然可解。
4. **可解性判定**：b 可解 ⟺ b 与每个安静模式点积为 0（A 对称）。

## 更新记录

### v1.2（2026-10-18）修复设置里改棋盘规模无效

**问题**：在「设置与统计」里改棋盘规模后，即使点「新游戏」也还是旧规模。

**原因**：`GameEngine.configure()` 只在 `MainActivity.onCreate` 调用过一次。设置弹层
改规模时只更新了 `SettingsStore` 并回调 `startNewGame()`，但 `startNewGame()` 没有把
最新设置同步给引擎——`engine.n` 一直是启动时的旧值。

**修复**：`startNewGame()` 开新局前先 `engine.configure(settings.n, settings.ratio,
settings.countTarget)`（放后台线程，幂等）。新增单元测试 `reconfigureTakesEffectOnNextNewGame`
锁定该行为。已在真机验证：设置里选 5×5 → 棋盘/统计/最少步全部即时切换。

### v1.1（2026-10-18）修复启动闪退

**问题**：v1.0 安装后启动即闪退，`InflateException: You must supply a layout_height attribute`。

**原因**：布局里大量元素把 `android:layout_width / layout_height / layout_weight / layout_marginEnd`
写在了自定义 style（`WgtButton` / `StatCell` 等）里，而 `LinearLayout.LayoutParams` 只从
**XML 元素自身属性**读取布局参数，style 里的 `layout_*` 一律不生效——轻则按钮变 0 宽隐形，
重则（如本 case）连 `layout_height` 都没有而直接抛异常。这是 Android 的经典陷阱：
**布局参数必须写在布局 XML 里，不能依赖 style**。

**修复**：两个布局（`activity_main.xml` / `sheet_settings.xml`）共 31 个元素全部补上显式
布局参数（81 个属性）；同时把各 style 里的 `layout_*` 项移除，避免再次踩坑。
v1.1 已在真机（小米）验证：启动、设置弹层、提示、自动求解、洗牌均正常，logcat 无异常。

### v1.0（2026-10-18）首个可运行版本

完整实现玩法、求解器、统计、音效与特效；17 个 JVM 单元测试全部通过。

## 单元测试

`app/src/test/` 下两组 JVM 测试（`./gradlew :app:testDebugUnitTest`）：

- `LightsSolverTest`：n=2..24 打乱局面可解且解可全灭；翻转规则不含对角；
  快速判定与完整消元一致；parity 修复正确且翻转数 ≤ 零空间维数；
  par 与最小解一致；无解/全灭边界。
- `GameEngineTest`：新局可解且有 par；落子/撤销/胜利；提示与自动求解；
  洗牌保持可解；编辑模式全流程；状态快照往返；全规模生成。
