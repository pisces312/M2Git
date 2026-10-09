# 标签交互增强：点条目标签即过滤 + 工具栏标签筛选弹窗

状态：**方案已定，实施中**（分支 `feature/repo-tags`）。

补齐 `docs/repo-tags-design.md` 里标签体系的两个交互缺口：条目里的标签只是静态展示（不能点），以及筛选只能进溢出菜单（要两步、还要点确定）。

决策记录（2026-10-09，用户定）：

1. **点条目里的标签 = toggle**：未选中→加入当前筛选，已选中→移出。与顶部筛选条里点已选 chip 的行为一致，多标签可叠加。（否决了「只按这一个标签筛选」——那样无法通过点标签组合多选。）
2. **弹窗勾选即时生效**，没有确定/取消按钮，点弹窗外面即关闭。
3. **删除溢出菜单里的「筛选」入口**（`action_filter_tags`）——既是「移」到工具栏，也避免两个入口一个即时生效、一个要点确定造成困惑。

## 1. 现状（改动前的落点）

| 角色 | 位置 |
|---|---|
| 条目里的标签 chip | `TagChipRenderer.fillTagRow(...)` 运行时 `new TextView`；onClick 恒传 `null` |
| 筛选逻辑 | `RepoListAdapter.toggleFilter(int)` / `setFilterSelection(Collection<Integer>)`，含持久化 + `requery()` |
| 顶部筛选条 | `RepoListActivity.renderFilterBar()`，由 `setOnListRefreshed` 在每次 requery 后同步 |
| 工具栏 | `res/menu/main.xml`：只有 `action_search`（`always\|collapseActionView`）+ 溢出三点；用的是 AppCompat 默认 ActionBar（布局里没有 Toolbar） |
| 标签多选面板 | `TagPickerDialog`（居中 AlertDialog，CheckBox 动态生成） |

## 2. 功能 A：点条目标签即过滤

### 改动

1. `TagChipRenderer.fillTagRow` 增加标签点击回调参数；条目 chip **只设 `clickable`、不设 `focusable`**。
2. `RepoListAdapter` 新增 `toggleFilterByName(String)`：用 `mTagRegistry` 把标签名反查成 id，再走现有 `toggleFilter(tagId)`。
3. `RepoListAdapter.newView` 给 `tagRow` 设 `FOCUS_BLOCK_DESCENDANTS` 兜底。
4. `RepoListAdapter.bindView` 把回调接上。

### 为什么 chip 现在传 null（必须绕开的坑）

`createChip` 在 `onClick != null` 时会一并 `setFocusable(true)`。只要行里存在可聚焦后代，`AbsListView.onTouchEvent` 的 ACTION_DOWN 就因为 `child.hasFocusable()` 为真而**跳过整行的 press 记账**，`onItemClick` 与 `onItemLongClick` 一起失效（真机 + 模拟器实测；`.zcode/skills/mgit-emulator/SKILL.md` §4.4 同）。

`hasFocusable()` 查的是 **focusable 标志**，不是 clickable。所以让条目 chip 变成 clickable 但不 focusable，整行点击不受影响——这次正是靠这一点绕开旧坑，而不是靠改 `descendantFocusability`。`FOCUS_BLOCK_DESCENDANTS` 只是防将来复发的保险，注释里写明原因。

### 预期内的行为变化

- 点 chip = 过滤，**不再触发整行点击**（打开仓库详情）。
- chip 上长按**不再**弹出行菜单；行内其他区域的短按/长按照旧。
- chip 落在 `HorizontalScrollView` 里，横向滑动仍是滚动，不会误触发过滤。

## 3. 功能 B：工具栏标签图标 + 原地弹窗

### 改动

| 文件 | 内容 |
|---|---|
| `res/menu/main.xml` | `action_search` 之后插入 `action_tag_filter`（`showAsAction="always"` + `app:actionLayout`）；删除 `action_filter_tags` |
| `res/layout/action_view_tag_filter.xml` | 新增：ImageView，白色 vector 标签图标 + `contentDescription` |
| `res/drawable/ic_action_filter_tags.xml` | 新增：24dp 白色 vector |
| `TagFilterPopup.java` | 新增：`PopupWindow` + `showAsDropDown(anchor, 0, 0, Gravity.END)` |
| `RepoListActivity.java` | `onCreateOptionsMenu` 里用 `MenuItemCompat.getActionView()` 拿 anchor 挂点击；`onOptionsItemSelected` 删旧分支 |
| `values/strings.xml` + `values-zh-rCN/strings.xml` | `action_tag_filter` |

### 为什么必须 `actionLayout`

列表页用的是 **AppCompat 默认 ActionBar**（`activity_main.xml` 里没有 Toolbar 节点，全项目只有 `fragment_credential_item.xml` 用了 `MaterialToolbar`）。默认 ActionBar 的菜单项没有可 `findViewById` 的 View 句柄，拿不到「点击位置」这个 anchor。给菜单项配 `app:actionLayout` 再 `MenuItemCompat.getActionView(item)` 是唯一可靠途径。副作用：用了 actionLayout 之后 `onOptionsItemSelected` 不会再收到该 id 的点击，点击必须在 action view 上自己处理。

### 弹窗实现方式

- `PopupWindow` + `showAsDropDown(anchor, 0, 0, Gravity.END)`：图标正下方、右边缘对齐，不会溢出屏幕右侧。
- 内容用独立的 `res/layout/popup_tag_filter.xml`（ScrollView + `#tagCheckList`）：**没有**复用 `dialog_tag_picker.xml` —— 那个布局里 ScrollView 是 `0dp` + `weight`（为 AlertDialog 的高度分配服务），放进 PopupWindow 会塌成 0 高。
- CheckBox 列表复用 **`TagPickerDialog.renderOptions`**（改成 package-private，并新增一个 `onChanged` 回调参数：对话框传 no-op，弹窗传「即时落筛选」）。不复制那 30 行逻辑，也不重复它的两条约束（`setChecked` 必须在监听器注册之前；只有互斥挤掉别的选项时才重画，否则滚动位置会跳）。
- 宽 ~280dp（不超过屏幕宽 - 16dp）；高度先按 UNSPECIFIED 量出内容真实高度再钳到屏幕高度的 70%，标签多了内部滚动。
- `setFocusable(true)` + 背景 drawable：点弹窗外部、按返回键都能关闭。
- 选项 = `getTagFilterOptions()`（全部标签 + 末尾「无标签」，带命中数）；`exclusiveUntagged` 按 `isMatchAll()` 传入，与旧对话框同一套互斥规则。

### 图标

实测现有工具栏图标（`ic_search`、`ic_content_new`、`ic_action_settings`）全部是**纯白位图**（RGB 255,255,255）——两个主题下 ActionBar 都是深色（`Theme.Sgit` = `Light.DarkActionBar`，`Theme.SgitDark` = `Theme.AppCompat`），所以一只白色图标两个主题通用。

不复用 `ic_tag_w.png`：它只有 m/h/xhdpi 三档（xxhdpi 设备会放大发虚），且语义是 git ref 的 tag（`RepoDetailActivity` 用它标 commit 类型）。新增 vector 遵循现有 `ic_action_cancel.xml` 的写法。

## 4. 验证清单

编译：`GRADLE_USER_HOME=D:/dev/.gradle JAVA_HOME=D:/dev/AndroidStudio/jbr ./gradlew :app:assembleDebug`（通过）。

模拟器（pixel6，debug 包）实测结果（2026-10-09）：

- [x] 工具栏图标落在放大镜与三点之间：Search `[702,138][828,264]` → 标签图标 `[828,128][975,275]` → More options `[975,138][1080,264]`
- [x] 弹窗锚在图标正下方、右边缘对齐（弹窗 `[240,275][975,559]`，右边缘与图标一致）
- [x] 勾选即时生效：勾「3rd」→ 筛选条出现 `ALL / 3rd × / 2 / 2 / Clear`，列表只剩带该标签的仓库
- [x] 点弹窗外部关闭，结果保留
- [x] 点条目里的标签 chip → 按该标签过滤；再点一次 → 取消（toggle 双向都对）
- [x] 点条目**非 chip** 区域 → 进 `RepoDetailActivity`（整行短按未失效，本次最大的风险点实测通过）
- [x] 长按条目非 chip 区域 → 弹出 Rename / Create shortcut / Edit tags 菜单（整行长按未失效）
- [ ] 标签行横向滑动不误触发过滤（沿用旧的滚动行为，未回归）
- [ ] 「无标签」在 ALL 模式下与具体标签互斥（走的是与旧对话框同一份 `renderOptions` 逻辑，未回归）
- [ ] 明暗两个主题下图标与弹窗背景（图标纯白、弹窗底色取 `?android:attr/colorBackground`，两主题 ActionBar 都是深色）

真机（无线设备）：已装 `ts.realms.m2git.debug`，供人工试。
