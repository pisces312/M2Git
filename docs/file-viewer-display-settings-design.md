# 文件显示设置（全局默认 + 单文件临时覆盖）设计方案

状态：已实现（P0，2026-10-18）
日期：2026-10-18
范围：文件内容查看页（`ViewFileActivity` / `ViewFileFragment` → `assets/editor.html`）
已知副作用：关闭「自动换行」后，横向拖动会被外层 ViewPager 抢走（内容不滚、直接切到「Commits」）。
根因与修复方案见 `docs/file-viewer-swipe-conflict-design.md`（2026-10-18 提出，待评审）。

---

## 1. 结论摘要

1. 文件内容由 **CodeMirror 5.65.21** 渲染，显示选项当前**硬编码**在 `assets/js/editor.js`（`lineNumbers: true` / `lineWrapping: true` / `matchBrackets: true`），
   因此本需求本质是「把硬编码选项变为可配置项」，不需要改渲染管线。
2. 采用 **Java 侧为唯一真源、JS 侧只做应用** 的结构：
   - 全局默认落在既有 prefs 文件 `ts.realms.m2git`（`R.string.preference_file_key`），随「设置备份/恢复」自动带走（`BackupManager` 用 `sp.getAll()` 全量导出）。
   - 单文件临时值只存在 `ViewFileFragment` 实例字段，**不写盘**，退出页面即失效；与既有「选择语言」（`ChooseLanguageDialog`）的临时语义一致。
3. 落地范围（2026-10-18 与用户确认后定稿）：
   - **实现（P0，零资产重建成本）**：行号、自动换行、字号、Tab 宽度、括号匹配开关。
   - **字号默认「跟随默认」**：不注入 font-size，保持升级前视觉；不做显式 16px 基线。
   - **P1 明确不做**：高亮当前行（`addon/selection/active-line`）与代码折叠（`foldcode` + `foldgutter`）——需要改 `scripts/rebuild-js-assets.sh` 并重跑全部资产，收益不抵成本。同理「显示空白字符」（core 选项，CSS 可见性待验证）一并搁置。
   - **不做「设为全局默认」按钮**：单页操作悄悄改全局与「临时」定位冲突。
4. **diff 页（`CommitDiffActivity`）不在本期范围**：它用 hljs 渲染 HTML 表格（`local_commits_diff.js`），没有 CodeMirror，行号语义来自 hunk 头而非编辑器 gutter，需要独立方案（仅「换行」一项有意义）。

---

## 2. 现状盘点（代码证据）

| 关注点 | 现状 | 位置 |
|---|---|---|
| 文件内容渲染 | CodeMirror，`fromTextArea` 创建，只读 | `assets/js/editor.js:6-21` |
| 显示选项 | 硬编码 4 项，无用户可配项 | `assets/js/editor.js:11-18` |
| 宿主 → JS 通道 | `WebViewJs.eval()`（`evaluateJavascript` + `onPageFinished` 队列） | `utils/WebViewJs.java:31-58` |
| JS → 宿主桥 | `CodeLoader`：`getCode` / `copy_all` / `loadCode` / `getTheme` | `ViewFileFragment.java:126-173` |
| 主题（已全局化） | `Profile.getCodeMirrorTheme()` ← pref `color_theme_id` | `Profile.java:76-79` |
| 偏好文件 | `ts.realms.m2git`（Profile 与 SettingsFragment 同名同模式） | `Profile.java:17-22`、`SettingsFragment.java:43-45` |
| 设置页结构 | `preferences.xml`，分 General / Git profile / Security / Backup / About | `res/xml/preferences.xml` |
| 文件页菜单 | 5 项：search / edit_in_other_app / copy_all / choose_language | `res/menu/view_file.xml` |
| 设置备份 | 全量 `prefs.getAll()` 导出 → 新增键自动包含 | `local/backup/BackupManager.java:185-192` |
| CM 资产来源 | 脚本生成（core + 4 个 addon + 40 个 mode），sha256 台账 | `scripts/rebuild-js-assets.sh:74-95`、`scripts/js-assets.sha256` |
| `editor.js` 性质 | **手写文件，不是脚本产物** → 改它不需要重跑资产脚本 | 脚本产物仅 6 个：jquery / hljs / cm-bundle / 3 个 css |

结论：改动集中、边界清晰，唯一需要额外评估的是「需要新 addon 的选项」（触发资产重建）。

---

## 3. 选项矩阵（能力 / 实现成本 / 验证状态）

| 选项 | CodeMirror 机制 | 前置条件 | 本期 |
|---|---|---|---|
| 显示行号 | core `lineNumbers` | 无 | **已实现**，默认开 |
| 自动换行 | core `lineWrapping` | 无 | **已实现**，默认开 |
| Tab 宽度 | core `tabSize` | 无 | **已实现**，默认 4 |
| 字号 | wrapper `style.fontSize` + `refresh()` | 无 | **已实现**，默认跟随（0） |
| 括号匹配 | `matchBrackets`（addon 已在 bundle） | 无 | **已实现**，默认开 |
| 高亮当前行 | `styleActiveLine`（`addon/selection/active-line`） | 需重建资产 | 不做（P1 搁置） |
| 代码折叠 | `foldGutter`（`foldcode`+`foldgutter`+`brace-fold`+css） | 需重建资产 | 不做（P1 搁置） |
| 显示空白字符 | core `showInvisibles` | 无 | 不做（CSS 可见性未验证） |

> 保留这张矩阵是为了下次要加 addon 类选项时不必重新调研「哪个选项要重建资产」。

默认值（沿用现状，避免升级即改变用户视觉）：

```
lineNumbers        = true
lineWrapping       = true
matchBrackets      = true
highlightActiveLine= false
showInvisibles     = false
tabSize            = 4      （CM5 默认值）
fontSizeSp         = 0      （0 = 跟随 WebView 默认，即当前行为）
```

---

## 4. 架构设计

### 4.1 数据流（单一真源）

```
                 ┌─────────────────────────────┐
   设置页 ──────▶│ prefs: ts.realms.m2git       │  全局默认（持久）
   (Switch/List) └──────────┬──────────────────┘
                            │ 读取（仅当本页无覆盖）
                            ▼
                   ┌─────────────────┐      临时覆盖（不落盘）
                   │ ViewFileFragment│◀──── 显示选项弹窗（用户勾选）
                   │  mOverride      │
                   └────────┬────────┘
                            │ CodeLoader.getViewOptions() → JSON
                            │ + WebViewJs.eval("applyViewOptions(...)")
                            ▼
                   editor.html / editor.js（CodeMirror setOption）
```

关键设计决策：

1. **Java 侧持有最终生效值**，JS 不自己算默认值。页面初始化时 JS **主动拉取**（`CodeLoader.getViewOptions()`），
   而不是「Java 在某一时刻 push 一次」——这样 WebView 重载、页面重建、旋转后都能自动一致，不依赖推送时序。
2. **增量应用，不重建编辑器**：用 `editor.setOption()` / 改 wrapper 字号 + `refresh()`，保留滚动位置与选区。
   只有在 `editor` 尚未创建时（页面还在加载）才把选项缓存到局部变量，初始化时合并。
3. **覆盖粒度是整份快照**：用户一旦在弹窗里调整，本页就以「调整后的完整组合」为准，设置页之后的改动不再影响本页。
   语义简单、可预测；需要逐项继承会显著增加状态机复杂度，收益不对等。
4. **重置**：弹窗提供「恢复默认」→ 覆盖置空 → 重新拉全局值并应用。

### 4.2 新增值对象（Java）

`app/src/main/java/ts/realms/m2git/local/preference/FileViewOptions.java`

```java
public final class FileViewOptions {
    public boolean lineNumbers, lineWrapping, matchBrackets,
                   highlightActiveLine, showInvisibles;
    public int tabSize, fontSizeSp;

    /** 全局默认：读 prefs，键缺失时用现状硬编码值兜底（老用户升级后视觉不变）。 */
    public static FileViewOptions readGlobal(Context c) { ... }

    /** 写全局默认（供「设为全局默认」与设置页 Import 复用）。 */
    public void writeGlobal(Context c) { ... }

    public FileViewOptions copy() { ... }

    /** 输出给 JS 的 JSON（org.json，项目已依赖）。 */
    public String toJson() { ... }
}
```

放在 `local/preference/` 而非并入 `Profile`：`Profile` 现职是身份/主题等账号级配置，塞 UI 显示选项会让它变成杂物间。
偏好键统一用点分风格（与 `use.cravatar` / `markdown_open_mode` 一致）。

### 4.3 JS 契约（editor.js）

```js
var viewOptions = { lineNumbers: true, lineWrapping: true, matchBrackets: true,
                    highlightActiveLine: false, showInvisibles: false,
                    tabSize: 4, fontSizeSp: 0 };

function readOptionsFromHost() {
  try { return JSON.parse(CodeLoader.getViewOptions()); } catch (e) { return viewOptions; }
}

function applyOptions(o) {                 // 幂等；editor 未就绪时只更新本地副本
  viewOptions = o;
  if (!editor) return;
  editor.setOption('lineNumbers', o.lineNumbers);
  editor.setOption('lineWrapping', o.lineWrapping);
  editor.setOption('matchBrackets', o.matchBrackets);
  editor.setOption('styleActiveLine', o.highlightActiveLine);   // P1
  editor.setOption('showInvisibles', o.showInvisibles);         // P1
  editor.setOption('tabSize', o.tabSize);
  editor.getWrapperElement().style.fontSize = o.fontSizeSp > 0 ? o.fontSizeSp + 'px' : '';
  editor.refresh();
}

window.applyViewOptions = applyOptions;
// displayFileContent() 内：var o = readOptionsFromHost(); 用 o 构造 editorOption，再 applyOptions(o);
```

注意点：
- `getViewOptions()` 走 `@JavascriptInterface` 同步返回 `String`，与既有 `getTheme()` 完全同构，无新增线程风险；
  实现里**直接返回已缓存的字段**（不做 SharedPreferences IO），避免在 WebView JS 线程做磁盘读。
- `JSON.parse` 必须 try/catch：桥方法缺失（旧包缓存页/异常）时回落到硬编码默认，不能白屏。
- 字号作用于 `getWrapperElement()` 而非 `.CodeMirror-line`，否则行号 gutter 与正文行高不一致、`refresh()` 后会错位。

### 4.4 生命周期与状态恢复

| 场景 | 期望行为 | 实现 |
|---|---|---|
| 首次打开文件 | 用全局默认 | `mOverride == null` → `readGlobal()` |
| 弹窗调整 | 立即生效、不落盘 | `setViewOptions(o)` → `mOverride = o` + eval |
| 旋转 / 进程重建 | 覆盖值保持 | `onSaveInstanceState` 存 override；`null` 时不存 |
| 退出页面再进 | 覆盖丢弃，回到全局 | Fragment 实例销毁，字段自然失效 |
| 切到 Commits tab 再回来 | 覆盖保持 | ViewPager 不重建 Fragment |
| 设置页改了全局值后返回 | 若本页无覆盖 → 跟随新值 | `onResume()` 比较 `readGlobal()` 与当前值，不同则 apply |
| SSH key 模式 | 同样可用（无副作用） | 菜单可见性只按 tab 判断 |

---

## 5. 全局设置（设置页）

`res/xml/preferences.xml` 在 General 之后插入新分组：

```xml
<PreferenceCategory android:title="@string/pref_category_title_file_viewer">
    <SwitchPreferenceCompat android:key="@string/pref_key_viewer_line_numbers"
        android:title="@string/preference_viewer_line_numbers"
        android:defaultValue="true" android:persistent="true" />
    <SwitchPreferenceCompat android:key="@string/pref_key_viewer_line_wrap"
        android:title="@string/preference_viewer_line_wrap"
        android:defaultValue="true" android:persistent="true" />
    <SwitchPreferenceCompat android:key="@string/pref_key_viewer_match_brackets" ... />
    <ListPreference android:key="@string/pref_key_viewer_font_size"
        android:title="@string/preference_viewer_font_size"
        android:entries="@array/viewer_font_sizes"
        android:entryValues="@array/viewer_font_size_values"
        android:defaultValue="0" />
    <ListPreference android:key="@string/pref_key_viewer_tab_size" ... />
    <!-- P1：高亮当前行 / 显示空白字符 -->
</PreferenceCategory>
```

约定（与 AGENTS.md 一致）：
- 新键在 `values/strings.xml` 与 `values-zh-rCN/strings.xml` **两处**都加，`translatable="false"` 的值型键只放 `values/`。
- 字号/Tab 的文案数组两语言都加；`*_values`（`0`/`12`/`14`/`16`/`18`/`20`、`2`/`4`/`8`）**不翻译**，只留 `values/arrays.xml`。
- 设置项改动**不触发 Activity 重建**（不同于主题/语言），无需改 `onSharedPreferenceChanged`；
  已打开的文件页由 4.4 的 `onResume` 兜底。

建议文案：

| key | en | zh-rCN |
|---|---|---|
| `pref_category_title_file_viewer` | File display | 文件显示 |
| `preference_viewer_line_numbers` | Show line numbers | 显示行号 |
| `preference_viewer_line_wrap` | Wrap long lines | 自动换行 |
| `preference_viewer_match_brackets` | Highlight matching brackets | 高亮匹配括号 |
| `preference_viewer_font_size` | Font size | 字号 |
| `preference_viewer_tab_size` | Tab width | Tab 宽度 |

---

## 6. 单文件临时覆盖（文件页）

### 6.1 入口方案对比

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A. 菜单项「显示选项」→ 弹窗 | 与既有 `choose_language` 同位同风格；不占内容区；可容纳字号这类多值项 | 两步操作 | **推荐** |
| B. 菜单内 4 个 checkable item | 一步直达 | 5 个菜单项已接近上限，字号/Tab 无处安放 | 否 |
| C. 页面内浮动「Aa」按钮 | 一步直达、可见性高 | 遮挡代码、与 WebView 点击区冲突、原生浮层需处理 insets | 否 |

菜单：`res/menu/view_file.xml` 增 `action_view_settings`（`showAsAction="never"`，与 `action_choose_language` 一组）；
`ViewFileActivity.onCreateOptionsMenu` 里按 `mCurrentTab == FILE_FRAGMENT_INDEX` 控制可见。

### 6.2 弹窗形态

实现为纯代码构建（与 `SortOptionsDialog` 一致：`AlertDialog.Builder` + 自绘 `LinearLayout` + `ScrollView`），
不用 `BaseDialogFragment`：这个弹窗没有任何需要跨重建恢复的自身状态，值是即时推给 Fragment 的。

```
显示选项
────────────────────────────
  [x] 显示行号
  [x] 自动换行
  [x] 高亮匹配括号
  字号        [ 跟随默认 ▾ ]
  Tab 宽度    [ 4 ▾ ]
────────────────────────────
  [恢复默认]        [关闭]
```

- 勾选/选择**即时回调** `ViewFileActivity` → `ViewFileFragment.setViewOptions(...)`，无「确定」按钮：
  这几项的效果就是看得见/看不见，用户需要当场看到结果才能判断。也因此不设「取消」——
  值已经推进页面了，撤销与关闭是两件事。
- 「恢复默认」= 丢弃本页覆盖、重新应用全局值，点击后弹窗关闭（AlertDialog 按钮默认行为），
  避免控件显示值与实际生效值不一致。
- Spinner 首次 layout 会补发一次 `onItemSelected`，靠「值没变就 return」拦掉，不需要初始化标志位。
- **不含「设为全局默认」**（2026-10-18 决定）。
- P2 可选：「设为全局默认」按钮，把当前组合写入 prefs 并 Toast 提示。先不做——避免单页操作悄悄改变全局，与「临时」定位冲突。

---

## 7. 关键坑与边界

1. **推送时序**：所有 JS 调用必须走 `WebViewJs.eval()`（`loadUrl("javascript:")` 在页面未 ready 时静默丢失，1.8.10 已踩过）。
2. **编辑器尚未创建时收到 apply**：`display()` 由 `CodeLoader.loadCode()` 的读文件线程回调后才触发，
   期间用户若已打开弹窗并改动，`editor` 仍为 `undefined` → JS 侧必须缓存并在初始化时合并（4.3 的 `applyOptions` 已处理）。
3. **关闭换行后的水平滚动**：`editor.html` 的 `.CodeMirror { width: 100%; height: 100% }` + absolute body，
   CodeMirror 自身 `.CodeMirror-scroll` 提供横向滚动；需实机确认长行（>2000 字符）下能横滑而不是被裁掉。
4. **超大文件**：关行号只省 gutter 的 DOM，不改变渲染策略；>1 MB 文件的既有性能表现不变（不在本需求范围内优化）。
5. **字号与行号对齐**：见 4.3 注意点，必须在 wrapper 上设字号并 `refresh()`。
6. **`editor.js` 是手写文件，不是脚本产物**：改动不会触发 `scripts/rebuild-js-assets.sh` 的台账校验；
   但 **P1 的 addon 属于产物**，必须改脚本 + 重跑 + 提交新 `js-assets.sha256`（脚本为纯英文注释，遵守项目约定）。
7. **资产重建风险**：脚本重跑会重新下载 6 个产物，jsDelivr 的 Terser 版本漂移可能让 `codemirror-bundle.min.js` 字节变化
   （脚本已带 `--retry-all-errors`，但仍需人工核对 bundle 头注释与文件大小）。
8. **备份兼容**：新键在 `ts.realms.m2git` 且 `BackupManager` 全量导出，旧版本导入新备份时会忽略未知键（`JSONObject` 写入），无兼容问题；
   反向（新版本导入旧备份）表现为「键缺失 → 用默认值」，同样安全。

---

## 8. 改动文件清单（实际落地）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `local/preference/FileViewOptions.java` | **新增**：值对象（`Serializable`）+ `readGlobal()` + `toJson()` / `copy()` / `sameAs()` |
| 2 | `assets/js/editor.js` | 选项改为从 `CodeLoader.getViewOptions()` 拉取；新增 `window.applyViewOptions`；`mergeOptions` 抵御缺字段 |
| 3 | `ui/components/fragments/ViewFileFragment.java` | `mGlobalOptions` / `mOverride` 字段、`getViewOptions()` 桥方法、`setViewOptions()` / `restoreDefaultViewOptions()`、`onSaveInstanceState` / `onResume` |
| 4 | `ui/components/fragments/ViewFileActivity.java` | 菜单项可见性 + `showViewSettings()` + `Listener` 透传 |
| 5 | `ui/components/dialogs/FileViewSettingsDialog.java` | **新增**：即时生效的选项弹窗（含「恢复默认」） |
| 6 | `res/menu/view_file.xml` | 新增 `action_view_settings`（`showAsAction="never"`） |
| 7 | `res/xml/preferences.xml` | 新增 `pref_category_title_file_viewer` 分组（3 开关 + 2 ListPreference） |
| 8 | `res/values/strings.xml`、`values-zh-rCN/strings.xml` | 10 条文案 + 5 个键常量（仅 `values/`） |
| 9 | `res/values/arrays.xml`、`values-zh-rCN/arrays.xml` | `viewer_font_sizes`（需翻译）/ `viewer_font_size_values` / `viewer_tab_sizes` |

未触碰：`scripts/rebuild-js-assets.sh`、`scripts/js-assets.sha256`、`assets/js/codemirror-bundle.min.js`、
`assets/css/*` —— P0 不需要新 addon，资产零变更。

---

## 9. 验收标准

1. 设置页关闭「显示行号」→ 打开任意文件无行号；返回设置页重开仍无（持久化正确）。
2. 文件页弹窗临时开启行号 → 立即生效；退出页面重进 → 回到全局值。
3. 弹窗调整后旋转屏幕 → 覆盖值保持；杀掉进程重进 → 覆盖值丢弃（符合「临时」定义）。
4. 设置页改字号 → 已打开的文件页在 `onResume` 后跟随（未做临时覆盖的前提下）。
5. 关闭「自动换行」→ 长行可水平滚动，行号列不错位；重新开启 → 恢复。
6. 主题切换（已有功能）、语言选择（临时）、复制全部（`copy_all`）三项回归无异常。
7. 空文件 / 二进制误开 / 10 MB 文本 三种边界不崩。
8. 中文与英文两种语言下弹窗与设置项文案完整，无硬编码英文。

---

## 10. 已结案的开放问题

1. **默认字号基线**：定「跟随默认」（`fontSizeSp = 0` 不注入 font-size），保持升级前视觉，不做显式 16px 基线。
2. **`showInvisibles`**：不做，因此无需验证 CSS 可见性。
3. **diff 页**：不纳入本期。若以后要做，只做「换行」一项（改 `local_commits_diff.css` 的 `white-space`），独立于 CodeMirror 选项。
4. **「设为全局默认」按钮**：不做。
5. **高亮当前行 / 代码折叠**：不做（需重建 CodeMirror 资产）。若日后要做，照第 8 节原 P1 路径：
   改 `scripts/rebuild-js-assets.sh` 的 `CM_ADDONS` + 重跑 + 更新 `js-assets.sha256`，再在 `FileViewOptions` 与 `editor.js` 加同名字段。

## 11. 实现备注（与设计稿不一致之处）

| 设计稿 | 实际实现 | 原因 |
|---|---|---|
| `BaseDialogFragment` | 静态 `FileViewSettingsDialog.show(...)` | 弹窗无自身状态可恢复，值是即时外推的；与较新的 `SortOptionsDialog` 保持一致 |
| 弹窗需要传入全局值「回填控件」 | 不需要；「恢复默认」直接丢弃覆盖并关闭弹窗 | 关闭后重新打开弹窗读到的就是全局值，少一层状态同步 |
| `FileViewOptions.writeGlobal()` | 未实现 | 设置页由 `ListPreference` / `SwitchPreferenceCompat` 自己写盘，「设为全局默认」已取消，没有第二个写入口 |
| 字号/`Tab` 用 `ListPreference`，弹窗用 `Spinner` | 一致；两者共用同一组数组资源 | 避免两处各维护一份候选值 |
