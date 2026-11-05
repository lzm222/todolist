# AGENTS.md

给 AI 编码代理看的项目说明书。**动手改代码前先读这一份**，它记录了这个项目里
「看起来可以随便改、一改就出 Bug」的地方。

面向人类的介绍、功能说明与版本修复日志在 [`README.md`](./README.md)；
JSON 导入格式的权威定义在 [`SKILL.md`](./app/src/main/assets/skills/homework-import/SKILL.md)。
三者的分工不要混：本文件讲**约束**，README 讲**故事**，SKILL 讲**格式**。

---

## 1. 项目速览

| 项 | 值 |
| --- | --- |
| 名称 | 作业清单（`rootProject.name`） |
| 包名 / namespace | `io.github.lzm222.todolist` |
| 技术栈 | Jetpack Compose + Material 3 + Room + Kotlin Coroutines |
| minSdk / targetSdk / compileSdk | 24 / 35 / 35 |
| Kotlin / AGP / KSP | 2.3.10 / 9.0.0 / 2.3.13（见 `gradle/libs.versions.toml`） |
| Room / Compose BOM | 2.8.5 / 2026.01.01 |
| JDK | 17，且 `isCoreLibraryDesugaringEnabled = true`（依赖 `java.time`） |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk` |
| 版本控制 | git 2.43.0，本地仓库，分支 `main`，无 remote（见 §8.1） |

**构建环境是 ARM64 + Proot（无 Android Studio）**，这一点决定了两处不能动的配置，见 §3。

---

## 2. 分层架构（改动必须顺着分层走）

```
app/src/main/java/io/github/lzm222/todolist/
├── MainActivity.kt          组装：建库、建 ViewModel、装 Skill、申请存储权限
├── data/
│   ├── HomeworkItem.kt      @Entity，唯一数据模型
│   ├── HomeworkDao.kt       纯 SQL，不含任何业务判断
│   ├── AppDatabase.kt       Room 单例 + MIGRATION_1_2
│   ├── HomeworkRepository.kt 业务规则（软删除语义、导入去重统计）
│   └── SampleData.kt        空态示例数据（今天/昨天/前天共 7 条）
├── parse/HomeworkJsonParser.kt  JSON → List<HomeworkItem>，带行号错误定位
├── skill/SkillInstaller.kt      把 assets 里的 SKILL.md 释放到 Download
└── ui/
    ├── HomeworkViewModel.kt     状态聚合 + 文案生成（唯一允许拼文案的地方）
    └── screens/
        ├── HomeworkScreen.kt          LazyColumn 骨架、Snackbar、日期条、空态、Skill 卡
        ├── TodayListSection.kt        当日分组列表
        ├── OverdueSection.kt          逾期栏
        ├── HomeworkItemRow.kt         单行：勾选完成 / 滑动删除
        └── ImportDialog.kt            导入弹窗（含 AssitTool）
```

分层红线：

- **DAO 只写 SQL**。不要在 DAO 里加业务判断；不要让查询返回已经聚合好的 DTO。
- **Repository 负责业务规则**：软删除 = `deleted` 置位；恢复 = 置回；导入去重靠 `insertAll` 的 rowId 判定。
- **ViewModel 负责状态聚合与文案**。用户看到的每一个中文字符串都在这里拼好，UI 层不要自己造句子。
- **Composable 只渲染**。UI 层**不出现硬编码色值**，一律 `MaterialTheme.colorScheme.*`。
- 新增数据流请走 `Flow`（`observeByDate` → `flatMapLatest` → `combine` → `stateIn`），不要引入一次性 `suspend` 查询再手工刷新。
- **多选/展开等交互状态属于 UI 层私有状态，不进 ViewModel**。`selectionMode`、`selectedIds`、`revealedId`、
  `showBatchConfirm`、`lastDeleted` 全部用 `HomeworkScreen` 里的 `remember { mutableStateOf(...) }` 持有。
  它们一旦进 `UiState`，就会给 `combine` 增加与数据无关的同步点，并把「全选范围」与「当前可见行」解耦。
- `UiState` 里允许出现**派生属性**（如 `selectableIds`、`allItems`）：用 getter 从既有字段算出，
  不新增字段、不新增 `combine` 输入。它们的存在理由是「全选必须与可见行一致」，而不是缓存。

---

## 3. 构建（两处 ARM64 配置绝对不要删）

```bash
# 首次进入环境
chmod +x ./setup_android_env.sh && ./setup_android_env.sh

# 日常构建
./gradlew :app:assembleDebug

# Agent 快速验证（只看错误与结果）
./gradlew :app:assembleDebug 2>&1 | grep -E "^(e:|w:)|error:|BUILD"
```

必须保留的两处：

1. `gradle.properties` 末尾
   ```properties
   android.aapt2.process.daemon=false
   ```
   Proot 里 AAPT2 daemon 起不来，会报 `Daemon startup failed`。

2. `app/build.gradle.kts` 中的强制换 target
   ```kotlin
   configurations.all {
       resolutionStrategy.eachDependency {
           if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
               useTarget("com.android.tools.build:aapt2:${requested.version}:linux-aarch64")
           }
       }
   }
   ```
   仓库里另有 `tools/aapt2/aapt2-arm64-v8a` 与 `setup_android_env.sh` 配套。

其他注意：

- 仓库镜像走 `settings.gradle.kts`（阿里云 + 华为云），`repositoriesMode` 是 `FAIL_ON_PROJECT_REPOS`，
  **不要**在模块级 build 文件里另加 `repositories { }`，否则构建直接失败。
- 构建成功后请自己确认「零 error、零 warning」，不要只看 `BUILD SUCCESSFUL`。

---

## 4. 数据库契约

`AppDatabase`：`version = 2`、`exportSchema = false`、库名 `homework.db`、单例双重检查锁。

**唯一性由「部分唯一索引」承担，这是本项目最关键的设计点：**

```sql
-- MIGRATION_1_2 的四个步骤，顺序不能换
DELETE FROM homework_items WHERE deleted = 1;
DROP INDEX IF EXISTS `index_homework_items_dueDate_subject_content`;
CREATE UNIQUE INDEX IF NOT EXISTS `index_homework_items_dueDate_subject_content_active`
  ON `homework_items` (`dueDate`, `subject`, `content`) WHERE `deleted` = 0;
CREATE INDEX IF NOT EXISTS `index_homework_items_dueDate_subject_content`
  ON `homework_items` (`dueDate`, `subject`, `content`);
```

为什么这样：旧的全表唯一索引会让**软删除行继续占着唯一键**，于是「删光后再导入同样的作业」
全部被判重复、插入 0 条。改成只约束 `deleted = 0` 的行之后，软删除不再挡插入。

配套注意：

- `HomeworkItem` 的 `indices` 里三项都是**普通** `Index`，没有 `unique = true`。
  Room 的 `@Entity` 表达不了部分索引，所以唯一性**只存在于迁移脚本里**。
  → 若将来升到 v3，必须用 `Migration` 显式重建这个部分索引，**不能靠 Room 自动生成**。
- 有 `fallbackToDestructiveMigration(dropAllTables = true)` 兜底，数据可用 JSON 重新导入，
  所以「升级不闪退」优先于「升级保数据」。
- 若改了实体字段，务必同时提供迁移；否则老用户会被静默清库。

### DAO 三条查询的形状是 UI 语义的一部分

```sql
-- observeByDate：当日清单，含已完成，不含已删除
WHERE deleted = 0 AND dueDate = :date ORDER BY subject ASC, sortOrder ASC, id ASC
-- observeOverdue：逾期，未完成 + 未删除 + 早于今天
WHERE deleted = 0 AND isDone = 0 AND dueDate < :today ORDER BY dueDate ASC, subject ASC, sortOrder ASC, id ASC
-- observeAllDates：日期条的候选来源
SELECT DISTINCT dueDate FROM homework_items WHERE deleted = 0 ORDER BY dueDate DESC
```

- 不要为了「性能」把 `deleted = 0` 去掉再在内存里过滤：软删除行会污染 `observeAllDates`，日期条会冒出空日期。
- 写操作只有四个：`insertAll`（`OnConflictStrategy.IGNORE`）、`setDone`、`setDeleted`、`purgeDeleted`。
  `insertAll` 返回 `List<Long>`，**`-1L` 代表被跳过**，`Repository.import` 据此算出 `(inserted, skipped)`。

---

## 5. JSON 导入格式

**权威字段名是 `name`，不是 `subject`。** SKILL.md、README 示例与解析器三处现已一致，
以 `HomeworkJsonParser` 为准：

```json
{
  "date": "2026-11-05",
  "subjects": [
    { "name": "数学", "items": ["课本 P42 第 1-5 题", "练习册 P18 全部"] },
    { "name": "语文", "items": ["背诵《静夜思》"] }
  ]
}
```

解析器行为（`HomeworkJsonParser`）：

- 顶层接受**对象**，也接受**对象数组** `[ {...}, {...} ]`（多天一起导入），其余类型报错。
- `date` 必填，`uuuu-MM-dd` 且 `ResolverStyle.STRICT`（`2026-02-30` 会被拒）。
- `subjects` 必填且非空；每项必须有非空 `name` 与数组 `items`；`items` 元素必须是字符串，空串会被静默跳过。
- 成功后返回 `Success(items, dates)`，`dates` 是去重后的日期集合；`ViewModel.importJson` 会跳到 `dates.first()`。
- 失败返回 `Error(message, line?)`，`line` 由括号配平粗略估算，用于在输入框下方显示错误块。**不清空用户输入**。
- 每次导入的 `sortOrder` 按 `date|subject` 分组从 0 递增。

改格式时的同步面：`HomeworkJsonParser` ↔ `SKILL.md`（assets） ↔ `README.md` 示例，三处必须一起改。

---

## 6. UI 契约（这些是修过的 Bug，别改回去）

### 6.1 空态必须是列表里的一段，不能整屏替换

`HomeworkScreen` 的 `LazyColumn` 渲染顺序固定：

```
item("datebar")                                    ← 恒在，永远第一个
if (state.isEmpty)        item("empty")
if (state.isToday && state.overdue.isNotEmpty()) item("overdue")
if (state.groups.isNotEmpty()) { item("todayHeader"); item("todayList") }
item("skill")
```

- **日期条不能被空态吞掉**：否则「今天没有作业」会把通往昨天/前天的入口一起隐藏，用户以为整个清单空了。
- `contentPadding = PaddingValues(bottom = 96.dp)`，别漏，否则最后一行被 FAB 挡住。
- `LazyColumn` 新增区块请继续用**字符串 key**，与现有风格一致。

### 6.2 `isEmpty` 的判定分视角

```kotlin
val isEmpty get() = if (isToday) groups.isEmpty() && overdue.isEmpty()
                    else        groups.isEmpty()
```

历史日期视角下逾期栏根本不渲染，所以**不能**把 `overdue` 算进空态判断，
否则「删光某一天」会被其他日期的逾期项掩盖，变成一片空白。

### 6.3 悬空日期必须回落

`HomeworkViewModel.init` 里有一条独立协程订阅 `repo.observeAllDates()`：
选中日期 ≠ 今天且已从数据库消失时，自动回落到今天。没有它，会停在
「标题写着 11 月 3 日、日期条里却没有 11 月 3 日」的矛盾状态。

`availableDates` 在 `combine` 中拼为 `(dates + todayStr).distinct().sortedDescending()`——**今天始终可选**。

### 6.4 完成 ≠ 滑开 ≠ 删除（三件事必须分开）

- **勾选**：`isDone` 置位，条目**留在原地**，划线 + 灰化。
- **滑开**：`anchoredDraggable` 只把行位移到 `Open` 锚点（= `-88.dp`）露出背景按钮，**不写数据库**。
- **删除**：只有用户**点击**背景那个删除按钮才调 `onDelete`，`deleted` 置位，Snackbar 提供「撤销」。
- **右滑不响应**：位移被钳在 `coerceAtMost(0)`，锚点全为非正值，向右拖动无任何位移。
- **批量删除**：长按进入多选 → 标题栏换成 `SelectionTopBar` → 删除图标弹 `BatchDeleteConfirmDialog` 二次确认 → `viewModel.deleteBatch(targets)`。
- 撤销判定靠 `msg.startsWith("已删除")`；`lastDeleted` 的类型是 `List<HomeworkItem>`，**非删除类消息必须清空它**，防止误撤销上一条。
- 单条删除与批量删除共用 `undoDeleteBatch`（长度 1 的批量）。不要为单条另开一条撤销通道。

### 6.5 滑开只是展开，绝不能「滑开即删」或「滑到头」

`HomeworkItemRow` 用的是 **`AnchoredDraggableState` + `Modifier.anchoredDraggable`**，
**不是** `SwipeToDismissBox`。这条是 v1.6 的核心，改回 `SwipeToDismissBox` 会让
「滑开只露按钮」直接失效（见 §9）。

- **为什么不能用 `SwipeToDismissBox`**：它是二态吸附（`Settled` / `Dismissed`），
  `Dismissed` 的停靠位是**整行滑出屏幕**。一旦越过阈值就吸附到那个位置，
  库本身没有「停在任意偏移量」的概念——所以无论阈值调多大，滑开都会滑到头，
  背景按钮只露出一瞬就被带走。这就是 v1.6 要修的问题。
- **锚点即位移上限**：`Open` 锚点必须写成 `-buttonWidthPx`（`buttonWidthPx` 由
  `DeleteButtonWidth = 88.dp` 换算而来）。这样「滑开的终点」在物理上就等于
  「只露出 88dp 按钮」，**天然越不过去**。不要把它改成屏幕宽度或其它魔数。
- **`anchoredDraggable` 的参数组合（已验证可编译、零 warning）**：
  ```kotlin
  val state: AnchoredDraggableState<RowAnchor> = remember(buttonWidthPx) {
      AnchoredDraggableState(                                        // 锚点必须在组合期就建好，见下方条目
          initialValue = RowAnchor.Settled,
          anchors = DraggableAnchors { RowAnchor.Settled at 0f; RowAnchor.Open at -buttonWidthPx },
      )
  }
  val flingBehavior = AnchoredDraggableDefaults.flingBehavior(   // 是 @Composable，不能包在 remember 里
      state = state,
      positionalThreshold = { distance: Float -> distance * 0.5f },  // 行程过半才吸附
      animationSpec = AnchoredDraggableDefaults.SnapAnimationSpec,
  )
  modifier.anchoredDraggable(state = state, orientation = Orientation.Horizontal,
                             enabled = !selectionMode, flingBehavior = flingBehavior)
  ```
- **不要用 `reverseDirection` 重载**：字节码确认该重载已标 `Deprecated`，用它会出 warning。
  方向靠锚点符号 + `offset` 钳制实现，不靠手势方向参数。
- **不要给 `state.positionalThreshold` / `velocityThreshold` 赋值**：这两个字段是
  `internal`（字节码后缀 `$foundation`），外部不可写。阈值只能走 `flingBehavior`。
- **不要走带阈值参数的顶层工厂函数**：它与类构造器同名、会产生解析歧义，
  且必须同时补齐 `snapAnimationSpec` / `decayAnimationSpec` 两个必填参数，
  否则报 `Cannot infer type for type parameter 'T'`。用简单构造器 + `flingBehavior` 即可。
- **位移渲染必须钳制**：`Modifier.offset { }` 里必须写 `coerceAtMost(0)`。
  `coerceAtMost(0)` 就是「右滑无响应」的实现，别删。完整写法见下方 NaN 条目。
- **锚点必须在组合期建立**：用 `AnchoredDraggableState(initialValue, anchors = DraggableAnchors {...})`
  直接构造，或 `remember(buttonWidthPx) { ... }`。**不要**只靠 `LaunchedEffect` 里的 `updateAnchors`：
  首帧布局早于 `LaunchedEffect`，那之前 `state.offset` 是 `NaN`。
- **NaN 防护必须写在两处，且不能只护一处**：`Modifier.offset { }` 的 lambda 在**布局阶段**
  每帧独立求值，与组合期算出的 `progress` 是两条不同的求值路径。
  只护 `progress` 而让 `offset` 裸调 `roundToInt()` 会直接崩：
  `IllegalArgumentException: Cannot round NaN value`（v1.6 线上就踩了这个）。
  ```kotlin
  // 组合期
  val offsetPx = state.offset
  val progress = if (offsetPx.isNaN()) 0f else (offsetPx / buttonWidthPx)
  // 布局期：注意两个分支类型必须一致，否则报 Argument type mismatch
  Modifier.offset {
      val raw = state.offset
      val dx: Int = if (raw.isNaN()) 0 else raw.roundToInt().coerceAtMost(0)
      IntOffset(dx, 0)
  }
  ```
- **锚点重建由 `remember(buttonWidthPx)` 承担**：宽度（密度）变化时 key 变化，
  整份 `AnchoredDraggableState` 连同锚点一起重建，不需要也不应该再补
  `LaunchedEffect { state.updateAnchors(...) }`。当前实现里**已无** `updateAnchors` 调用。
  若将来出现「状态必须跨宽度存活」的需求，才考虑补 effect，且必须保留组合期构造作为兜底。
- **`progress` 必须归一化到 `[0, 1]`，且在定义处取负**：`state.offset` 向左为负，
  区间是 `[-buttonWidthPx, 0]`。背景按钮的淡入靠 `alpha = progress.coerceIn(0f, 1f)` 驱动，
  若 `progress` 写成 `offset / buttonWidthPx`，结果落在 `[-1, 0]`，会被整段压成 `0`，
  **按钮完全隐形**（v1.6.2 就是这个现象：行滑开了，露出一条空白）。
  正确写法：`(-offsetPx / buttonWidthPx).coerceIn(0f, 1f)`。
  不要指望下游 `coerceIn` 替你纠正符号——它只夹范围，不取绝对值。
- **`state.offset` 绝不能在组合作用域里读**：它是可观察状态，读它会让整个 `HomeworkItemRow`
  依赖它。拖动时 offset 每帧都变 → **整行每帧重组**（`ItemContent`/`Surface`/图标/文字全部重走），
  手感就是「一顿一顿」（v1.6.3 的症状）。
  - 位移：`Modifier.offset { }` 的 lambda（布局阶段求值）。
  - 透明度：`Modifier.graphicsLayer { alpha = ... }`（绘制阶段求值）。
  - **不要**在组合期算出 `progress: Float` 再作为参数往下传——那等于把高频依赖扩散给子组件。
  - 判据：**这个值每帧都会变吗？** 是 → 走绘制/布局阶段；否 → 可以进组合。
  - `state.settledValue` 属低频（仅动画结束变化），在组合期读是可接受的。
- **展开/收起由 `isRevealed` 单向驱动**：`LaunchedEffect(isRevealed, selectionMode)` 里
  `shouldOpen = isRevealed && !selectionMode` → `animateTo(Open / Settled)`。
  行**允许长期停在 `Open`**，这是「等用户再点按钮」的载体。
- **回写给父层用 `settledValue`**：`LaunchedEffect(state)` 里
  `snapshotFlow { state.settledValue }.collect { onRevealChange(it == RowAnchor.Open) }`。
  用 `settledValue` 而非 `currentValue`/`targetValue`，避免动画途中的中间态抖动回调。
- **背景按钮可用性**：`enabled = state.settledValue == RowAnchor.Open`，
  只有真正停靠在展开锚点后才可点，避免动画途中误触。
- 展开是**互斥**的：`HomeworkScreen` 用 `revealedId: Long?` 保存唯一展开行的 id，
  `onRevealChange = { id, open -> revealedId = if (open) id else if (revealedId == id) null else revealedId }`。
  收起分支**必须**比对 id，否则旧行的延迟回调会把新行的展开态清掉。
- 滚动即收起：`Content` 里 `LaunchedEffect(listState.isScrollInProgress)`，滚动开始且 `revealedId != null` 时收起。
- 切换日期、进入多选、长按、点击删除，这些入口都必须把 `revealedId` 置空，防止跨状态残留。

原因：让行消失的应该是**数据变化**，不是组件内部状态。但「展开」本身是合法的稳定状态，
所以它可以长期存在、也可以被撤销——这是与旧实现（滑开即删）的根本区别。


### 6.6 可点击组件：命中区域 = 视觉区域

`ImportDialog` 里的工具按钮叫 **`AssitTool`**（历史上少写一个 `s`，是既有标识符，重命名需全量替换）。

它刻意不用 `IconButton + 裸 Text` 的组合，而是：

```kotlin
Row(
    Modifier
        .clip(MaterialTheme.shapes.small)
        .clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,   // ← androidx.compose.foundation
            onClick = onClick
        )
        .then(Modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            onClick(label = text) { onClick(); true }
        })
        .padding(horizontal = 8.dp, vertical = 6.dp)
)
```

新写任何「图标 + 文字」的按钮请沿用这个模式：**整块可点、有涟漪、语义合并成一个 Button**。
图标一律 `contentDescription = null`，由外层文字标签承担朗读，避免 TalkBack 读两遍。

### 6.7 其他 UI 约定

- 主题类名是 `TodoListTheme`（`ui/theme/Theme.kt`），`MainActivity` 里包一层。
- `MainActivity` 用 `permissionVersion` 驱动重组：权限状态变化后 `key(version) { HomeworkScreen(...) }`，
  Skill 卡片依赖它刷新。删掉会导致「授权后回来仍显示未授权」。
- `onResume` 里会再次尝试 `SkillInstaller.install()`，这条路径不要去掉。

---

## 7. Skill 分发

- 唯一真源：`app/src/main/assets/skills/homework-import/SKILL.md`。
- 安装目标：`/sdcard/Download/Operit/skills/homework-import/SKILL.md`。
- 幂等：`SkillInstaller` 用 SHA-256 比对，内容一致直接跳过。
- 权限：Android 11+ 需 `MANAGE_EXTERNAL_STORAGE`（`Environment.isExternalStorageManager()`），
  跳 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`；Android 10- 用 `WRITE_EXTERNAL_STORAGE` 运行时权限。
- 清单里还声明了 `READ_EXTERNAL_STORAGE`（maxSdk 32）与 `MANAGE_EXTERNAL_STORAGE`（`tools:ignore="ScopedStorage"`），
  去掉会连带影响 Skill 写入。
- 改了 `SKILL.md` 只需重新构建，安装逻辑会自动检测哈希变化并覆盖。

---

## 8. 修改流程（沿用既有节奏）

1. `grep_code` / `grep_context` 定位，**别一上来就通读全项目**。
2. 分段读全文，确认上下文与调用方。
3. 写清根因，再动代码。
4. `./gradlew :app:assembleDebug 2>&1 | grep -E "^(e:|w:)|error:|BUILD"` 确认零 error 零 warning。
5. 复制产物到 `/sdcard/Download/作业清单/作业清单-vX.Y-debug.apk`，记录文件大小与 MD5。
6. 同步更新 `README.md`：新增「修复说明」小节 + 「回归验证」清单。
7. 大改动前建议先备份到 `.fixbak/todolist-<HHMMSS>/`（现有基线：`.fixbak/todolist-172157/`）。
8. `git add -A && git commit`，**一个 Bug 一次提交**，见 §8.1。

交付目录现状（均为 20,387,672 字节）：`v1.0` / `v1.1` / `v1.2` / `v1.3` / `v1.4` / `v1.5` / `v1.6` / `v1.6.1` / `v1.6.2` / `v1.6.3`。
- v1.6 的 MD5 为 `6a2a556671625e4240c8a2357292a3cd`（滑动删除改为 `anchoredDraggable` 实现）。
- v1.6.1 的 MD5 为 `0c782dadd44e89cf61b867fdfe6e072b`（修复首帧 `Cannot round NaN value` 崩溃）。
- v1.6.2 的 MD5 为 `43d6f794890015490e6026ea264d4103`（修复 `progress` 符号导致删除按钮隐形）。
- v1.6.3 的 MD5 为 `dcb982c65d1897ad46ea398f8969db01`（修复组合期读 `offset` 导致的滑动卡顿）。

### 8.1 版本控制约定

仓库根即本工作区根，`git init -b main` 建立，首次提交 `7c56e59`
（`chore: 初始化版本控制，纳入 v1.4 基线`，56 文件 / 4102 行）。**当前无 remote，纯本地仓库。**

本地配置（**全部是本仓库级，刻意没用 `--global`**，换机器不会串味）：

| 配置 | 值 | 为什么 |
| --- | --- | --- |
| `user.name` / `user.email` | `lzm222` / `lzm222@users.noreply.github.com` | 提交署名，GitHub 隐私邮箱 |
| `core.autocrlf` | `false` | 避免 CRLF↔LF 转换把整文件变成「全改」 |
| `core.quotepath` | `false` | 中文路径（`assets/skills/homework-import/SKILL.md` 等）正常显示，不转义成 `\346\226\207` |
| `core.fileMode` | `false` | Android 上权限位恒为 777，关掉可执行位跟踪才能避免无意义 diff |

`.gitignore` 六段（28 行），**这七项必须保持被忽略，任一条丢失都会污染仓库**：

| 忽略项 | 原因 |
| --- | --- |
| `.gradle/`、`.kotlin/`、`build/`（含 `app/build`） | 构建缓存与产物。`app/build` 单目录 97M，而 `app/src` 只有 342K——源码小、产物大，产物绝不能入库 |
| `local.properties` | 内容是 `sdk.dir=/root/Android`，**机器绝对路径**，入库必冲突 |
| `.operit/`、`.backup/` | Operit 工作区运行期数据（对话、对象存储），与项目无关 |
| `.fixbak/` | 本地备份快照（`todolist-<HHMMSS>/`），大改动前临时用，不入库 |
| `*.apk` / `*.aab` / `*.log` | 随手产物。正式产物应放 `/sdcard/Download/作业清单/`，不进仓库 |

验证手段（随时可跑）：

```bash
git check-ignore -v app/build local.properties .operit .backup .fixbak .gradle .kotlin
git status --short    # 期望空输出
```

提交信息格式：`<type>: <一句话>`，type 用 `fix` / `feat` / `chore` / `docs`。
修复类提交在正文里写清「问题 → 根因 → 修复」，与 README 的修复说明表对齐。

**注意**：v1.0–v1.3 无源码留存，git 历史里只有 v1.4 之后的内容；
回溯更早版本只能看 `README.md` 的修复说明与回归验证小节，不要指望 `git log`。

---

## 9. 已知陷阱清单

| 陷阱 | 说明 |
| --- | --- |
| `LocalIndication` 导入错包 | 属于 `androidx.compose.foundation`，**不是** `androidx.compose.material3`。导错会报 `Unresolved reference 'LocalIndication'`。 |
| README 示例字段 `subject` | 解析器真正读的是 `name`，示例文案是历史遗留。改文档时顺手修正。 |
| 测试类包名 | `app/src/test`、`app/src/androidTest` 下仍是模板的 `com.java.myapplication`，与主包不一致，内容也是模板示例。跑测试前需重命名包或删除。 |
| `@Entity` 索引 ≠ 部分唯一索引 | 唯一性只在 `MIGRATION_1_2` 的 SQL 里，Room 无法从实体注解推导。升版本必须手写迁移。 |
| `AssitTool` 拼写 | 少一个 `s`。IDE 重命名会把它改掉，注意别在 diff 里产生大面积无意义改动。 |
| 空态整屏替换 | 曾经踩过：日期条被吞，用户无法切到历史日期。见 §6.1。 |
| 用 `SwipeToDismissBox` 做滑动删除 | 它是二态吸附，「已 dismiss」= **整行滑出屏幕**，无论阈值多大都停不住中间态，滑开必然滑到头。v1.6 已换成 `anchoredDraggable`，别改回去。见 §6.5。 |
| 把 `Open` 锚点写成屏幕宽度 | 锚点就是位移上限。写成屏宽行会整条滑出；必须等于 `-buttonWidthPx`（88dp），这才是「只露按钮」。见 §6.5。 |
| 用 `anchoredDraggable` 的 `reverseDirection` 重载 | 该重载字节码已标 `Deprecated`，会出 warning。方向靠锚点符号 + `offset` 钳制。见 §6.5。 |
| 给 `state.positionalThreshold` 赋值 | 该字段是 `internal`（字节码后缀 `$foundation`），外部写不了。阈值只能通过 `AnchoredDraggableDefaults.flingBehavior(positionalThreshold = ...)` 传。见 §6.5。 |
| 给 `AnchoredDraggableState` 工厂函数只传 `initialValue` | 会报 `Cannot infer type for type parameter 'T'`——工厂函数还需 `snapAnimationSpec` / `decayAnimationSpec` 两个必填参数。用简单构造器 + `flingBehavior` 更省事。见 §6.5。 |
| `flingBehavior` 包进 `remember {}` | `AnchoredDraggableDefaults.flingBehavior` 是 `@Composable`，放进 `remember` lambda 里会编译失败。直接在当前 composable 作用域调用。见 §6.5。 |
| 删掉 `offset` 里的 `coerceAtMost(0)` | 它同时承担「右滑无响应」与「位移不越界」，删了右滑就会带动行。见 §6.5。 |
| 只护 `progress` 不护 `offset` | `Modifier.offset { }` 的 lambda 在**布局阶段**每帧独立求值，早于/独立于组合期的 `progress`。只给 `progress` 加 `isNaN` 判断、`offset` 裸调 `roundToInt()`，首帧直接崩 `Cannot round NaN value`。v1.6 线上事故就是这条。见 §6.5。 |
| 用 `LaunchedEffect` 建立锚点 | 首帧布局早于 `LaunchedEffect`，那之前 `state.offset` 是 `NaN`。锚点必须用 `remember(buttonWidthPx) { AnchoredDraggableState(initialValue, anchors = ...) }` 在组合期建好。见 §6.5。 |
| `offset` 两分支类型不一致 | `if (raw.isNaN()) 0f else raw.roundToInt()...` 会被推成 `Number & Comparable<...>`，报 `Argument type mismatch`。必须 `val dx: Int = ...` 且两分支同为 `Int`。见 §6.5。 |
| `progress` 忘了取负 | `state.offset` 向左为负，`offset / buttonWidthPx` 落在 `[-1, 0]`；背景 `alpha = progress.coerceIn(0f, 1f)` 会把它整段压成 `0`，**删除按钮完全隐形**（行滑开了但只见空白）。必须 `(-offsetPx / buttonWidthPx).coerceIn(0f, 1f)`。v1.6.2 就踩了这条。见 §6.5。 |
| 在组合作用域里读 `state.offset` | 它每帧都变，读它会让整行每帧重组，表现为「一顿一顿」。位移走 `Modifier.offset { }`、透明度走 `Modifier.graphicsLayer { }`。也不要算出 `progress: Float` 往子组件传。v1.6.3 就踩了这条。见 §6.5。 |
| 用 `currentValue`/`targetValue` 回写 `onRevealChange` | 动画途中值会抖动，导致展开态反复翻转。必须用 `settledValue`。见 §6.5。 |
| 多选状态塞进 ViewModel | 选中集合是纯 UI 私有状态，进 UiState 会给 `combine` 增加无意义同步点，还会让「全选范围」与可见行脱钩。见 §2。 |
| 全选按全库算而不是按可见行算 | 逾期栏在历史日期不渲染，此时不该被全选选中。`selectableIds` 已按 `isToday` 分支处理，别改成平铺。见 §6.4。 |
| 往仓库里漏提交 `app/build` 或 `local.properties` | `app/build` 有 97M，`local.properties` 含 `/root/Android` 机器路径。`git add -A` 前先 `git status --short` 扫一眼，或用 `git add -A --dry-run` 预检。见 §8.1。 |

---

## 10. 一句话原则

> **完成与删除分离、唯一性靠部分索引、空态嵌在列表里、命中区等于视觉区、文案只在 ViewModel、滑开只露按钮。**
> 这六条是这个项目全部修复历史的沉淀，改任何一处之前先确认不会破坏它们。
