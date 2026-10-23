# Material 3 主题化方案（路线甲：保留 View 栈）（2026-10-23）

状态：**方案，未实施**。本文只描述改造路径与风险，不含代码。
路线甲 = 继续用 XML + 经典 View 栈，把 AppCompat/Holo 风格的主题换成 Material 3（M3）视觉；
不做 Compose 重写（路线乙）。

---

## 1. 目标与非目标

目标：

1. 视觉接近 local-dream 那类现代观感：M3 色板、圆角、留白、卡片化列表、动态取色。
2. 不重写页面结构，不做 Compose 迁移；交互语义（搜索条、标签筛选、长按浮层工具条、详情页抽屉、sync）全部保持不变。
3. 改造成本可控，可分阶段合入，任一阶段都能单独回退。

非目标：

- 不改导航结构与页面层级。
- 不动 WebView 内的渲染管线（CodeMirror / 语法高亮 / Markdown 外部应用分工）。
- 不引入 Jetpack Compose。

## 2. 现状盘点（实测）

| 项 | 事实 |
|---|---|
| Material 依赖 | `com.google.android.material:material:1.12.0` **已在依赖里**（`app/build.gradle:150`） |
| 主题链 | `AppTheme` / `DarkAppTheme` → `Theme.Sgit` / `Theme.SgitDark` → `Theme.AppCompat.Light.DarkActionBar` / `Theme.AppCompat`（`themes.xml:14-68`） |
| 上世纪遗留 | Holo 风格的 `ActionBar.Solid.Sgit`、`PopupMenu.Sgit`、`ProgressBar.Sgit`、`DropDownNav.Sgit` 等一整套自定义 drawable（`themes.xml:70-112`） |
| 主题选择 | `Profile.getThemeResource()` 返回 `R.style.AppTheme` / `R.style.DarkAppTheme`，`BaseCompatActivity.onCreate()` 里 `setTheme()` |
| 夜间资源 | **没有** `values-night/`；深色主题靠第二套主题 + 主题内属性手工维护 |
| minSdk | 31（= Android 12）→ **动态取色（Material You）在所有目标设备上可用**，无需版本分支 |
| 规模 | 13 个 Activity、48 个 layout；`AlertDialog.Builder` 33 处 / 28 个文件；`ListView` 出现在 12 个布局；`getSupportActionBar()` 5 个文件 |
| 已用 Material 控件 | 仅 `TextInputLayout` 4 处、`appbar` 1 处 |
| 硬编码色值 | 布局与 drawable 里仍有 `#5c6bc0`、`#37474f`、`#FFFFFF`、`#F44336` 等一批字面色 |

结论：**Material 库已就位，缺的是主题、色板与控件层**。这决定了本路线几乎不增体积。

## 3. 体积影响

- 路线甲：只新增 theme/style/color 资源和少量布局改动，Material 1.12.0 本来就在包内 → **增量 < 100 KB**（估计值）。
- 对照：路线乙（Compose）需要 `compose-ui` + `material3` + runtime，dex 与资源通常是 **数 MB 级**增量（估计值，未实测）。
- 所以"改现代风格"和"APK 变大"在本项目里没有必然关系；变大的是换框架，不是换主题。

## 4. 分阶段方案

### P0 主题骨架（先只换骨架，不改配色）

1. `themes.xml` 新增 M3 主题：
   - `Theme.M2Git.M3`（parent `Theme.Material3.Light.DarkActionBar`）
   - `Theme.M2Git.M3.Dark`（parent `Theme.Material3.Dark`）
2. 把 `AppTheme` / `DarkAppTheme` 的 parent 指到上面两个新主题，**保留这两个类名**：
   `Profile.getThemeResource()`、`BaseCompatActivity.setTheme()` 以及各 Activity 都不用改。
3. 先保留现有 ActionBar 形态（本项目的 5 个页面用 `getSupportActionBar()` 操作标题/菜单），
   等 P3 再决定是否换成 `MaterialToolbar`。
4. 必须保留 `android:windowDisablePreview=true`（`themes.xml:53`），否则暗色启动会白闪。
5. 旧 `Theme.Sgit*` 与 Holo drawable 暂时留着，作为回退；确认无引用后再删。

验收：13 个页面逐页冷启动，无崩溃、无白闪；深浅两套主题都能进入。

### P1 色板与动态取色

1. 新建 `colors_m3.xml`，按 M3 role 命名：`md_primary` / `md_on_primary` / `md_primary_container` /
   `md_surface` / `md_surface_variant` / `md_outline` …，浅色与深色各一套（`values/` 与 `values-night/`）。
2. 把旧色名（`@color/branch_name_bg` 等）**继续保留为别名**指向新 role，
   这样无需一次改完所有布局，视觉先整体偏移。
3. `DynamicColors.applyToActivitiesIfAvailable(application)`（material 1.12 自带）：
   Android 12+ 跟随壁纸取色。建议做成设置开关，默认开。
4. 混用注意：本项目"深色主题"是 `setTheme(手工两套主题)`，而动态取色走的是 M3 主题 +
   night 模式。两者要统一，建议二选一：
   - 甲：保留手工 Light/Dark，只让动态取色影响各自的色板；
   - 乙：改为 `AppCompatDelegate.setDefaultNightMode()` 跟随系统/强制深浅，删掉第二套主题。
   **乙更现代但要动设置项与全部页面验收，建议 P1 先走甲。**
5. 清理布局里的 `#rrggbb` 字面色值 → 引用 role。

验收：浅/深两套主题下，每一页的正文、次要文字、分隔线、图标都达到可读对比度；动态取色开/关各截一套图。

### P2 控件替换（按页面分批，不追求一次到位）

优先级从"改动面最大、收益最高"往下排：

1. **对话框**：`AlertDialog.Builder` → `MaterialAlertDialogBuilder`（33 处 / 28 文件）。
   收益大、风险低。大部分页面走 `BaseCompatActivity.showMessageDialog(...)`，改公共入口即可覆盖多数场景；
   sync 结果框、push 结果框、鉴权框都在这一层。
2. **仓库列表行**：普通行布局 → `MaterialCardView`（圆角、`elevation=0`、`surfaceVariant` 描边）。
   **保持 ListView 不动**：`AbsListView` 的 press 记账依赖"行内无可聚焦后代"，
   卡片里不能再塞 `ImageButton` 之类可聚焦控件（历史坑：行内取消按钮导致整行点不动）。
3. **按钮与输入框**：`Button` → `MaterialButton`；`EditText` → `TextInputLayout`（已有 4 处可照抄）。
   涉及 Clone 对话框、提交信息、重命名、标签编辑等。
4. **长按浮层工具条**：`repo_toolbar_popup.xml` 的深色圆角背景 → `MaterialCardView` 容器；
   **定位代码不动**（手指位置、状态栏偏移修正、边缘翻转都要原样保留）。
5. **详情页抽屉**：**保留 `ListView` + `RepoOperationsAdapter`**。
   操作项与下标严格对应（`RepoOperationDelegate` 用 position 映射 action），
   换成 `NavigationView` 会打破这条契约，只换 item 样式。
6. **标签**：如果现有标签是 TextView + 背景 drawable，换 `Chip`；注意点击/长按语义不变。
7. **Tab**：`PagerTitleStrip` 是 framework 控件，M3 下样式割裂。可选升级到 `TabLayout`，
   但要改 `ViewPager` 关联代码，风险高于收益，放在最后。

### P3 系统栏与细节

1. Edge-to-edge：`WindowCompat.setDecorFitsSystemWindows(false)` + insets 处理（targetSdk 31 不强制，但观感更现代）。
2. 状态栏/导航栏颜色跟随 `surface`，深色下用浅色图标。
3. 图标 tint：现有 `ic_*_w` / `ic_*_d` 两套白/黑图标 → 统一用 `?attr/colorOnSurface` tint，之后可删一份资源。
4. 列表分隔线改为留白/描边（M3 不流行 divider）。
5. 视情况把 5 个页面的 `getSupportActionBar()` 换成 `MaterialToolbar`。
6. 设置页：Preference 样式同步换 M3 配色（`preference` 主题）。

## 5. 风险清单

1. **R 字段非 final**（AGP 9）：`case R.xxx` / `switch(getId())` 编译不过，替换控件时别顺手写。
2. `android:windowDisablePreview=true` 丢不得（暗色启动白闪）。
3. `PagerTitleStrip` / `ActionBarTabStyle` 是 framework 控件，M3 主题下样式不会自动跟随。
4. 旧 Holo drawable（`ab_background_textured_sgit` 等）删除前要全仓确认无引用。
5. 抽屉 ListView 的下标映射不能被结构性改动破坏。
6. 列表行不能出现可聚焦后代（`AbsListView` press 记账）。
7. 长按浮层定位依赖 `anchorView` + `getLocationOnScreen/getLocationInWindow` 差值， 换容器后要复测：顶部行、底部行、屏幕左右边缘三种情况。
8. 主题切换必须重建 Activity（现有 `setTheme` 在 `onCreate`，符合）。
9. 文件查看器的 WebView 背景/文字色与 M3 主题需要联动，否则深色下白底刺眼。
10. **编译通过 ≠ 视觉通过**：这类改造必须逐页截图（浅/深 × 中/英）。

## 6. 验收清单

- [ ] 13 个 Activity 冷启动无崩溃、无白闪
- [ ] 浅色/深色（若 P1 选乙，再加"跟随系统"）
- [ ] 动态取色开/关
- [ ] 仓库列表：短按进详情、长按出浮层、浮层定位与边缘翻转
- [ ] sync：列表页与详情页的全屏进度 + 结果对话框
- [ ] 搜索条展开/收起、标签筛选条、排序弹窗
- [ ] 文件查看器与 diff 页面在深浅主题下的可读性
- [ ] 中/英文文案不截断（标题宽度基线见 AGENTS.md §仓库详情页工具栏）
- [ ] APK 体积前后对比（arm64 debug 与 release 签名包）

## 7. 工作量估算（经验值，未计时）

| 阶段 | 估算 |
|---|---|
| P0 主题骨架 | 0.5–1 天 |
| P1 色板 + 动态取色 | 0.5–1 天 |
| P2 控件替换（按页面分批） | 2–3 天 |
| P3 细节 | 0.5–1 天 |

建议 **P0 + P1 先合入**，观感变化最明显且风险最低；P2 按页面逐个推进，每个页面单独验收。

## 8. 与 local-dream 的关系

local-dream 是 Compose + Material3 实现。本项目只借鉴其**视觉规则**（大标题、留白、卡片圆角、
动态取色、弱化分隔线），不借鉴其实现方式。两者观感可以接近，但本项目不需要引入 Compose。

## 9. 动手前需要确认的三件事

1. 深色主题是保留"手动 Light/Dark 两套"，还是改成"跟随系统 + 手动覆盖"（影响 P1 方案选择）。
2. 动态取色默认开还是默认关（影响默认观感与截图基线）。
3. 是否允许删除 Holo 时代的 ActionBar/PopupMenu/ProgressBar 自定义 drawable（影响可回退程度）。
