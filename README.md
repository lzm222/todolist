# 作业清单

一个用 Jetpack Compose + Material 3 + Room 写的安卓作业管理应用。核心是「**让任何格式的作业记录都能一键变成结构化清单**」。

- 包名：`io.github.lzm222.todolist`
- minSdk 24 / targetSdk 35 / JDK 17
- 产物：`app/build/outputs/apk/debug/app-debug.apk`

---

## 一、功能

### 1. 按固定 JSON 格式导入今日作业

点右下角「导入」→ 粘贴 JSON（或点剪贴板按钮自动读取）→ 确认。数据按**科目分组、每条一行**落库。

导入格式（科目 → 条目数组，日期可省略默认今天）：

```json
{
  "date": "2026-11-05",
  "subjects": [
    {
      "name": "数学",
      "items": [
        "课本 P42 第 1-5 题",
        "练习册 P18 全部"
      ]
    },
    {
      "name": "语文",
      "items": ["背诵《静夜思》", "写一篇 300 字日记"]
    }
  ]
}
```

导入弹窗的交互细节：

| 能力 | 说明 |
| --- | --- |
| 读取剪贴板 | `ContentPaste` 按钮，剪贴板无文本时提示「剪贴板里没有文本」 |
| 一键格式化 | `FormatAlignLeft` 按钮，把压缩 JSON 排版成缩进形式；非 JSON 时提示「无法格式化」 |
| 行内错误提示 | 解析失败时在输入框下方用 `errorContainer` 色块显示错误信息与行号，**不清空已输入内容**，方便就地修正 |
| 重复跳过 | 同科目+同内容视为重复，导入结果会告知「新增 N 条 / 跳过 M 条」 |

### 2. 完成与删除是两个独立动作

这是本应用与普通待办的**关键区别**：

- **勾选完成**：条目打勾 + 删除线 + 置灰，**仍然留在当日清单里**。完成不是消失，是为了让用户看到「今天这些我都做完了」。
- **滑动删除**：左右滑动条目触发删除。删除才会让条目离开清单。
- **删除可撤销**：底部 Snackbar 出现「已删除」提示时附带「撤销」按钮，点击调用软删除恢复（`deleted` 字段置回 false，不动唯一索引）。

### 3. 逾期作业单列一栏

打开应用时（`selectedDate == today`）：

- 所有**日期早于今天、且未完成、且未删除**的作业，会被归入顶部独立的「逾期作业」栏。
- 每条前有 `WarningAmber` 图标 + 红色「逾期 N 天」徽标，天数由 `ChronoUnit.DAYS.between(item.date, today)` 计算。
- 逾期栏里勾选完成 → 条目从逾期栏消失（因为它已经完成），**不会回流到今日清单**（清单按 `date == selectedDate` 过滤）。
- 逾期栏只在「看今天」时出现，翻看历史日期时隐藏。

### 4. 日期切换

顶部横向 `FilterChip` 条列出所有有作业的日期，可回看历史。非今天时右上角出现「回到今天」按钮。

---

## 二、随 app 分发的 Skill

项目内置了一套 **Skill 说明书**，教 AI 助手如何把用户随手记的任意格式内容（聊天记录、拍照识别文字、语音转写、微信群发的作业）转换成上面那个可直接导入的 JSON。

- **唯一真源**：`app/src/main/assets/skills/homework-import/SKILL.md`
- **安装位置**：`/sdcard/Download/Operit/skills/homework-import/SKILL.md`
- **安装时机**：`MainActivity.onCreate` 与 `onResume` 自动尝试安装
- **幂等更新**：`SkillInstaller` 用 SHA-256 比对内容，相同则跳过，不同则覆盖更新——所以升级 app 后 Skill 会自动同步，无需手动删旧文件
- **权限**：Android 11+ 需要「所有文件访问权限」。无权限时首页 Skill 卡片显示「去授权」，点击跳系统设置页；授权返回后 `onResume` 递增 `permissionVersion`，用 `key(version)` 强制重组刷新卡片状态，并重试安装。Android 10 及以下走普通 `WRITE_EXTERNAL_STORAGE` 运行时权限。

首页底部的 Skill 卡片会实时显示三种状态：已安装（显示路径与「重新安装」）、需要授权（显示「去授权」）、安装失败（显示原因）。

---

## 三、项目结构

```
app/src/main/java/io/github/lzm222/todolist/
├── MainActivity.kt                 入口：ViewModel 工厂、权限引导、Skill 首装
├── data/
│   ├── HomeworkItem.kt             实体：科目/内容/日期/完成/软删除
│   ├── HomeworkDao.kt              查询：按日期、逾期、批量插入、软删除与恢复
│   ├── AppDatabase.kt              Room 数据库单例
│   ├── HomeworkRepository.kt       仓储层：导入去重、切换完成、软删除
│   └── SampleData.kt               示例数据（空态的「加载示例数据」按钮）
├── parse/
│   └── HomeworkJsonParser.kt       解析 + 校验 + prettify，返回带行号的错误
├── skill/
│   └── SkillInstaller.kt           把 assets 里的 SKILL.md 落到外部存储
└── ui/
    ├── HomeworkViewModel.kt        状态聚合（today/日期列表/分组/逾期）与一次性消息
    ├── screens/
    │   ├── HomeworkScreen.kt       Scaffold 主界面、日期条、Snackbar 撤销、Skill 卡片
    │   ├── TodayListSection.kt     今日作业分组列表
    │   ├── OverdueSection.kt       逾期栏（含「逾期 N 天」徽标）
    │   ├── HomeworkItemRow.kt      单条：勾选/划线/双向滑动删除
    │   └── ImportDialog.kt         导入弹窗：剪贴板/格式化/行内报错
    └── theme/                      M3 主题（Color/Theme/Type）
```

分层原则：**DAO 只做 SQL，Repository 做业务规则（去重、软删除），ViewModel 做状态聚合与文案，Composable 只渲染**。UI 层不出现硬编码色值，全部走 `MaterialTheme.colorScheme` 语义 token，因此深浅色与动态取色都能自动适配。

---

## 四、构建

环境已就绪（JDK 17 + Android SDK）。ARM64 / Proot 环境的 AAPT2 替换已内置，首次使用请先执行一次：

```bash
chmod +x ./setup_android_env.sh
./setup_android_env.sh
```

然后：

```bash
./gradlew :app:assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # 直接装到已连接设备
./gradlew clean                     # 清理
```
> `gradle.properties` 里的 `android.aapt2.process.daemon=false` 和 `build.gradle.kts` 里强制 `aapt2 ...:linux-aarch64` 的 `configurations.all` 块是 ARM64 环境的必需配置，**请勿删除**。

### v1.1 修复说明

| 问题 | 原因 | 修复 |
| --- | --- | --- |
| 滑动删除时动画卡住 | `confirmValueChange` 里同步触发删除后直接返回 `true`，`SwipeToDismissBoxState` 永久停在 `EndToStart`；数据流若未及时移除该行（或用户撤销），组件就卡在「已滑开但还占位」的半死态 | 改为「动画播完返回 true → `LaunchedEffect` 里触发删除 → 立刻 `dismissState.reset()`」，把删除动作与滑动动画解耦；回调用 `rememberUpdatedState` 包裹，避免闭包捕获旧 item；列表行用 `key(item.id)` 绑定组件身份，防止状态串到相邻行 |
| 删光后再点「加载示例数据」提示「已存在」但列表为空 | 唯一索引 `(dueDate, subject, content)` 是全表生效的，**软删除行仍然占着唯一键**，`OnConflictStrategy.IGNORE` 让所有重新插入全部冲突返回 -1，于是 `inserted == 0` | 改用**部分唯一索引**（`WHERE deleted = 0`），只有未删除的行参与去重；Room 版本升到 2 并附 `MIGRATION_1_2`（迁移时顺带物理清除历史软删除行）。提示文案也改为三档：全部新增 / 部分跳过 / 全部重复 |

### v1.2 修复说明

| 问题 | 原因 | 修复 |
| --- | --- | --- |
| 把过去日期的任务删光后，即使当天还有任务，切换到某一天仍提示「没有作业」 | `HomeworkUiState.isEmpty` 原实现是 `groups.isEmpty() && overdue.isEmpty()`。`groups` 只包含**选中日期**的条目，而 `overdue` 是**全库** `dueDate < today AND isDone = 0` 的结果，且不区分选中日期。于是当选中历史某天时：该天任务被删空 → `groups` 为空，但 `overdue` 里还装着其它日期的逾期项 → `isEmpty == false`，界面走 `Content()` 分支；而 `Content()` 里逾期栏又被 `if (state.isToday && ...)` 挡住不渲染，最终只剩日期条和 Skill 卡片，成了**空白页**。本质是「判断空态时用了逾期数据，展示时又不用它」的对齐缺陷 | `isEmpty` 改为按视角分档：今天视角 `groups.isEmpty() && overdue.isEmpty()`（当日清单与逾期栏都是可见内容）；历史日期视角只用 `groups.isEmpty()`（逾期栏根本不展示，不得参与判断） |
| 停在某个历史日期上删光该天任务后，日期条里没有这个日期，但标题还停在它上面 | 日期条来自数据库 `DISTINCT dueDate`，日期被删空后自然消失；而 `_selectedDate` 仍指向那个已不存在的日期，`availableDates` 的兜底只是补上今天，并不会纠正选中项，造成「标题 11 月 3 日 / 日期条里没有 11 月 3 日」的自相矛盾 | 在 `init` 中单独订阅 `observeAllDates()`，一旦发现选中的历史日期已不在日期集合里，就自动 `_selectedDate` 回落到今天。回落逻辑放在独立协程而非 `combine` 的变换函数内，避免在流变换中改状态引起重入发射 |

> 顺带把 `availableDates` 的拼装统一为 `(dates + todayStr).distinct().sortedDescending()`，今天恒在日期条中、不会被去重逻辑挤掉。

### v1.3 修复说明

| 问题 | 原因 | 修复 |
| --- | --- | --- |
| 把今天的任务全部删除、并把逾期任务全部标记完成后，即使昨天/前天的任务还在，界面仍提示「今天还没有作业」，并且无法切到那些日期 | `isEmpty` 判断本身没错（今天视角下当日清单与逾期栏确实都为空），但展示层把 `isEmpty` **当成了「整个清单为空」**：`HomeworkScreen` 在 `isEmpty == true` 时用 `EmptyState` **整屏替换** `Content()`，而日期切换条（`LazyRow` + `FilterChip`）恰恰是 `Content()` 里的第一个 item。结果通往历史日期的唯一入口被空态一起吞掉，数据还在库里却无从进入，用户感受就是「没有任务」。空态越权代表了整个应用的状态 | 取消整屏空态分支，`Content()` 始终渲染；把 `EmptyState` 降级为 `LazyColumn` 内的一个占位 item（`key = "empty"`），插在**日期条之后**、逾期栏与当日清单之前。渲染顺序固定为：日期条（恒在）→ 空态占位（仅 `isEmpty` 时）→ 逾期栏 → 当日清单 → Skill 卡片。`Content()` 相应新增 `onLoadSample` / `onImport` 两个回调参数，由 `HomeworkScreen` 传入 |

> 与 v1.2 同源：两轮问题都是「空态判断的代表范围超出了它该描述的视图」。v1.2 是历史视角被逾期数据干扰（该空态时不空），v1.3 是空态吞掉导航（该导航时被替换）。本次修复后空态只描述「当前视图没有内容」，不再承担「整个 App 没有内容」的语义。

### v1.5 修复说明

| 问题 | 原因 | 修复 |
| --- | --- | --- |
| 上下滚动列表时手指带一点横向偏移，就有任务被误删 | 原来删除动作挂在 `onDismiss` 里**立即执行**，且默认判定阈值只有固定 56dp，垂直滚动中哪怕只横向漂移一点就触发删除——用户没有「我要删这一条」的意图，也没有任何确认机会 | 把「滑开」与「删除」拆成两步：① 提高判定门槛，横向漂移很难达标；② 滑动只让行停在展开态、露出背景的删除按钮，**不写数据库**；③ 用户再点这个按钮才真正调 `onDelete`。滚动一开始即自动收起已展开的行 |
| 右滑也会把行滑走，但滑开后没有任何按钮，是纯误触入口 | 正反两个方向都能滑开；而背景只实现了左侧的删除按钮，右滑出来是一片空白 | 只允许向左滑动，向右不产生任何位移 |
| 删一条要点一次、等一次提示条，清一整天的作业非常繁琐 | 删除入口只有单条滑动，没有任何多选能力 | 长按任意一行进入多选态：标题栏切换为「已选 N 项」，可逐条勾选、点「全选」按当前可见条目全选，点删除图标（选中数为 0 时禁用）弹出二次确认「删除 N 项作业？」，确认后批量软删除，并复用底部提示条的「撤销」通道一键找回 |

> 本版把「完成 / 滑开 / 删除」三件事的边界进一步拉直：**勾选**只是 `isDone` 置位、条目留在原地划线灰化；**滑开**只是展开、行允许长期停在该状态；**只有点按钮**才写 `deleted`。撤销统一走 `undoDeleteBatch`，单条删除在内部就是长度 1 的批量删除。

### v1.6 修复说明

| 问题 | 原因 | 修复 |
| --- | --- | --- |
| 左滑之后整行直接滑到头，看不到删除按钮 | v1.5 用的 `SwipeToDismissBox` 语义是**二态吸附**：只有「归位」和「已 dismiss」两个停靠位，一旦越过阈值就吸附到 dismiss 位置——而那是个**整行滑出屏幕**的位移，库本身没有「停在任意偏移量」的概念。所以无论阈值调多大，只要过了阈值就必然滑到头，背景按钮露出一瞬就被带走了 | 换掉手势底层，改用 `AnchoredDraggableState` + `Modifier.anchoredDraggable` 自控位移：定义两个锚点 `Settled at 0f` 与 `Open at -buttonWidthPx`（88dp），`Open` 的位移**恰好等于按钮宽度**，于是「滑开」的终点在物理上就等于「只露按钮」，越不过去。阈值与动画规格挂到 `AnchoredDraggableDefaults.flingBehavior(..)` 上（`positionalThreshold = { it * 0.5f }`，即行程过半才吸附） |
| 向右滑仍然会带动行 | 手势库默认双向可用 | 前景位移用 `Modifier.offset { IntOffset(state.offset.roundToInt().coerceAtMost(0), 0) }` 把偏移强制钳在 ≤ 0，配合锚点全为非正值，向右拖动无任何位移 |
| 背景按钮在行没完全展开时就能点到 | 背景层只是铺在下面，没有跟随展开进度做可用性约束 | 按钮 `enabled = state.settledValue == RowAnchor.Open`，只有真正停靠在展开锚点后才可点；同时按 `PROGRESS = offset / buttonWidthPx` 驱动背景淡入，展开过程视觉连续 |

> 对外签名未变（`isRevealed` / `onRevealChange` 照旧），因此 `TodayListSection`、`OverdueSection`、`HomeworkScreen` 一行未改；展开互斥仍由父层 `revealedId: Long?` 统一管理，收起分支按 id 比对，防止旧行的延迟回调清掉新行的展开态。

### v1.4 修复说明

| 问题 | 原因 | 修复 |
| --- | --- | --- |
| 导入弹窗里的「粘贴」「格式化」只有图标能点，点在文字上没有任何反应 | 这两个工具是「`IconButton` + 裸 `Text`」横向拼接的：`onClick` 只挂在 `IconButton` 上，旁边的 `Text` 是纯展示组件，天生不可点击。视觉上「图标 + 文字」看起来是一个按钮，实际可点区域只有那 20dp 的图标，点文字成了死区 | 抽出 `AssitTool` 组件：用 `Modifier.clickable` 把**图标 + 文字整块**包成一个可点击区域（含 `clip` 圆角与 `LocalIndication` 涟漪反馈），文字提到 `labelLarge` + `primary` 色，明确表达「这是个按钮」。同时用 `Modifier.semantics(mergeDescendants = true) { role = Role.Button; onClick(...) }` 让整块在无障碍与 UI 自动化里被识别为单个按钮 |

> 图标 `contentDescription` 置为 `null`：可点击区域已有文字标签，重复朗读「粘贴 粘贴」反而对 TalkBack 有害。


关键依赖（版本集中在 `gradle/libs.versions.toml`）：

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| Compose BOM | 2026.01.01 | UI 与 M3 |
| Room | 2.8.5 | 本地持久化（配 KSP 2.3.13） |
| Lifecycle | 2.6.1 | `lifecycle-runtime-compose` / `viewmodel-compose` |
| material-icons-extended | 随 BOM | `WarningAmber`/`ContentPaste`/`FormatAlignLeft` 等图标 |
| desugar_jdk_libs | 2.0.4 | minSdk 24 下使用 `java.time` |

---

## 五、验收清单

- [x] 示例 JSON 导入后按科目分组、每科多条正确显示
- [x] 勾选完成 → 划线置灰，但仍留在当日清单
- [x] 昨日未完成作业进入逾期栏，逾期天数计算准确
- [x] 逾期栏内勾选完成 → 消失，不回流今日清单
- [x] 滑动删除生效，Snackbar「撤销」可恢复
- [x] 重复导入给出跳过计数
- [x] 非法 JSON 行内报错且不清空输入
- [x] 剪贴板读取与一键格式化可用
- [x] 权限被拒时 Skill 卡片给出明确提示并可跳转授权
- [x] 「加载示例数据」按钮效果完整
- [x] Skill 成功落到 `/sdcard/Download/Operit/skills/homework-import/SKILL.md`
- [x] 全篇仅使用 M3 原生组件与语义色 token，无硬编码色值

### v1.1 回归验证

- [x] 滑动删除动画播完后行正常移除，不再卡在滑开状态
- [x] 删除后立即撤销，行以正常位置回归，可再次滑动
- [x] 删除全部条目后点「加载示例数据」能正常重新灌入
- [x] 部分删除后重新导入，提示「已加载 N 条，跳过 M 条重复」
- [x] 从 v1.0 升级（数据库 1 → 2）不丢未删除数据

### v1.2 回归验证

- [x] 今日任务仍在、过去日期的任务全部删除 → 切回今天，仍正常显示当日清单，不出现「没有作业」误报
- [x] 今天恰好既无当日任务也无逾期项 → 正确显示空态与「加载示例数据 / 导入 JSON」
- [x] 在某历史日期上删光该天全部任务 → 自动回到今天，日期条中不再残留该日期
- [x] 历史日期仍有任务时切过去 → 只显示该天清单，不显示逾期栏，也不误判为空
- [x] 逾期栏在「今天」视角下正常展示且不影响历史视角的空态判断

### v1.3 回归验证

- [x] 删除今天全部任务 + 把逾期任务全部标记完成 → 页面不再整屏替换成空态，日期条仍然可见可点
- [x] 上述状态下点击日期条里的昨天 / 前天 → 正常进入并显示当天清单，不再「看不到历史任务」
- [x] 上述状态下空态文案「今天还没有作业」显示在日期条下方，语义只描述当前视图，不再暗示整个清单为空
- [x] 整库真的没有任何任务（新装/清空全部）→ 空态占位正常出现，「加载示例数据」「导入 JSON」两个 chip 均可点击并生效
- [x] 历史日期视角下删空该天 → 仍按 v1.2 行为回落今天，不被 v1.3 的列表内空态破坏
- [x] 空态占位与当日清单、逾期栏互斥展示，不出现「空态 + 列表」同时堆叠

### v1.5 回归验证

- [x] 上下快速滚动列表，手指带轻微横向偏移 → 无任何行被删除、无展开态残留
- [x] 左滑一行 → 仅停在展开态并露出「删除」按钮，此时数据库未变更；再点按钮才真正删除
- [x] 右滑一行 → 无任何响应，行不动、不出现空白背景
- [x] 展开 A 行后再滑开 B 行 → A 自动收起，同时只允许一行展开
- [x] 展开某行后开始滚动 → 展开态自动收起
- [x] 长按任意行 → 进入多选，标题栏变「已选 1 项」，该行被选中且滑动展开态收起
- [x] 多选态下再长按/点击其它行 → 逐条增删选中，标题计数同步
- [x] 点「全选」→ 选中当前视图全部可见条目（历史日期不含逾期栏，故逾期项不入选）；再点 → 全部取消
- [x] 选中数为 0 时删除图标为禁用置灰态，点击无反应
- [x] 选中若干项点删除 → 弹出「删除 N 项作业？」二次确认；点「取消」不删任何数据
- [x] 确认批量删除 → 条目消失，底部提示「已删除 N 项」，点「撤销」全部恢复原位
- [x] 单条滑动删除后的撤销仍正常（内部走长度 1 的批量撤销），提示条不会出现误撤销上一条的情况

### v1.4 回归验证

- [x] 导入弹窗中点「粘贴」的**文字** → 正常读取剪贴板
- [x] 导入弹窗中点「格式化」的**文字** → 正常格式化 JSON
- [x] 点图标与点文字的命中区域一致，按下时有涟漪反馈，可点区域覆盖图标与文字之间的空隙
- [x] 剪贴板为空时点文字 → 仍给出「剪贴板里没有文本」提示
- [x] 输入非法 JSON 时点文字触发格式化 → 仍给出「无法格式化：当前内容不是合法的 JSON」

### v1.6 回归验证

- [x] 左滑一行 → 行只位移 88dp、恰好露出删除按钮，**不会滑出屏幕**
- [x] 左滑到底再松手 → 停在露出按钮的位置，不会继续滑到头
- [x] 左滑幅度不到一半就松手 → 自动弹回原位，不显示按钮
- [x] 展开后点删除按钮 → 条目消失，提示条可撤销
- [x] 右滑一行 → 无任何位移，行完全不动
- [x] 上下快速滚动列表，手指带轻微横向偏移 → 无行被展开、无行被删除
- [x] 展开 A 行后再滑开 B 行 → A 自动收起，同时只允许一行展开
- [x] 展开某行后开始滚动 → 展开态自动收起
- [x] 长按进入多选 → 已展开的行先收起，多选态下拖动不产生位移
- [x] 切换日期 / 点击删除 / 长按 → `revealedId` 均被清空，无跨状态残留

---

## 六、文档

| 文件 | 面向 | 内容 |
| --- | --- | --- |
| `AGENTS.md` | AI 编码代理 | **约束**：分层红线、不可破坏的构建配置、数据库迁移契约、UI 渲染顺序与交互约定、已知陷阱清单 |
| `README.md` | 人类读者 | **故事**：功能说明、项目结构、构建步骤、逐版本修复日志与回归清单 |
| `app/src/main/assets/skills/homework-import/SKILL.md` | AI 代理（skill） | **格式**：作业 JSON 的权威字段定义与示例 |

三者分工不要混。**改代码前先读 `AGENTS.md`**；JSON 导入格式以 `SKILL.md` 与
`HomeworkJsonParser` 为准（字段名是 `name`，不是 `subject`）。

---

## 七、版本控制

项目自 v1.4 起纳入 git 管理，仓库根就是项目根，主干分支 `main`。

```bash
git log --oneline          # 7c56e59 chore: 初始化版本控制，纳入 v1.4 基线
git status --short         # 期望空输出（干净）
./gradlew :app:assembleDebug
git add -A && git commit -m "fix: ..."
```

- **首次提交**：`7c56e59`，56 个文件 / 4102 行插入，对应 v1.4 基线（APK 20,387,672 字节）。
- **远程**：目前没有配置 remote，是纯本地仓库。要备份或协作需自行 `git remote add`。
- **历史起点**：v1.0–v1.3 没有源码留存，git 里看不到这些版本；它们的变更记录只在本 README 的「修复说明」与「回归验证」小节中。

`.gitignore` 刻意忽略以下几类，**请勿把它们提交进仓库**：

| 忽略项 | 原因 |
| --- | --- |
| `build/`（含 `app/build`） | 构建产物，单目录 97M；源码 `app/src` 仅 342K |
| `.gradle/`、`.kotlin/` | 构建缓存 |
| `local.properties` | 内容是 `sdk.dir=/root/Android`，机器相关绝对路径 |
| `.operit/`、`.backup/` | Operit 工作区的运行期数据，与项目无关 |
| `.fixbak/` | 本地备份快照 |
| `*.apk`、`*.aab`、`*.log` | 随手产物；正式 APK 归档在 `/sdcard/Download/作业清单/` |

随时可自检：

```bash
git check-ignore -v app/build local.properties .operit .backup .fixbak .gradle .kotlin
```

提交信息用 `<type>: <一句话>`，type 取 `fix` / `feat` / `chore` / `docs`；修复类提交在正文写清「问题 → 根因 → 修复」。
