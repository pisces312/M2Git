# 文件页「横向滚动 vs 滑动切页」冲突：方案与实测结论

状态：已实现并实测通过（2026-10-18）
日期：2026-10-18
关联：`docs/file-viewer-display-settings-design.md`（本冲突是该功能「关闭自动换行」引入的副作用）
范围：`ViewFileActivity` / `ViewFileFragment`（`activity_view_file.xml` 的 ViewPager + `editor.html` 的 CodeMirror）

---

## 1. 现象

`显示选项 → 关闭自动换行` 后打开含长行的文件（实测 `Cargo.lock`）：

- 长行右侧被截断 = 内容确实宽于视口
- 手指向左拖想看右边内容 → **页面直接切到「Commits」标签，内容一格都没滚**

结论：横向手势被 **ViewPager** 抢走，CodeMirror 的横向滚动完全没机会响应。不是渲染问题，是父容器手势拦截。

---

## 2. 先回答一个关键问题：加横向/纵向滚动条有用吗？

**没有。** 实测（emulator-5554，内容确实可向右滚时，在 `ACTION_DOWN`/`ACTION_UP` 读 WebView 的对应 API）：

```
DOWN canH+1=false canH-1=false canV+1=false canV-1=false scrollX=0 scrollY=0   ← JS 报告 canRight=true
UP   canH+1=false canH-1=false canV+1=false canV-1=false scrollX=0 scrollY=0   ← 已横滚 400px
```

原因：横向溢出滚动发生在 Chromium 内部的 DOM 层（`.CodeMirror-scroll` 的 `overflow: scroll`），
滚的不是 WebView 这个 View —— `getScrollX()` 恒为 0，`computeHorizontalScrollRange()` 恒等于 Extent，
所以 `canScrollHorizontally()` 永远 false。**外面包 ScrollView 或画出滚动条，都改变不了这个返回值**，
滚动条只是装饰，ViewPager 一个字节都读不到。

**但用户的直觉方向是对的**：ViewPager 判定「手势归不归孩子」的唯一入口就是
`View.canScrollHorizontally()`（`ViewPager.canScroll()` → `ViewCompat.canScrollHorizontally(v, -dx)`）。
既然 WebView 给不出真话，就让它给得出 —— 继承 WebView 覆盖这个方法，值是 JS 早已上报的边界。

---

## 3. 实现（`EdgeAwareWebView`）

```
editor.js  scroll / applyOptions / display
   │  getScrollInfo() → canLeft / canRight（只在变化时跨桥）
   ▼
CodeLoader.onScrollEdges(canLeft, canRight)          ← @JavascriptInterface，JavaBridge 线程
   ▼
EdgeAwareWebView.mCanScrollLeft/Right（volatile）     ← 主线程读
   ▼
canScrollHorizontally(direction)  →  ViewPager.canScroll() 原生判定
   ├─ true  ：ViewPager 不拦手势，CodeMirror 滚内容
   └─ false ：ViewPager 接管，正常翻页（走它自己的拖拽/回弹动画）
```

- 布局里 `<WebView>` 换成 `ts.realms.m2git.ui.components.views.EdgeAwareWebView`（`fragment_view_file.xml`）。
- 不再有任何 `OnTouchListener` / `requestDisallowInterceptTouchEvent`：手势归属完全交给 ViewPager 原生逻辑。
- JS 侧只有 `reportScrollEdges`（约 12 行，含去重），无资产变更。

---

## 4. 语义差别：为什么是「再划一次」

ViewPager 对一次手势只会决策一次有效归属：某个 MOVE 里它发现孩子能横滚，就设 `mIsUnableToDrag = true`，
**本手势余下所有 MOVE 它都不再介入**。因此：

| 场景 | 行为 |
|---|---|
| 内容还能往该方向滚 | 整段手势都给内容，内容滚动 |
| 手势开始时已到边界 | ViewPager 从第一次判定就接管 → 翻页 |
| 内容滚到头但手指没抬 | 本次手势不再翻页（内容已到边界，画面不再变化） |
| 抬手，在边界上再划一次 | 翻页 |

这正是需求里说的「滚到最右端，再划时触发切换」。想做到「同一手势内滚到头立刻接力翻页」，
必须自己抢手势（旧方案），代价是父容器在半个手势之后收不回控制权，只能自己去操纵 ViewPager，
实测更容易把 WebView 拖成半屏空白 —— 已放弃。

实测日志（在边界处第一次 MOVE 就被交给 ViewPager）：

```
V EDGEDIAG: touch action=0 x=900        ← DOWN
V EDGEDIAG: canH(24)=false              ← ViewPager 判定：不能滚
V EDGEDIAG: touch action=3 x=823        ← WebView 收到 CANCEL，ViewPager 接管
```

---

## 5. 实测矩阵（emulator-5554，debug 包）

| 场景 | 结果 |
|---|---|
| 关换行 + 长行，第一次左滑 | 内容横向滚动，页签仍 File ✅ |
| 继续左滑至右边界 | 内容滚到 `left=896 / max=897`，页签仍 File ✅ |
| 在右边界再左滑 | 切到 Commits ✅（连做 3 轮往返，6/6 稳定） |
| 从 Commits 右滑回 File | 回到 File，内容仍在右边界且**正常显示**，无空白 ✅ |
| 开换行（内容无溢出） | 左滑直接翻页，手感与改动前一致 ✅ |
| 竖向滚动 | 正常，不触发切页 ✅ |
| 每次手势询问次数 | 每手势 1~2 次 `canScrollHorizontally` 调用，无性能影响 ✅ |
| Chromium 是否抢占手势 | `requestDisallowInterceptTouchEvent` 全程 0 次调用 ✅ |

---

## 6. 附带查清：那个「一片空白」不是渲染 bug

排查时出现过「横滚到最右端整屏只剩行号、正文全白」，一度怀疑 WebView 合成层不重绘。
用 DevTools 协议直连渲染进程（`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`）量了空白时刻的 DOM：

```json
{ "scrollLeft": 896, "scrollWidth": 1359, "clientWidth": 462,
  "renderedRows": 51, "maxSpanWidth": 691, "sizerW": 1313,
  "visibleRowsWithAnyTextInViewport": 0 }
```

DOM 完好（51 行、文字、颜色、transform 都正常），渲染器自己截的图（`Page.captureScreenshot`）同样是空白。
真相是：**横向范围 1313px 来自文件别处的 140 字符长行，而当前纵向视口里最宽的行只有 691px**，
滚到 896 时视口内没有任何一行的文字落在可视区 —— 就是「滚进了空白列」，和桌面编辑器关掉自动换行后
一直往右拖看到空白是同一回事，不是缺陷。`refresh()` 能「救回来」是因为它把滚动位置/量算一并刷新了。

---

## 7. 改动文件

| # | 文件 | 改动 |
|---|---|---|
| 1 | `ui/components/views/EdgeAwareWebView.java` | **新增**：覆盖 `canScrollHorizontally()`，两个 volatile 边界字段 |
| 2 | `res/layout/fragment_view_file.xml` | `<WebView>` → `<EdgeAwareWebView>` |
| 3 | `assets/js/editor.js` | `reportScrollEdges`：scroll / applyOptions / display / setLang 时上报边界（去重） |
| 4 | `ui/components/fragments/ViewFileFragment.java` | 字段类型换成 `EdgeAwareWebView`；`onScrollEdges` 桥改为写 WebView |

**不动**：`activity_view_file.xml`、CodeMirror 资产、`js-assets.sha256`（零资产变更）。
`ViewFileActivity.switchPage()` 已删除（旧方案用它自己翻页，现在不需要）。

---

## 8. 未做 / 可选

1. **可见的横向滚动条**：目前横向范围有、但看不见条（Android WebView 的溢出滚动条是触摸时闪现的浮层）。
   要常显得在 `codemirror.css` 里给 `::-webkit-scrollbar` 设宽度和颜色，属纯视觉改动，与手势无关。
2. **同一手势内接力翻页**：放弃（见第 4 节）。
3. **diff 页**：`CommitDiffActivity` 是独立 Activity，不在 ViewPager 里，不存在此问题。
