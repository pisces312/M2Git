# Shallow clone depth 调研：现状、pull 行为与默认值建议（2026-10-20）

状态：**调研存档；§5.3 的 hint 文案补充已于 2026-10-20 实施**（方案 A：仅说明 1 的含义），其余内容（默认值改造、加深能力）均未动代码。本文回答三个问题：M2Git 的浅克隆深度怎么用、浅克隆仓库能不能继续 pull、若要默认浅克隆默认值取多少及其改法。

---

## 1. 背景

浅克隆（`git clone --depth N`）只拉取最近 N 层提交，能显著降低克隆下载量与耗时，对移动端有意义。fork 在 v1.8.5 引入过可选的 depth 输入（commit `5f6d0b1`，2026-04-29），但默认空 = 全量克隆。本文确认其完整行为链路，并评估"默认浅克隆"的方案。

## 2. 现有实现：depth 的四层流转

```
克隆对话框 EditText(number) → CloneViewModel.cloneDepth → cloneRepo() 解析兜底
    → CloneTask(mCloneDepth) → CloneCommand.setDepth()
```

### 2.1 输入框

`app/src/main/res/layout/clone_view.xml` L96–113：`TextInputLayout` 内一个 `TextInputEditText`，`android:inputType="number"`（仅非负整数），提示语 `@string/dialog_clone_depth_hint`（英文 *"Shallow clone depth (empty for full)"*），双向绑定 `android:text="@={viewModel.cloneDepth}"`。

### 2.2 ViewModel 解析与兜底

```java
// ui/screens/main/CloneViewModel.java:26,37
public MutableLiveData<String> cloneDepth;
this.cloneDepth = new MutableLiveData<>("");   // 初值空 = 全量克隆

// :65-73
int depth = 0;
try {
    String depthStr = this.cloneDepth.getValue();
    if (depthStr != null && !depthStr.isBlank()) {
        depth = Integer.parseInt(depthStr);
    }
} catch (NumberFormatException e) {
    Timber.w("Invalid clone depth, using full clone");
}
CloneTask task = new CloneTask(repo, this.cloneRecursively, depth, "", null);
```

空白 → 0；非数字 → 捕获后兜底 0；`0` 与 0 均表示全量克隆。

### 2.3 Task 守卫

```java
// core/command/tasks/remote/CloneTask.java:75-77
if (mCloneDepth > 0) {
    cloneCommand.setDepth(mCloneDepth);
}
```

`CloneCommand` 同时设置 `setCloneAllBranches(true)` 与 `setCloneSubmodules(mCloneRecursive)`。

### 2.4 旁路入口（不受对话框默认值影响）

`ui/screens/main/RepoListActivity.java` L130：处理外部 `git://` intent 直达克隆时硬编码 `new CloneTask(mRepo, true, 0, cloningStatus, null)`，即 intent 路径永远全量克隆。

## 3. JGit 侧验证（对 `app/libs/org.eclipse.jgit-7.8.0.202609011348-r-stripped.jar` 反汇编确认）

1. **`CloneCommand.setDepth(int)` 对 depth < 1 直接抛 `IllegalArgumentException`**（`JGitText.depthMustBeAt1`）——CloneTask 的 `> 0` 守卫是必需的，传 0/负数会崩。
2. **depth 与 `setCloneAllBranches(true)` 兼容**：`CloneCommand.fetch()` 内部把 depth 透传给 `FetchCommand.setDepth()`，不分支于 fetchType；浅克隆时每个分支 tip 各取 depth 层提交。
3. **浅状态持久化**：`.git/shallow` 由 `ObjectDirectory.getShallowCommits()/setShallowCommits()` 维护。
4. **浅协议完整**：`BasePackFetchConnection` 每次 fetch 读取本地 shallow 集合并发送 `shallow <sha>` 行（`ObjectDatabase.getShallowCommits()` → `sendShallow()`），处理服务端 `shallow` 响应行更新边界；`FetchV0Request`/`FetchV2Request` 均支持 shallow/deepen；传输不支持时抛 `shallowNotSupported`。
5. **加深能力已内置但未被 app 调用**：`FetchCommand` 提供 `setDepth(int)`、`setUnshallow(boolean)`、`setShallowSince()`、`addShallowExclude()`——JGit 7.8 支持后续 fetch 加深或整体 unshallow；M2Git 全仓 `setDepth` 仅出现在 CloneTask 一处（grep 确认），FetchTask/PullTask 均未使用。

## 4. 浅克隆仓库的后续行为

| 操作 | 结论 | 依据 |
|---|---|---|
| pull（fetch + merge） | **正常**。fetch 时本地 shallow 边界发给服务器，只回传边界之后的新提交，边界随之前移 | §3.4；`PullTask.java:68-101`、`FetchTask.java:81-89` 均为裸 `git.pull()`/`git.fetch()`，不带 depth 也不影响 |
| merge 前提 | merge base 必须在浅边界之内。远程在浅 tip 之上追加提交 → 正常；远程历史在浅边界之下分叉（rebase、force-push、合并基线低于边界的旧分支）→ merge base 缺失，pull 报错 | 与 git CLI 一致的 shallow 语义 |
| 自动加深 | **不会**。pull/fetch 不携带 depth，浅边界不后退 | §3.5 |
| push | 正常（服务器已有父链）；推浅边界以下的 refs 会被服务端拒绝 | 标准 git 行为 |
| `file://` 本地传输 | **不支持 shallow**，浅克隆本地路径仓库可能直接失败 | §3.4 `shallowNotSupported` |
| LFS | 无影响。`Repo.applyLfs()` 仅把 `filter.lfs.clean/smudge` 一次性写入 `.git/config`，后续 pull/checkout 的 smudge 均走该 filter | `Repo.java:246-255` |

## 5. 若要默认浅克隆：改法与默认值

### 5.1 改法（二选一，推荐 A）

- **A（推荐）**：`CloneViewModel.java` L37 初值 `new MutableLiveData<>("")` → `new MutableLiveData<>("1")`。输入框预填 1，用户可清空退回全量，与 hint "empty for full" 语义自洽。
- B：仅改 L65 兜底 `int depth = 0` → `int depth = 1`。此改法使"留空"不再等于全量，与提示语矛盾，不推荐单独使用。

若同时希望 intent 旁路也浅克隆，需同步改 `RepoListActivity.java` L130 的硬编码 `0`。

### 5.2 默认值建议

| 取值 | 下载量 | 代价 |
|---|---|---|
| **1（推荐）** | 最小，每分支仅 1 个提交 | 浅后看不了旧 commit / 跨 commit diff / blame；且 app 内无加深路径，恢复全量只能删库重克隆 |
| 10–50 | 略大 | 折衷：可翻有限页历史，深层仍断 |
| 空（现状） | 全量 | 功能完整，克隆慢、占空间 |

结合 M2Git 作为移动端客户端以浏览代码为主的场景：**只看当前代码取 1；想保留历史能力维持空**。默认 1 的核心取舍是"浅后不可加深"（§4），可接受再默认。

### 5.3 顺手的小问题（hint 文案，已处理）

~~`dialog_clone_depth_hint` 只有英文，`values-zh-rCN/strings.xml` 漏翻译，中文界面回退英文。~~
**2026-10-20 已按方案 A 实施**（纯文案，不碰代码/布局/默认行为）：

- `values/strings.xml`：`Shallow clone depth (empty for full)` → `Shallow clone depth: 1 = shallow (latest commit only); empty = full`
- `values-zh-rCN/strings.xml`：补齐翻译 `浅克隆深度：1 = 浅克隆（仅最新提交）；留空 = 全量克隆`

用户当时选择了最简方案（仅说明 1 的含义，不做“无历史/不可加深”的后果提醒）；若后续要加提醒，见 §6.3。

## 6. 后续可选增强（本文档范围内，未实施）

1. **加深 / 取消浅克隆**：在仓库详情页加菜单，调 `git.fetch().setDepth(N)` 或 `setUnshallow(true)`（JGit 7.8 已支持，§3.5）。
2. **设置项**：把默认 depth 收进设置页，而非写死 ViewModel 初值。
3. 二者任一实施时，需同步 `values/` 与 `values-zh-rCN/` 文案，并在 release notes 说明浅克隆对历史浏览的影响。
4. **（2026-10-20 追加）后果提醒未做**：hint 目前只说明“1 = 浅克隆（仅最新提交）”，不含“无历史/无法在应用内加深”的警告（用户选了最简方案 A）。若后续要补，直接改这两个 strings 文件即可，无需动布局；也可考虑改用 `TextInputLayout` 的 `app:helperText` 做常驻提醒。

## 7. 已实施：浅边界提交不再退化成整仓 diff（2026-10-20）

**现象**：浅克隆（depth=1）只有一个提交，点开它的 diff 页面长时间转圈，随后涌出**整个仓库全部文件 ADD**。

**根因**：边界提交的父对象不在本地，JGit 把它的 `getParentCount()` 归零（见 §4 的
`RevCommit` 行为），于是 `Repo.isInitialCommit()` 返回 true，`CommitDiffTask` 走
`EmptyTreeIterator` 做整树 diff。**不是报错，是被当成“首个提交”**。本仓库深度 1 实测：
467 个 entry / 2.02 MB diff 文本，前端 `local_commits_diff.js` 逐条同步 `hljs.highlight()`
再 append，页面 `Skipped 280 frames`，约 18 秒才渲染完，期间完全无响应。

**改法（方案 A：边界提交不伪造 diff）**：

| 位置 | 改动 |
|---|---|
| `Repo.isShallowBoundary(String commit)` | 新增。判据是 `.git/shallow` 集合（`ObjectDatabase.getShallowCommits().contains(id)`）。**不能**继续用 `getParentCount() == 0` —— 它在浅边界和真正的首个提交上同时成立 |
| `CommitDiffTask.getCommitDiff()` | 整仓模式（`mPathFilter == null`）命中浅边界时直接给空 entries，不 scan；新增 `isShallowBoundary()` 供界面查询 |
| `CommitDiffActivity.CodeLoader` | 透出 `isShallowBoundary()` 与提示标题/正文（取 strings） |
| `assets/js/local_commits_diff.js` + `.css` | 空结果时在提交信息块下渲染一条黄色提示（`shallowBoundaryNotice()`） |
| `values/`、`values-zh-rCN/` | `diff_shallow_boundary_title` / `diff_shallow_boundary_hint` |

**为什么不改 `isInitialCommit`**：真正的首个提交仍必须走整树 diff（那时“全部新增”是对的），
两个状态只能靠 `.git/shallow` 区分。全仓 `isInitialCommit` 只有 `CommitDiffTask` 一个调用点。

**范围**：只挡整仓 diff。单文件 diff（状态页长按某行，`mPathFilter != null`）不挡 ——
它只渲染一个文件，代价可接受，且“这个文件当时是什么样”对只读浏览仍有价值。

**实测（pixel6，1.8.12 debug）**：

| 场景 | 改前 | 改后 |
|---|---|---|
| 点浅边界提交（depth=1，467 文件） | 转圈约 18 秒后渲出 467 个 ADD 块，`Skipped 280 frames` / `Davey 5394ms` | 约 2 秒出提示，仅 `Davey 745ms`，无跳帧 |
| 点完整克隆的提交（cadcraft） | 正常 | 正常（`Cargo.lock` MODIFY diff 照旧） |

**刻意未做**（2026-10-20 用户决议）：不加“加深历史 / unshallow”入口 —— 浅克隆要保留，手机端以
阅读代码为主，边界提交的内容用「文件」页浏览即可。因此边界那条提交会长期保持“只有提示”的状态，
这是预期行为，不是缺陷。

**另一条与浅克隆无关的遗留项**：diff 页逐条同步高亮 + append 的渲染方式在大 diff 下仍会卡
（原方案 B：分批渲染 + 条目上限），本次未动，留作独立性能项。

