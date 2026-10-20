# 仓库内搜索设计（文件页过滤 + 全仓库递归搜索）

> 状态：已实现（2026-10-18）。本文是设计记录，行号以当时代码为准，后续可能漂移。

## 需求

仓库详情界面（`RepoDetailActivity`）文件页增加搜索能力：

1. 过滤当前界面显示的文件名 / 文件夹名（实时）；
2. 递归搜索整个仓库的文件名；
3. 搜索指定文件（可按类型过滤）的文件内容；
4. 选项：大小写敏感开关、文件类型 glob 过滤。

## 入口结构

工具栏与搜索条分工（2026-10-18 改版，起因：SearchView 折叠展开和其他图标挤同一条工具栏，
输入框只剩两百多像素、标题也被吃光；2026-10-20 二次收紧，见下）：

- **工具栏常驻图标只保留核心入口**：排序（仅文件页）、开关抽屉（抽屉里是拉取/推送等
  仓库操作，必须常驻）。**「搜索」与「编辑标签」都在溢出菜单**，搜索排第一项。
- **ActionBar 不再显示返回箭头**（2026-10-20）：仓库名常是长路径名，411dp 宽的工具栏上
  箭头要占约 57dp。去掉后标题左边界从约 72.8dp 回落到 16dp，配合搜索移出常驻位，
  标题可用宽度从约 191dp 提到约 296dp（实测基线：1080×2400 @420dpi，
  标题文字 20sp 约 8.7dp/字符）。
  返回统一交给系统返回（手势 / 三键 / 键盘），链路见 `RepoDetailActivity.onKeyUp`：
  搜索条开着先收条 → 文件页在子目录先上跳一级 → 最后 `finish()`。
  **副作用修正**：旧实现里工具栏箭头的 `android.R.id.home` 分支直接 `finish()`，
  与硬件返回的"先上跳目录"语义不一致，已随箭头一并删除。
- **点溢出菜单的「搜索」滑出全宽搜索条**（`activity_repo_detail_content.xml` 的 `searchBar`，
  独占工具栏下方一行）：输入即过滤**当前 tab** —— 文件页 = 当前目录条目、提交页 = 提交
  历史；提示词随 tab 切换；返回键先收搜索条。入口在溢出菜单后，`showSearchBar()` 里的
  `showSoftInput` 改成下一帧（`post`）执行，避免弹出窗收起当帧抢焦点导致键盘不弹。
- 搜索条右侧两个按钮：「在仓库中搜索…」（弹条件对话框 → `SearchResultsActivity`
  **递归搜索**）和「×」（清空并收起）。对话框预填搜索条已输入的词，两个入口串成一条
  路径：先过滤本目录，不够再全仓库搜。
- 状态页没有过滤语义：搜索入口隐藏，切到状态页时搜索条自动收起。

为什么不把递归搜索也塞进搜索条的实时过滤：递归 + 读文件是重操作，和「当前目录列表」
语义不符，且结果需要展示相对路径 / 行号，列表项形态不同。

## 一、当前目录实时过滤

`FilesListAdapter`：

- 后台 `setDir()` 列举结果先存一份**未过滤快照** `mListedFiles`（主线程落地时存），
  再按当前 `mFilterQuery` 过滤后装进 adapter。
- `setFilter(String)`：**主线程同步**执行 —— 名称 contains 过滤一个数组是 O(n) 纯内存操作，
  不需要后台线程，也就避开了与 `setDir` 的 token 互相作废问题
  （曾考虑后台过滤 + token，但「输入关键词 → 点进子目录 → 再输入」会让在途的
  `setDir` 结果因 token 过期被丢弃，列表停在旧目录）。
- 过滤持久跨目录：`mFilterQuery` 是字段，`setDir` 落地时也会套用它 —— 搜索框展开时
  点进子目录，子目录同样被过滤；SearchView 收起时 `setFilter(null)` 恢复完整列表。
- 大小写：读 `repo_view_state` 的 `pref_key_search_case_sensitive`（与递归搜索对话框的
  勾选同一份设置，行为一致）。

`FilesFragment.setFilter` 转发；`RepoDetailActivity` 的搜索条 `TextWatcher`
调 `applyFilter` 按当前 tab 转发（文件页 / 提交页），切 tab 时把同一过滤词套用到新 tab，
状态页自动收起搜索条并清空过滤。

## 二、递归搜索（SearchResultsActivity）

`ui/screens/repoDetail/SearchResultsActivity`，Manifest 注册（非导出，parent =
RepoDetailActivity），由详情页 `startActivityForResult` 启动，extras：

| extra | 含义 |
|---|---|
| `Repo.TAG` | 仓库（取 `getDir()` 为搜索根） |
| `EXTRA_QUERY` | 关键词 |
| `EXTRA_MODE` | 0 = 文件名（含目录），1 = 文件内容 |
| `EXTRA_CASE_SENSITIVE` | 大小写敏感 |
| `EXTRA_GLOB` | 类型过滤（如 `*.java`），空 = 全部；非法 glob 按无过滤处理 |

执行模型（仿 `FilesListAdapter.LISTING_POOL`）：static daemon 单线程池 + `volatile` 取消标志，
`onDestroy` 置位即取消；全程不经过 jgit，纯文件系统。

- **遍历**：显式栈 DFS，跳过 `.git`。
- **文件名模式**：名称 contains 匹配，目录与文件都收。
- **内容模式**：仅文件；glob 过滤 → 单文件 ≤ 1 MB → `FsUtils.getMimeType` +
  `isTextMimeType` 跳过二进制 → UTF-8 `BufferedReader` 逐行 `contains`，每个命中行一条结果
  （行号 + 行内容预览）。
- **结果上限 500 条**：防爆列表与长时间占用，截断时状态栏提示「只显示前 500 条」。
- 进度：不定进度条 + 状态行（搜索中 / N 条结果 / 已截断），主线程 handler 发布。

结果项布局 `list_item_search_result.xml`：两行 = 相对路径 +（内容模式：`行号: 行预览`；
文件名模式目录项显示「文件夹」标签）。点击行为：

- **文件**：文本 → `ViewFileActivity`（带 `Repo.TAG`，含提交历史页）；非文本 →
  `FsUtils.openFile` 抛给系统（与 `FilesFragment` 的点击分派一致）。
- **目录**：`setResult(RESULT_OK, EXTRA_RESULT_DIR=绝对路径)` 后 finish；
  `RepoDetailActivity.onActivityResult` 收到后切到文件页并
  `FilesFragment.setCurrentDir(该目录)`，实现「搜到文件夹直接跳进去」。

## 偏好与文案

- `pref_key_search_case_sensitive`（`repo_view_state`，translatable=false）：两处共用。
- 新字符串全部同步 `values/strings.xml` 与 `values-zh-rCN/strings.xml`；
  pref 键只在 `values/`。

## 涉及文件

| 文件 | 改动 |
|---|---|
| `res/menu/repo_detail.xml` | 常驻 = 排序/开关抽屉；溢出 = 搜索（第一项）、编辑标签 |
| `RepoDetailActivity.java` | 无返回箭头（系统返回承担）；全宽搜索条（show/hide/转发过滤/返回键收起）、搜索条件对话框、结果回传跳目录 |
| `FilesListAdapter.java` / `FilesFragment.java` | `setFilter` + 未过滤快照 |
| `SearchResultsActivity.java`（新） | 递归搜索与结果展示 |
| `layout/activity_repo_detail_content.xml` | 顶部全宽搜索条 |
| `layout/activity_search_results.xml` / `layout/list_item_search_result.xml`（新） | 结果页与结果项 |
| `AndroidManifest.xml` | 注册 `SearchResultsActivity` |
| `strings.xml` ×2 | 新文案 |

## 已知边界（有意为之）

- 搜索条跨 tab 保留过滤词并套用到新 tab（文件页 ↔ 提交页）；状态页收起搜索条。
  两侧过滤语义不同（名称 contains vs 提交历史），同一个词换个 tab 结果不同是预期行为。
- 内容搜索不建索引，大仓库首次搜索耗时与文件总量成正比；1 MB 上限与 500 条上限是
  防爆闸，不是精确性限制。
- 非 UTF-8 文本按 UTF-8 解码读行，乱码行仍可能命中 ASCII 关键词，不做编码探测。
