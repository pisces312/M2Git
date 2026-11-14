# GitHandy：独立项目化方案（暂缓执行）

> **状态（2026-11-13）：方案已归档，代码未动。**
> 未建分支、未改任何生产文件、未 commit / push、未做 GitHub 侧任何动作（未改名、未 detach）。
> 本文是未来恢复这项工作的唯一入口：包含决策依据、证据行号、风险与待确认项。

**硬约束**：项目可以脱离上游成为独立项目，但 **README 必须继续写明基于上游**，且保留三方署名。
这既是用户明确要求，也是 GPL-3.0 的合规需要。

---

## 0. 归档时的仓库快照

| 项 | 值 |
|---|---|
| 基线 commit | `2aca9fa fix(files): .bat 默认走内置文本预览，收敛文本判定到 FsUtils.isTextFile()` |
| 分支 / 工作区 | `main`，干净 |
| 版本 | `versionCode 287` / `versionName 1.8.13-pisces312`（`app/build.gradle:42-43`） |
| 最新公开 release | **v1.8.11**（2026-10-18）；1.8.12 / 1.8.13 只有本地签名包，**无 tag、无 release** |
| 历史 release | 7 个，均带 APK / 截图附件 |
| 最近正式版构建 | `M2Git-v1.8.13-pisces312-arm64-signed.apk`，12,079,768 B，SHA-256 `56e38ae1fdd50925aa7b7c96e6efbc0a5b8974dd7bd009e9409003ed07ea4c6e` |
| 签名证书 | SHA-256 `abadebd2fc9523628b5dacfa0fb40f652df0e161b71820c7a4b5a40653ee0b90`，自 v1.8.5 起统一，**不得更换** |
| 远端 | `origin = git@github.com:pisces312/M2Git.git`；`upstream = https://github.com/Zacharia2/M2Git.git` |
| GitHub 元数据 | PUBLIC、`isFork=true`、`parent=Zacharia2/M2Git`、`source=maks/MGit`、18.6 MB、0 子 fork、0 stars、Issues 已开且 0 open |
| CI | 仅 `.github/workflows/build.yml`（push main → `assembleDebug testDebug`）；`gh run list` 为空，无自动发版 |

---

## 1. 新项目名：`GitHandy`

### 结论与查重

- GitHub `search/repositories?q=GitHandy+in:name` → **total = 0**。
- Web 检索只见 `mchandleraz/git-handy`（2016 年 shell 插件，1 star）与同名博客文章，品类不同、无 Android 应用、无规模项目。
- 名称含 `git`（一眼知道是 Git 客户端）+ `handy`（顺手 / 便携 / 随手可用），不绑定 Android 具体版本，也不绑定单一厂商。
- 中文可用「随手 Git」；仓库名、应用名、APK 前缀统一用 `GitHandy`。

### 为什么弃用 `GitPalm`

`Palm` 不是普通词：1992 年成立的 PDA / 智能手机品牌（`PalmPilot`、`Treo`、`Palm Pre`、webOS），
2010 年 HP 收购、2011 年终止硬件，2014–2015 年商标转给 TCL，2018 年授权推出过小屏 Palm Phone，
**截至 2026 年商标仍归 TCL，品牌休眠但未失效**。GitHub 上另有 `gitpalm` org 占位。

两层问题：

1. 商标：`GitPalm` 作仓库名风险低，但作为应用名上架商店时落在持有人核心类别附近，存在投诉尾部风险。
2. 定位：Palm 让人联想到 90 年代 PDA 与已停产平台，与 Android 12+ 现代客户端的「移动」诉求方向相反。

### 淘汰名单（均有查重依据）

| 名字 | 淘汰原因 |
|---|---|
| `GitPocket` / `PocketGit` | Google Play 已有同名 Android Git 客户端（EifelBytes，`com.gitpocket.app`，2026-04 上架）；GitHub 近名 28 / 16 个 |
| `Git2Go` / `GitGo` | `Git2Go` 是知名 iOS Git 客户端且 `libgit2/git2go` 是 Go 绑定；`GitGo` 近名 1238 个 |
| `GitDroid`(110) / `GitTap`(80) / `GitPal`(63) / `GitMobi`(46) / `GitSwipe`(10) | 占用多、辨识度低；`GitDroid` 还绑死 Android |
| `MobiGit` / `GitOnHand` / `GitOnGo` | 均为合格备选：`MobiGit` 最直白（同名仅 2016/2017 的 0-star 死仓库）、`GitOnHand` GitHub 零占用、`GitOnGo` 与 `Git2Go` 读音近 |

---

## 2. 脱离上游是否适合

### 事实

| 维度 | 数据 |
|---|---|
| 直接上游 `Zacharia2/M2Git` | 最后 push 2026-02-26；其 main HEAD 正是 merge-base，**上游独有提交 0**；最新 release v1.8.4 |
| 本 fork 分歧 | 自 v1.8.4 起 91 commits / 190 文件 / `+13015 −12836` 行 |
| 网络源头 `maks/MGit` | 2026-09-18 仍有推送，仍活跃；README 写明 "MGit is a Git client Android App"，是 `sheimi/SGit` 的延续 |
| 仓库体量 | 18.6 MB（< 1 GB），PUBLIC，0 子 fork，0 stars |

### 判断

**适合脱离，且当前是低成本窗口。** 保持 fork 的唯一实质收益是「将来能同步上游」，
而直接上游已 8.5 个月零提交、分歧量大到无法直接 merge（只能 cherry-pick），收益趋近 0。
成本却是：贡献不计入 GitHub 贡献图、Issues 默认禁用（本项目是手动开启的）、UI 带 "forked from"、
仓库 description 仍写着早已移除的 WebDAV。源头 `maks/MGit` 仍活跃，脱离后改作只读观察源即可。

### 法律约束（GPL-3.0）

- 上游仓库 license metadata = **GPL-3.0**，根目录有 `COPYING`（GPLv3 全文）。
- 允许独立，条件是：整体继续 GPL-3.0（或 `-or-later`）、保留版权声明与 `COPYING`、标明修改、公开完整源码。
- **不要改成 MIT / Apache**：README 里「由 `maks@manichord.com` 编写的所有代码可选用 MIT 许可证」
  只覆盖原作者那部分，不覆盖 `Zacharia2` 及其他贡献者，实操无法逐文件甄别。
- 现有两处必须修的瑕疵：README 中英两段的 `[GPLv3](./LICENSE)` 是**悬空链接**（仓库只有 `COPYING`）；
  两段都写着过时的「当前版本 1.8.10」。
- 署名建议写全链条：`maks/MGit`（原始）→ `Zacharia2/M2Git v1.8.4`（直接上游）→ `GitHandy`（本仓库），
  并注明「自 v1.8.4 起的修改见提交历史与 `docs/`」。

### 分阶段方案

| 阶段 | 动作 | 可逆性 |
|---|---|---|
| **P0** | 修 README 双语标题与三方署名、版本 1.8.10 → 实际版本、`./LICENSE` → `./COPYING`、description 去掉 WebDAV；把「自用 fork」改成「独立项目，基于…」但**保留基于上游的声明** | 可逆 |
| **P1** | 备份：下载 7 个 release 的正文与全部附件、记录 Issues / PR / Star / Watch、`git clone --bare` 完整镜像 | — |
| **P2** | GitHub 仓库改名 `M2Git → GitHandy`（URL 自动重定向，可逆）；随后 `Settings → Danger Zone → Leave fork network` | **永久不可逆** |
| **P3**（可选） | 品牌与包名独立，见第 3 节 | 部分不可逆 |

**P2 的官方约束**（GitHub Docs「Detaching a fork」）：

- 「Leave fork network」仅当 fork 公开、< 1 GB、无子 fork 时可用 —— 本项目**三条都满足**。
- 新仓库**不保留** Issues、PR、Wiki、Star、Watch、评论、子 fork 等元数据；git 提交元数据保留。
- 永久，且无法重新连回 fork 网络。官方文档**只保证 git 提交元数据**，**未明确 releases 及二进制附件是否保留** → 这是 P1 必须先做完整备份的原因。
- 若想保留 Issues / PR 历史，可走 GitHub Support 协助分离（官方称可保留），或手动 `bare clone → 删 fork → 同名新建 → push --mirror`（更彻底但丢配置）。
- 手动方式属于删除仓库，会永久删除关联 PR 与配置，不可撤销。

**P2 后必须复查**：Issues 仍开启 —— 设置页「Feedback」用的 `feedback_url`（`values/strings.xml`，`translatable="false"`）
指向 `https://github.com/pisces312/M2Git/issues/new`；GitHub 对 fork 默认禁用 Issues，禁用时该 URL 是 404 且 app 侧无兜底。
仓库改名后需同步把 URL 改成新仓库名，并重新确认 Issues 开关。

**不确定性**：官方原文对贡献计数用的是 "may become eligible"，不是保证；release 附件是否随 detach 保留没有明确说明。

---

## 3. 改名与包名

### 「包名」有三个层次，不要一把梭

| 层 | 当前值 | 建议 | 影响 |
|---|---|---|---|
| `applicationId` | `ts.realms.m2git`（`app/build.gradle:37`，debug 后缀 `.debug` 在 `:89`） | **改** —— 这才是用户说的「包名」 | APK 标识，决定能否覆盖升级 |
| `namespace` | `ts.realms.m2git`（`app/build.gradle:35`） | 建议不改 | R / BuildConfig 包名 |
| Java `package` 声明与源码目录 | `ts.realms.m2git`，约 150 文件 | 建议不改 | 大迁移：R / BuildConfig / dataBinding / 测试 / FileProvider 全要跟 |

`git grep -l -i -E "m2git|mgit"` 命中 **175 个跟踪文件**，绝大多数只是 Java 包声明。
只改 `applicationId` 可把改动面压到 3–5 个文件；连 Java 包名一起改收益低、风险高。
建议值：`io.github.pisces312.githandy`。

### 改 `applicationId` 的代价（务必先读）

1. **无法覆盖升级**：Android 视为不同应用，新旧包可共存；签名必须保持 `abadebd2…`，否则雪上加霜。
2. **本地仓库会「消失」**：仓库在 `getExternalFilesDir()` 下，路径含包名，新包看不到旧目录里的 git 仓库。
3. 偏好、SSH 私钥、加密凭据都在旧沙箱；`preference_file_key` 是字符串常量 `ts.realms.m2git`
   （`values/strings.xml:7`），不会自动跟着变；`FileProvider` authority 用 `getPackageName()` 拼接，会自动跟随。
4. 桌面快捷方式（`RepoListAdapter.java:798-801` 的 `repo_%d` + `repo_id`）指向旧包，全部失效。

**可行迁移路径**（需真机实测）：用现有加密备份（SAF + PBKDF2 + AES/GCM）在旧包导出 → 装新包 → 在新包恢复 →
用「添加本地仓库」重新指向旧外部目录。旧目录在新包下是否仍可访问尚未验证，这是该项最大的未知。
若实测不可行，需要在旧包里提供一次性「导出仓库清单 / 迁移仓库目录」能力，属于新增功能。

### 需要改的文案落点（C1）

`README.md`（双语镜像结构）、`AGENTS.md`、`CODE_OF_CONDUCT.md`、`app/src/main/res/values/strings.xml`
（`app_name` 当前为小写 `m2git`，见 `:4`；`feedback_url`；`clone_with_mgit`）、
`values-zh-rCN/strings.xml`、`app/src/main/res/values/themes.xml`（样式名 `Theme.M2Git.M3*`）、
`build.sh` 产物前缀、`scripts/dev.sh`、`docs/*.md`、`.zcode/skills/mgit-emulator/SKILL.md`、
`settings.gradle`（先确认有无 `rootProject.name`）。
**不要改**：`docs/MGit-vs-M2Git.md` 等历史对比文档的既有事实表述；`app/src/main/assets/js/codemirror-bundle.min.js`
与 `app/src/main/res/raw/server.bks` 仅为内容命中，不是名称引用。

### 分支与提交策略

- 从 `main`（`2aca9fa`）切 `feature/githandy-rebrand`；**暂不 push**，等确认。
- 三个可独立回滚的提交，只在最后一个提交做一次构建验证（项目约定：中间提交不要求独立编译）：
  - **C1** 名称与文案（含 README 三方署名、修悬空链接与过时版本号），低风险可逆；
  - **C2** `applicationId` 独立改动 + 迁移说明文档，高风险、便于单独回滚；
  - **C3** 每仓库主题色功能（第 4 节）。
- 构建验证：`./scripts/dev.sh check` → `./scripts/dev.sh apk`；若改了 `AndroidManifest.xml` 必须用 `apk` 档。

---

## 4. 每仓库主题色

### 需求澄清

用户原话「每个仓库进去可以随机主题颜色，不会觉得那么单调」。按**每个仓库一个固定色**理解：
用 `Repo.getID()` 散列到预定义调色板，同一仓库每次进入颜色恒定，形成视觉记忆。
每次进入真随机不推荐 —— 颜色跳动、无记忆点、容易被当成 bug。**此项仍需用户确认。**

### 现有生效路径（已按「grep 全仓调用点」核实，非按文件名推断）

| 环节 | 位置 |
|---|---|
| Application 注册 / Manifest 默认主题 | `app/src/main/AndroidManifest.xml:29` / `:38` |
| 动态取色**唯一**注册点 | `app/src/main/java/ts/realms/m2git/MainApplication.kt:70-75`（`DynamicColors.applyToActivitiesIfAvailable` + precondition） |
| 主题定义 | `app/src/main/res/values/themes.xml:14-16` `AppTheme` → `:32-51`；`:19-21` `DarkAppTheme` → `:53-70`；自定义 attr 在 `:2-10` |
| 主题选择 | `app/src/main/java/ts/realms/m2git/local/preference/Profile.java:71-74` |
| **全仓唯一 `setTheme`** | `app/src/main/java/ts/realms/m2git/ui/screens/main/BaseCompatActivity.java:67`（`super.onCreate` **之前**） |
| 详情页取仓库 | `app/src/main/java/ts/realms/m2git/ui/screens/repoDetail/RepoDetailActivity.java:108-113`（`Repo.TAG` 或 `repo_id` 回退） |
| 详情页 `setContentView` | `RepoDetailActivity.java:123` |
| 稳定色源 | `app/src/main/java/ts/realms/m2git/core/models/Repo.java:263-265` `getID()`（DB 自增主键） |
| 每次进仓库新建 Activity | `app/src/main/java/ts/realms/m2git/ui/components/adapters/RepoListAdapter.java:611-613` |
| 偏好存储范式 | `Profile.java:20-27, 88-101` + `values/strings.xml:316,319` + `res/xml/preferences.xml:10-21` |
| 切换主题重建返回栈 | `app/src/main/java/ts/realms/m2git/ui/screens/settings/SettingsFragment.java:202-209` |
| 死代码 | `themes.xml:77-161` 的 `Theme.Sgit*` / Holo 系列，仅文件内自引用，无 Java / Manifest / layout 引用 |

### 关键冲突

依赖 `com.google.android.material:material:1.12.0`（`app/build.gradle:150`）。
`DynamicColors.applyToActivitiesIfAvailable` 通过 `ActivityLifecycleCallbacks` 在 **`onActivityPreCreated`**
调用 `activity.getTheme().applyStyle(overlay, true)`；库 Javadoc 明确警告此后叠加其它 theme overlay 会覆盖颜色。
本项目 `MainApplication.kt:73` 的 precondition 目前忽略 `Activity` 参数、只做二值开关，
且 `DynamicColorsOptions` 只支持一个固定 themeOverlay —— **直接再叠一个仓库色会被动态取色覆盖或反向覆盖**。

### 实现方案

1. **互斥**：扩展 `MainApplication.kt:70-75` 的 precondition，利用其第一个 `Activity` 参数判断
   「是否为 `RepoDetailActivity` 且 per-repo 配色开启」→ 返回 `false` 跳过动态取色。
2. **应用**：在 `BaseCompatActivity.java:67` 的 `setTheme` 之后、`super.onCreate` 之前，
   从 `getIntent()` 取 `Repo.TAG` / `repo_id`，用 `Repo.getID()` 算出色号，
   `getTheme().applyStyle(repoOverlay, true)`。必须早于 `RepoDetailActivity.java:123` 的 `setContentView`。
3. **调色板**：预定义 8 个 theme overlay（浅 / 深各一套），只染强调色
   （toolbar / statusBar / 标签 chip / FAB 等，复用 `themes.xml:2-10` 的自定义 attr → M3 role 映射），
   保留背景与文本色以规避对比度风险。不推荐整页替换 M3 color scheme。
4. **开关**：新增 `pref_key_repo_theme_color`，按 `Profile.java` getter + `strings.xml` 键 +
   `preferences.xml` `SwitchPreferenceCompat` 的现有范式接入。
5. **无需 `recreate()`**：每次进入仓库都是新建 Activity；仅当在已存在页面内换色时才按
   `SettingsFragment.java:202-209` 重建返回栈（动态取色 precondition 只在 Activity 创建时求值）。

### 待确认

- 每仓库固定色 vs 每次进入真随机。
- 从详情页打开的 `ViewFileActivity` / `CommitDiffActivity` 是否沿用同一颜色（若沿用，需透传 repo id）。
- 作用范围：仅强调色（推荐）还是整页 color scheme。

---

## 5. 恢复工作前必须确认的三项决策

1. **包名范围**：只改 `applicationId`（推荐，`io.github.pisces312.githandy`），还是连 `namespace` / Java 包目录一起改？
2. **配色语义**：每仓库固定色（推荐）还是每次真随机？子页面是否沿用？
3. **脱离时机**：只做分支内代码与文档（GitHub 侧不动），还是同时执行仓库改名 / `Leave fork network`？

## 6. 恢复时的执行顺序（checklist）

```
[ ] 确认上述 3 项决策
[ ] git checkout -b feature/githandy-rebrand main
[ ] P1 备份：release 正文+附件、Issues/PR/Star、bare clone
[ ] C1 名称与文案（保留三方署名、修 ./LICENSE 与过时版本号）
[ ] C2 applicationId + 迁移说明（先实测旧 external 目录可访问性）
[ ] C3 每仓库主题色
[ ] ./scripts/dev.sh check && ./scripts/dev.sh apk
[ ] 真机验证：升级链 / 本地仓库可见性 / 深浅主题配色 / Feedback URL
[ ] P2 仓库改名 → 验证重定向与 Issues → Leave fork network（不可逆，最后做）
[ ] 补发 tag / release（当前公开版仍停在 v1.8.11，1.8.12、1.8.13 未发）
```

---

## 附：本次归档前的既有结论出处

- 提交与发布流程：`android-fork-github-release` skill；项目特定约定以 `AGENTS.md` 为准
  （本项目无 `CHANGELOG.md`、tag 用轻量 tag、CI 不自动发版）。
- fork 独立性事实、许可证与 detach 官方条款：见 `.workbuddy/memory/2026-11-13.md` 与 GitHub Docs
  「Detaching a fork」。
- 主题生效路径逐项行号：本文件第 4 节表格（源自全仓 grep 调用点核实，2026-11-13）。
