# 仓库详情文件页 / 文件预览页的「相对路径条」（含点击复制）

> 状态：已实现（2026-10-27）。编译 + 打包装机通过；模拟器手动点测按分工留给用户。
> 行号以当时代码为准，后续可能漂移。

## 需求

1. 仓库详情页**文件 tab** 浏览目录时，显示当前浏览的**相对路径**（相对仓库工作区根）。
2. 点击即可复制该路径，并弹 toast 告知复制成功。
3. 覆盖文件预览页（`ViewFileActivity`）：打开文件后同样显示该文件的相对路径，行为一致。

## 决议（2026-10-27）

| 项 | 决议 |
|---|---|
| 文件页位置 | tab 条（`PagerTitleStrip`）之下、文件列表之上，新增一行，只在文件 tab 出现 |
| 预览页位置 | 页面顶部（tab 条之上），文件与「提交历史」两个 tab 共用 |
| 仓库根目录 | **整行 `GONE`** —— 根目录没有相对路径可言，复制 `/` 也无实际用途，顺便省 40dp |
| 显示文本 | 不带前导 `/` 的相对路径，如 `app/src/main/java`；仓库根 → 不显示 |
| 复制内容 | 与显示文本同一段路径（不做「显示 `/app/...` 却复制成 `app/...`」）；**渲染层的省略不影响复制**，剪贴板永远是完整路径 |
| 预览页隐藏条件 | `mRepo == null`（SSH 私钥页、`.git/config` 查看器都不带 Repo）或文件不在仓库内 |
| 长按菜单 | 不动。长按文件项已有的「复制相对/绝对路径/文件名」是**文件级**入口，本条是**目录级**可见性 |

## 位置选型（否决的方案）

| 位置 | 否决理由 |
|---|---|
| ActionBar 副标题 | 副标题不可点，要可点只能自绘标题视图；且工具栏宽度本来就紧（见 AGENTS.md「仓库详情页工具栏」，411dp 上标题可用宽约 296dp） |
| 底部分支条右侧 | 那条是承载分支选择的 `Button`，塞第二个点击区得重构成双按钮，视觉也拥挤 |
| 仿搜索条在 activity 顶部挤一行 | 属于 activity 层，项目摘要/状态页要手动隐藏；搜索条展开时两行叠一起 |
| 塞进 `PagerTitleStrip` 的「Files」标签 | 标签条只能单行、会与 tab 点击语义冲突 |

选中方案的成本是固定吃掉约 40dp 竖向空间；根目录隐藏后，最常停留的顶层目录观感完全不变。

## 实现

### 复用布局 `res/layout/view_path_bar.xml`

一个 `LinearLayout`（根 id `pathBar`，默认 `GONE`）：

- 背景 `?pager_title_strip_background`，与 tab 条连成一块表头（两套主题、动态取色都跟随）；
- 左侧 `?ic_folder_fl` 小图标、中间路径 `TextView`（`currentPathText`，14sp、`?attr/colorOnSurfaceVariant`）、
  右侧 `@drawable/ic_menu_copy`；
- `singleLine`，省略策略见下节；
- `foreground="?android:attr/selectableItemBackground"` 给整行涟漪，`minHeight 40dp`，整行可点；
- 两个图标 `importantForAccessibility="no"`；可点的整行在代码里设 `contentDescription`
  （`复制路径 %1$s`），TalkBack 读到的是**完整路径**而不是截断后的显示文本。

两处 `<include>`：

- `fragment_files.xml`：根 `RelativeLayout` → `LinearLayout(vertical)`，路径条 + `ListView(0dp/weight=1)`。
- `activity_view_file.xml`：`include` 在顶部，`ViewPager` 改 `layout_below="@id/pathBar"`。
  与仓库详情页的 `searchBar` 同一个技巧：`GONE` 的锚点视为零尺寸贴顶，不出现空行。

### 一行放不下时：保头保尾，末级优先

`PathBarTextView`（`ui/components/views/`）不是简单的 `ellipsize=start/end/middle`：
`middle` 按字符数对半截断，可能把最后一级从中间切开；`start` 会让所有深路径看起来一样，
`end` 直接丢掉「我在哪」。规则改成**确定性**的：

1. 放得下 → 原样显示；
2. 放不下 → `头部…/最后一级`，头部用 `TextUtils.ellipsize(..., END)` 填满剩余宽度，
   最后一级始终完整；
3. 最后一级本身都超过整行宽度（极罕见）→ 退化成 `…最后一级的末段`（整体 START 省略）。

实现放在 `onMeasure` 里：路径条的宽度是 `0dp + weight`，只有测量规格里拿得到确切宽度，
算法由宽度决定、与 measure 次数无关，所以按 `getText()` 比对后只在文本变化时 `setText`，
不会反复 `requestLayout`。旋转（`ViewFileActivity` 自己处理 `orientation`，不重建 Activity）
时宽度变化会重新算一次。

### 路径计算

统一走已有的 `FsUtils.getRelativePath(file, base)`（`File.toURI().relativize(...).getPath()`）：

- `URI.getPath()` 返回**已解码**的路径，空格、中文、`#`、`%` 都不需要额外处理
  （已用 JDK 实测：`a b/c`、`x#y.txt` 原样返回）；
- `File.toURI()` 对**存在的目录**会补尾 `/`，目录场景要剥掉；
- 结果为空 = 仓库根 → 隐藏；以 `/` 开头（不在仓库内）或以 `..` 开头（仓库外的兄弟目录）→ 隐藏。

### 刷新点

- 文件页只有 `FilesFragment.setCurrentDir(File)` 一个换目录入口（进入子目录、返回上级、
  搜索页跳目录、新建文件/目录、view 重建恢复全都汇到这里），在它末尾调 `updatePathBar()` 即可；
  view 可能尚未创建（adapter 为 null 时也会被调用），字段判空。
- 预览页在 `ViewFileActivity.onCreate` 里算一次即可，页面内文件不会变。

### 复制

沿用项目里既有的写法（`RepoFileOperationDialog` / `CommitsFragment`）：

```java
ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
cm.setPrimaryClip(ClipData.newPlainText("mgit", path));
showToastMessage(getString(R.string.msg_copied_path, path));
```

新增两条双语文案：`msg_copied_path`（`Copied: %1$s` / `已复制：%1$s`）。
toast 带路径是为了在「路径被 `ellipsize` 截断」时仍能确认完整内容。

> Android 13+ 系统在写入剪贴板时自己会弹一次浮层，本条 toast 会与其重复一次。
> 这是现有「复制」功能一直以来的行为，保持一致，不做特判。

## 边界情况

| 场景 | 行为 |
|---|---|
| 仓库根 | 隐藏，列表贴 tab 条 |
| 进子目录 / 系统返回上一级 | 同一次 `setCurrentDir` 内更新 |
| 切到提交 / 状态 tab 再切回 | fragment 字段保留目录，路径一致 |
| 旋转 / activity 重建 | 目录由 `savedInstanceState` 恢复，路径跟随 |
| 搜索页点目录跳转回来 | 走 `setCurrentDir`，路径跟着变 |
| 搜索条展开 + 名称过滤中 | 路径仍指物理目录，不受过滤影响 |
| 超长路径 | `头部…/最后一级`，末级完整；复制的仍是完整路径，toast 兜底 |
| 外部导入仓库 | 相对其工作区根，与长按菜单「复制相对路径」口径一致 |
| SSH 私钥页 / `.git/config` 查看器 | `mRepo == null`，整行隐藏 |
| 当前目录被别处删除 | 下次导航即刷新，不做额外容错 |

## 验证

- 已做：`./scripts/dev.sh check`（资源合并 + data binding + Java/Kotlin 编译）与 `apk` 均通过，
  `adb install -r` 已装到 pixel6 模拟器（debug 包，权限已重授）。
- 按项目分工，模拟器上的点击回归由用户执行：按边界表逐项走；剪贴板内容按
  `.zcode/skills/mgit-emulator/SKILL.md` 与 `android-adb-ui-verification` 里的免 root 读法核验，
  确认粘出的是完整相对路径（而不是被 `ellipsize` 截断的显示文本）。

## 未做

- 面包屑：路径条不做「点某一段跳到该层」，只做复制；导航仍走列表项 / 系统返回。
- 不做 ActionBar 副标题版，也不再提供开关（常驻且仅子目录可见，空间成本可控）。
