# SearchView 改造调查：为什么不动框架内部（2026-10-18）

状态：**改造已整体撤回**，`main` 保持原行为（点工具栏放大镜 → 还要再点一次输入框才能打字）。
本文存档当时的方案、框架源码证据与替代方案。`AGENTS.md` 只保留一句禁令 + 指向本文。

---

## 1. 起点与目标

现象：仓库列表工具栏点放大镜，`SearchView` 展开、输入框可见，但**拿不到焦点**，必须再点一次输入框，软键盘才起来。

期望（用户提出，按提出顺序）：

1. 一次点击直达输入，不要再点第二次；
2. 输入框里那个「小放大镜」是多余的（工具栏上已经有一个），希望去掉，只留输入框；
3. **UI 简单易维护**——这条是最终否决整个改造的依据。

## 2. 现状代码（`main`）

```java
// ui/screens/main/RepoListActivity.java:312
public void configSearchAction(MenuItem searchItem) {
    SearchView searchView = (SearchView) searchItem.getActionView();
    if (searchView == null) return;
    SearchListener searchListener = new SearchListener();
    MenuItemCompat.setOnActionExpandListener(searchItem, searchListener);
    searchView.setIconifiedByDefault(true);      // ← 第二个放大镜的来源
    searchView.setOnQueryTextListener(searchListener);
}
```

```xml
<!-- res/menu/main.xml -->
<item android:id="@+id/action_search"
      android:icon="@drawable/ic_search"
      android:title="@string/action_search"
      app:actionViewClass="android.widget.SearchView"
      app:showAsAction="always|collapseActionView" />
```

即：菜单项挂一个 framework `SearchView` 作为 action view，`collapseActionView` 负责展开/收起。

## 3. 试过的方案与结局

| # | 方案 | 做法 | 结果 | 根因 / 否决理由 |
|---|---|---|---|---|
| A | 展开回调内抢焦点 | `onMenuItemActionExpand()` 里 `actionView.requestFocus()` + `IMM.showSoftInput()` | **无效** | 回调早于真正的展开（§4.1），此时 view 还是 `GONE`、未 attach，`getWindowToken() == null`，两个调用都静默失败 |
| A′ | 展开后延迟一帧 | `post()` / `postDelayed()` 再抢 | **不稳定** | `post` 只保证排在本轮输入事件之后，**不保证 measure/layout 完成**；第几帧能成靠运气 |
| B | `OneShotPreDrawListener` | 新建 `SearchViewFocusHelper`（约 160 行），等 layout 完成、绘制前再抢焦点 | 焦点与键盘能起（这也是当时能继续往下改的前提） | 方向可用，但为一个点击引入 160 行 helper + 反射遍历内部 view，与目标 3 冲突 |
| C | 去掉第二个放大镜 | `setIconifiedByDefault(false)` 抹掉 hint 里的 `ImageSpan`，再把 `search_mag_icon` 的 drawable 置 null | 视觉达成 | 全程改框架内部状态，且顺带改掉 collapse / 关闭语义（§4.3） |
| D | 菜单项换 `actionLayout`，工具栏下方放独立行 | 仍用 `SearchView`，只是换个宿主 | **否决** | 两套搜索逻辑并存，没变简单 |
| E | 页面自建搜索条（**推荐方向，未实施**） | 不碰 `SearchView`，自己放 `EditText` | 未做 | 见 §6 |

最终执行：`git checkout --` 撤回 6 个 Java/XML 文件，删除 `SearchViewFocusHelper.java`，`git diff` 归零；
重建装机后 `action_search` 恢复为 `ActionMenuItemView` + framework `SearchView`，`dumpsys` 显示点一次后 `mInputShown=false`（即原行为）。

## 4. 框架源码证据

引用位置：`$ANDROID_HOME/sources/android-35/`（本机 `D:\dev\android_sdk\sources\android-35`，API 35，minSdk 31 的设备行为一致）。

### 4.1 展开顺序：回调早于展开

`com/android/internal/view/menu/MenuItemImpl.java:771`

```java
public boolean expandActionView() {
    if (!hasCollapsibleActionView()) return false;
    if (mOnActionExpandListener == null
            || mOnActionExpandListener.onMenuItemActionExpand(this)) {
        return mMenu.expandItemActionView(this);   // ← 真正的展开在这之后
    }
    return false;
}
```

**结论**：`onMenuItemActionExpand()` 返回 `true` 之后框架才开始把 action view 挂上菜单容器、改可见性。
回调内 actionView 尚未 attach，任何依赖 `getWindowToken()` 的调用（`requestFocus()` 的 IME 侧、
`InputMethodManager.showSoftInput()`）都会静默丢弃——不抛异常、不打日志，这是方案 A 失败的根因。

同时要注意：`SearchView` **自己也做了这件事**（`SearchView.java:1409`）：

```java
public void onActionViewExpanded() {
    ...
    mSearchSrcTextView.setText("");
    setIconified(false);            // → onSearchClicked() → requestFocus() + setImeVisibility(true)
}
```

所以方案 A/B 其实是在和框架抢同一件事。这也是后来判定「改造收益可疑」的依据之一。

### 4.2 「第二个放大镜」不是 View，是 hint 上的 `ImageSpan`

`SearchView.java:1203`

```java
private CharSequence getDecoratedHint(CharSequence hintText) {
    // If the field is always expanded or we don't have a search hint icon,
    // then don't add the search icon to the hint.
    if (!mIconifiedByDefault || mSearchHintIcon == null) {
        return hintText;
    }
    final int textSize = (int) (mSearchSrcTextView.getTextSize() * 1.25);
    mSearchHintIcon.setBounds(0, 0, textSize, textSize);
    final SpannableStringBuilder ssb = new SpannableStringBuilder("   ");
    ssb.setSpan(new ImageSpan(mSearchHintIcon), 1, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    ssb.append(hintText);
    return ssb;
}
```

**结论**：那个图标是 hint 文本里的一个 span，没有对应 View，**没有 `setVisibility()` 可点**。
旁证：`uiautomator dump` 里该节点 `text` 恰好是 3 个空格（就是 `new SpannableStringBuilder("   ")`），
不是 `ImageView`——当初按 id 找 view 找不到，原因就在这里。

### 4.3 关掉 `iconifiedByDefault` 的连带代价

`SearchView.java:902`

```java
private void updateViewsVisibility(final boolean collapsed) {
    ...
    final int iconVisibility;
    if (mCollapsedIcon.getDrawable() == null || mIconifiedByDefault) {
        iconVisibility = GONE;
    } else {
        iconVisibility = VISIBLE;
    }
    mCollapsedIcon.setVisibility(iconVisibility);
    ...
}
```

由此推出两条必须知道的后果：

1. `mCollapsedIcon`（即 `search_mag_icon`）的可见性是**每次重算**的，且判据里同时看 drawable 是否为空
   与 `mIconifiedByDefault`。所以「先 `setVisibility(GONE)`」会被下一次 `updateViewsVisibility()` 覆盖；
   唯一稳定做法是 `setImageDrawable(null)`，让它自己判 GONE。—— 这就是方案 C 里那步「绕」操作的由来。
2. `SearchView.java:1324` 的 `onCloseClicked()` 用 `if (mIconifiedByDefault)` 决定「空查询点 × 是否收起」。
   把 `iconifiedByDefault` 关成 `false` 之后，空查询状态下 × 变成**死按钮**（既不收起也不清空），
   返回键 / `collapseActionView` 的配合也一起变。这是「去图标」的隐性代价，不写代码看不出来。

### 4.4 内部 id 拿不到，只能字符串反查

`search_button` / `search_mag_icon` / `search_badge` / `search_src_text` / `search_plate` / `search_edit_frame`
都定义在 `com.android.internal.R.id`。**实测** `Resources.getIdentifier(name, "id", "android")` 运行期返回 0；
能用的只有对子树遍历 `getResourceEntryName(view.getId())` 做名字匹配——把「私有实现」换成「私有名字」，
同样是脆的。

### 4.5 框架自己也承认软键盘要跨帧握手

`SearchView.java:2103` `setImeVisibility(true)`：当 `IMM.hasActiveInputConnection(this)` 为假时，
**并不立即 show**，只把 `mHasPendingShowSoftInputRequest` 置为 `true`；真正的 `showSoftInput()`
要等到 `onCreateInputConnection()`（`:2080`）再 `post(mRunShowSoftInputIfNecessary)`。
`SearchAutoComplete.onWindowFocusChanged()`（`:2013`）的注释更直接：

> it is a bit too early to call InputMethodManager#showSoftInput() here. We still need to wait until
> the system calls back onCreateInputConnection() to call InputMethodManager#showSoftInput().

**推断（未插桩验证）**：展开动作里 IMM 的窗口焦点回调早于本控件的焦点变更，`onCreateInputConnection()`
在用户下一次触摸（第二次点击）时才被触发，于是待发的 showSoftInput 到那时才执行——正好对应「要点两次」的观感。
这条只是最自洽的解释，**没有做 `InputMethodManager` 侧插桩确认**，留作后续可验证项。

## 5. 结论：为什么不在 `SearchView` 上继续做

1. **三个目标互相打架**：去图标的唯一手段（关 `iconifiedByDefault`）恰好改掉 collapse 与关闭语义；
   要恢复语义就得再补代码，复杂度正反馈。
2. **生效的手段全是私有状态**：hint 的 `ImageSpan`、`mCollapsedIcon` 的可见性判据、内部 id 名字。
   没有一个是 public API，SDK 升级后失效是**静默**的（图标悄悄回来、焦点不再抢），不会编译报错。
3. **成本不对称**：为一次点击体验引入约 160 行 helper + 反射 + 私有状态改写，与「UI 简单易维护」直接冲突。
4. **与框架重复劳动**：`onActionViewExpanded()` 已在做 requestFocus + 延迟 showSoftInput（§4.1、§4.5），
   我们的代码只是抢先/重复执行同一件事，出问题时还要判断是谁覆盖了谁。

## 6. 替代方案：页面自建搜索条（推荐）

现成先例：**仓库详情页**就是这么做的（commit `86fe19b`，`activity_repo_detail_content.xml` 的 `searchBar`：
工具栏下方一整行，单一 `EditText` + 显隐控制 + 右侧「在仓库中搜索…」+ × 收起），焦点 / 软键盘 / 提示词
全自控，一行框架代码都没碰。仓库列表要改就照这个模式搬。

实现要点：

1. **布局**：`activity_main.xml` 在工具栏下方加一行 `searchBar`（水平 `LinearLayout`：`EditText` + 收起按钮），默认 `GONE`。
2. **菜单**：`main.xml` 的 `action_search` **去掉** `app:actionViewClass` 与 `collapseActionView`，降级为普通 item，
   点击只做「切换 `searchBar` 可见性」。
3. **焦点**：`searchBar` 可见后 `input.post(() -> { input.requestFocus(); imm.showSoftInput(input, 0); })`。
   自建 `EditText` 在点击时已 attach，不存在 §4.1 的时序问题；要更稳仍可用 `OneShotPreDrawListener`。
4. **过滤**：直接复用 `mRepoListAdapter.searchRepo(s)` / `queryAllRepo()`，与现有 `onQueryTextChange` 等价。
5. **返回键**：`getOnBackPressedDispatcher().addCallback(...)`，搜索条可见时先收搜索条（§7.1）。
6. **文案**：新增 hint 与「无结果」文案，`values/` 与 `values-zh-rCN/` 同步（AGENTS.md 硬约定）。
7. **边界**：标签筛选弹窗/抽屉打开时收起；状态页（clone 视图）打开时收起；`savedInstanceState` 决定旋转后是否保留。

代价与收益：多一个常驻布局节点、多一个「搜索条可见性」状态；换来的是全部自有代码、行为可预测、
不随 SDK 升级漂移。

## 7. 顺带沉淀的两条通用坑

### 7.1 Android 13+ 重写 `onBackPressed()` 无效

返回键走 `OnBackInvokedDispatcher`，`ComponentActivity` 的兜底直接调 `super.onBackPressed()`，
不经过子类重写。实测：搜索行开着按返回，应用直接退到桌面。
正确写法 `getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) { ... })`，
需要让位时 `setEnabled(false)` → `onBackPressed()` → `setEnabled(true)`。

### 7.2 模拟器 Gboard 拼音会劫持 `input text`

ASCII 进的是组合串，不落进 `EditText`（`uiautomator` 读到的 text 仍是 hint），按 ENTER 也不提交。
要注入 ASCII 须 `pm disable-user --user 0 com.google.android.inputmethod.latin`，测完恢复三件套：
`pm enable` + `ime enable voiceIME` + `settings put secure default_input_method .../LatinIME`。

## 8. 将来若还要做，先答三个问题

1. 愿不愿意**放弃** framework `SearchView`（改为自建 `EditText`）？不愿意 → 到此为止。
2. 接受「搜索入口从控件展开变成切换一行」这个交互变化吗？
3. 这条改造的收益，值不值得引入 §6 的第二个状态（搜索条可见性）？

## 附：本文引用的源码位置

`$ANDROID_HOME/sources/android-35/`

| 位置 | 行号 | 用途 |
|---|---|---|
| `com/android/internal/view/menu/MenuItemImpl.java` | 771 | `expandActionView()`：回调早于展开 |
| `android/widget/SearchView.java` | 902 | `updateViewsVisibility()`：`mCollapsedIcon` 可见性判据 |
| 同上 | 1203 | `getDecoratedHint()`：第二个放大镜是 `ImageSpan` |
| 同上 | 1324 | `onCloseClicked()`：`if (mIconifiedByDefault)` 决定空查询时 × 的行为 |
| 同上 | 1344 | `onSearchClicked()`：`requestFocus()` + `setImeVisibility(true)` |
| 同上 | 1409 | `onActionViewExpanded()`：框架自己也在展开时抢焦点 |
| 同上 | 2013 / 2080 / 2103 | `SearchAutoComplete` 的窗口焦点、`onCreateInputConnection`、`setImeVisibility` |
