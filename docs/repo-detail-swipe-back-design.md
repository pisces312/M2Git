# 仓库详情页「页面内右滑返回仓库列表」：调研与方案（暂不实施）

状态：**已调研，暂不实施**（2026-10-25 决议）
日期：2026-10-25
范围：`RepoDetailActivity`（文件 tab）、`RepoListActivity` 的转场与窗口主题

---

## 0. 为什么暂不做

需求：仓库详情页的**文件 tab** 内往右划 → 回到仓库列表页（不是 ViewPager 切 tab，是回上一级）。

**暂不实施的理由**：Android 系统的**屏幕边缘返回手势**（从屏幕左/右边缘往内滑）已经覆盖了同一类需求 ——
"从详情页退回列表页"这件事用户已经在系统手势里有了肌肉记忆。再在页面内部加一套自定义右滑，
收益边际递减，反而多出一条手势路径要维护、多一套窗口/转场要测。

**什么情况下值得重启**：系统手势用腻了、想要"跟手"（手指按住就能看到页面在动）的观感、
或者明确要求"文件页内任意位置右滑"而非系统边缘触发。届时本文的调研直接可用。

---

## 1. 现状（代码事实）

| 项 | 事实 | 位置 |
|---|---|---|
| 详情页结构 | `RepoDetailActivity` + 一个 `ViewPager` 三 tab（文件 / 提交 / 状态） | `RepoDetailActivity.java:152-161` |
| 文件页 | `FilesFragment`，内容是 `ListView` | `ui/components/fragments/FilesFragment.java`、`res/layout/fragment_files.xml` |
| tab 适配器 | `TabItemPagerAdapter extends FragmentPagerAdapter` | `RepoDetailActivity.java:579-648` |
| 返回路径 | `onKeyUp(KEYCODE_BACK)`：搜索条开着→收起 → 文件页在子目录→上跳一级 → 否则 `finish()` | `RepoDetailActivity.java:348-384` |
| finish 转场 | `BaseCompatActivity.finish()` → `backTransition()` → `overridePendingTransition(slide_in_right, slide_out_right)`（缩放淡出，不是滑动） | `BaseCompatActivity.java:352-369` |
| 列表页 → 详情页 | `RepoListAdapter.onItemClick` → `startActivity(RepoDetailActivity)` + `putExtra(Repo.TAG, repo)` | `RepoListAdapter.java:611-613` |
| 桌面快捷方式 | `RepoListAdapter.createShortcut` → `new Intent(RepoDetailActivity.class)` + `putExtra("repo_id", ...)` + `ACTION_VIEW` | `RepoListAdapter.java:787-809` |
| 快捷方式入口识别 | `mRepo == null && getIntent().hasExtra("repo_id")` 分支 | `RepoDetailActivity.java:109-114` |
| SDK | `minSdk 31` / `targetSdk 31` | `app/build.gradle:40-41` |
| 详情页主题 | 无独立主题，走应用 `AppTheme` | `AndroidManifest.xml:80-87` |

**手势归属无竞争**：文件列表项是纯 `RelativeLayout + TextView`（`res/layout/list_item_files.xml`），
没有横向滚动容器；文件预览 `ViewFileActivity` 是独立 Activity 且有自己的 ViewPager。
所以详情页内的横向手势当前**无人认领**。

---

## 2. 关键技术分析

### 2.1 拦截点：为什么必须在 `dispatchTouchEvent` 且"先喂检测器"

若在 `RepoDetailActivity.dispatchTouchEvent` 里识别手势，**顺序不能反**：

```java
@Override
public boolean dispatchTouchEvent(MotionEvent ev) {
    mGestureDetector.onTouchEvent(ev);   // 必须在 super 之前
    return super.dispatchTouchEvent(ev);
}
```

原因：ViewPager 在收到 `ACTION_UP` 时会**同步**改写 `mCurItem`（"提交页右滑回文件页"的 fling 就靠这个）。
若检测器在 `super.dispatchTouchEvent` 之后喂，`getCurrentItem()` 已经是 0，
会把"从提交页滑回文件页"误判成"从文件页右滑返回"，直接误退。

配套：**用 `ACTION_DOWN` 时锁定的 tab 判定**，而不是抬手时读当前页。同理能区分"同一次手势内翻页"。

### 2.2 三种判定灵敏度

| 判定方式 | 触发条件 | 手感 | 适用 |
|---|---|---|---|
| 只看速度（`GestureDetector.onFling`） | 甩一下，`velocityX > 阈值` | 快甩才响应，慢拖无效 | 识别即退 |
| 只看位移（`onScroll` 累计 dx） | 拖过阈值，与速度无关 | 慢拖也响应，可预测 | **跟手必用**（否则拖到一半慢松手会弹回） |
| 位移 + 速度 | 两者都达标 | 最稳 | 参数多，边界难调 |

### 2.3 跟手滑动的真正成本：抢手势归属

复杂度不在动画，在**谁拥有这次手势**：

| | 识别即退 | 跟手滑动 | 自定义 SwipeBackLayout |
|---|---|---|---|
| 手势归属 | **不干预**，只在 `dispatchTouchEvent` 里旁听 | **中途抢**：MOVE 阶段判定为水平右滑后调 `mViewPager.requestDisallowInterceptTouchEvent(true)` | 天然归自己，但要在 `onInterceptTouchEvent` 里判断是否该让内层 ViewPager 处理 |
| 抢的代价 | 无 | ViewPager 一旦被 disallow，**本次手势内彻底失去控制权**（其覆写置 `mIsUnableToDrag=true`，只能等下次 DOWN 复位） | 父子竞争：抢早了列表横滚不动，抢晚了 ViewPager 已开始翻页 |
| 副作用 | ViewPager 在第 0 页右滑会画**边缘光晕**（edge effect） | 光晕会跟着被平移的内容一起动，需 `setEdgeEffectEnabled(false)` 或忽略 | 同左 |
| 布局改动 | 无 | 给内容加 id 做 `translationX` | 新增 ViewGroup + 改层级 |
| 代码量 | ~50 行 | ~140 行 | ~250 行 |

### 2.4 ViewPager 切页效果 vs 跨 Activity 返回

ViewPager 的文件→提交效果本质是 **同一窗口内两层页面同时反向平移**（跟手）。
"右滑回仓库列表"是**跨 Activity** —— 仓库列表页在另一个任务栈条目里，详情页动的时候它没法同时动。
所以**字面 1:1 复刻做不到**，能做的是同等级手感的近似，差别在"下面露出什么"。

### 2.5 三条实现路线

| | A · 真两层 | B · 假两层 | C · 塞进 ViewPager 当虚拟页 |
|---|---|---|---|
| 跟手 | ✅ 位移阈值判定 | ✅ 同左 | ✅ ViewPager 原生，最跟手 |
| 底层露出 | **真实的仓库列表页**（活的：滚动位置、标签筛选都还在） | 自身背景 + 一个"←"提示 | 假的列表页占位 |
| 主题改动 | 详情页要半透明窗口 | 无 | 无 |
| 栈依赖 | **强依赖**：栈里得有列表页 | 无 | 无 |
| 硬伤 | 桌面快捷方式直启时栈里没有列表页 → 透出桌面壁纸，必须降级（见 §2.6） | 拖动中看到的不是真列表 | ① 页数 3→4，`PagerTitleStrip` 会多一个空 tab（该 strip 已被 R8 `isDecor` hack 动过，见 `RepoDetailActivity.java:157`，别碰）；② 滑到假页过程中 Activity 销毁 → 闪烁；③ 假页内容是假的，观感割裂 |

### 2.6 快捷方式入口：真两层必须降级

桌面快捷方式由 Launcher 启动，带 `FLAG_ACTIVITY_CLEAR_TASK` → **任务栈被清空，详情页下面是空的**。

| 入口 | 栈里的下一个 Activity | 半透明窗口拖开时露出什么 |
|---|---|---|
| 仓库列表点进 | `RepoListActivity` | 真的仓库列表 ✓ |
| 桌面快捷方式直启 | 无 | **桌面壁纸 / 黑屏**（必现观感 bug，不是偶发） |

**降级做法**（同一个 Activity，运行时两套底层）：

| | 从列表页进 | 从快捷方式进 |
|---|---|---|
| 窗口 | 半透明 | 半透明（同一主题） |
| 底层铺色 View | **不加** → 拖开露出真列表 | **加一层主题背景色** → 拖开露出纯色，不透桌面 |
| 拖动观感 | 上层右移，下层真列表原地不动 | 上层右移，下层纯色 |
| 松手 | 列表页左滑入 | 详情页滑出、回桌面 |

入口识别：列表页启动时显式带标记（如 `EXTRA_UNDER_LIST=true`），快捷方式只带 `repo_id` 不带该标记。
比复用 `hasExtra("repo_id")` 反推更清楚、不依赖推断。

连带问题：半透明窗口丢失窗口阴影 / elevation，上层与下层会"贴"在一起没有层次感 ——
真两层需补左边缘 1dp 分隔线或阴影，假两层不需要。

### 2.7 系统预测性返回（最省事但不可用）

Android 13+ 的系统返回手势自带跟手动画，几乎零代码。但：

- 本项目 `targetSdk 31`（`app/build.gradle:40`），要用得升到 33+ 并开 `enableOnBackInvokedCallback` ——
  全 app 行为变化，代价远大于收益；
- 且它是**从屏幕边缘往内滑**，不是"页面内任意位置右滑"，与"文件页内右滑"的诉求本来就不是一回事。

---

## 3. 若将来实施：推荐路线与改动清单

**推荐路线 A（真两层）+ 快捷方式入口降级**。理由：唯一能同时满足"跟手"与"拖动中预判松手后看到什么"，
而后者正是 swipe-back 手感的价值所在。路线 B 是保底（跟手照样有，底层只露纯色）。

### 改动清单（路线 A）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `res/values/styles.xml` | 新增详情页半透明主题变体：`windowIsTranslucent=true` + `windowBackground=@android:color/transparent` |
| 2 | `AndroidManifest.xml` | 详情页 `android:theme` 指向新主题（`BaseCompatActivity.onCreate` 里 `setTheme` 已在，可按 flag 在运行时再切一次） |
| 3 | `ui/screens/repoDetail/RepoDetailActivity.java` | ① `dispatchTouchEvent` 覆写：MOVE 阶段判定水平右滑 → `requestDisallowInterceptTouchEvent(true)` + 平移 `getWindow().getDecorView()`；② 松手过阈值（建议 35% 屏宽）→ 走转场并 `finish()`，否则回弹；③ 底层铺色 View 按入口 flag 决定加不加；④ 转场换成 slide（现有 `backTransition()` 是缩放淡出，半透明窗口上很怪） |
| 4 | `ui/components/adapters/RepoListAdapter.java` | 列表页启动详情页时带 `EXTRA_UNDER_LIST=true` |
| 5 | `res/anim/` | 新增 2 个：`swipe_back_exit.xml`（0→100%p）、`swipe_back_enter.xml`（-100%p→0） |
| 6 | `res/values/strings.xml` + `values-zh-rCN/strings.xml` | 如有新增可见文案，两边同步（AGENTS.md 约定） |

### 实施要点

- **手势判定用位移阈值，不用速度**（跟手场景必需，见 §2.2）。
- **DOWN 时锁定 tab**（见 §2.1），且只在 tab==文件页生效（提交页/状态页右滑仍是 ViewPager 切页）。
- **松手时先把 `translationX` 归零再 `finish()`**，否则退出动画起点错位（页面从偏移位置开始滑）→ 看起来像跳帧。
- **返回语义复用现有 `onKeyUp(KEYCODE_BACK)` 那条链**（搜索条先收 → 子目录先上跳 → 最后才回列表），
  把这段抽成 `handleBack()` 供系统返回与手势共用，不要写两套。
- 排除态：右侧抽屉打开中、搜索条输入框聚焦。

### 验证矩阵（模拟器，按 `.zcode/skills/mgit-emulator/SKILL.md`）

| 场景 | 期望 |
|---|---|
| 文件页右滑拖到一半松手（不足阈值） | 回弹，无误退 |
| 文件页右滑过阈值 | 详情页右滑出、列表页左滑入 |
| 文件页右滑（列表页在栈里） | 拖动中露出真实仓库列表 |
| 文件页右滑（快捷方式入口） | 拖动中露出纯色，不透桌面 |
| 文件页左滑 | 仍切到提交页，不受影响 |
| 提交页右滑回文件页 | **不得误触发返回**（DOWN 锁定 tab 的核心用例） |
| 子目录内右滑 | 先上跳一级目录，不退出（复用返回语义） |
| 搜索条开着右滑 | 先收搜索条 |
| 右侧抽屉开着右滑 | 不触发 |
| 竖向滚动列表时右滑 | 不误触发 |

---

## 4. 已排除的方案与原因

| 方案 | 排除原因 |
|---|---|
| 升 `targetSdk 33` 用系统预测性返回 | 全 app 行为变化；且是边缘手势，不是页面内右滑 |
| ViewPager 插虚拟"返回页" | `PagerTitleStrip` 多空 tab（该组件已被 R8 hack 动过）；滑动中 finish 闪烁；假页割裂 |
| 合并列表页与详情页为单 Activity | 详情页有多个外部入口（快捷方式、Intent、clone URL），架构改动过大 |
| 拖动时露出仓库列表**截图** | 截图随列表滚动失真，且要额外内存与截图时机 |
| 改造 framework SearchView 顺带做手势 | 与本需求无关，且 AGENTS.md 已有决议禁止改造 SearchView |
