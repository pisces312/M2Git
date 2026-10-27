# M2Git 项目约定

## 构建与 ABI

- **默认仅构建 arm64-v8a**，不构建 x86_64：模拟器（x86_64 镜像带 ARM 转译）可直接安装运行 arm 版 APK，无需单独的 x86_64 包。
- 需要其他 ABI 时手动指定：`./gradlew assembleDebug -PbuildAbi=x86_64`。
- 一键脚本：`./build.sh [debug|release] [arm64|x86_64]`（默认 release arm64；release 需签名环境变量，见全局约定）。
  **build.sh 是 Bash 脚本（`#!/usr/bin/env bash`、`case`/`sed`/`du` 等 shell 语法），不是 PowerShell/cmd 语法，
  Windows 下必须用 Git Bash 跑**（Git Bash 终端里 `./build.sh`，或 PowerShell 里 `& 'D:\dev\Git\bin\bash.exe' build.sh ...`）。
  踩坑：PowerShell 里的 `bash` 解析到 `C:\WINDOWS\system32\bash.exe`（WSL），Windows 签名环境变量传不进去，
  release 构建会报 `KEY_STORE env var not set` —— 必须用 Git Bash。另：`*.sh` 已在 .gitattributes 约定 `eol=lf`；
  若 bash 报 `$'\r': command not found`，说明文件被检出成 CRLF 行尾，转回 LF 即可。
- CI（`.github/workflows/build.yml`）跑裸 `assembleDebug`，跟随 app/build.gradle 的默认 ABI 列表。

## Agent 构建回路（2026-10-23）

- 入口：`./scripts/dev.sh <check|apk|install>`（纯英文脚本，Git Bash 下运行）。默认 `--offline --console=plain`；新增/升级依赖时加 `--online`；不要日常 `clean`，确需清理用 `--clean`。
  - `check` = `:app:compileDebugSources`：资源合并、R 生成、data binding、Java/Kotlin 编译。**不含** AndroidManifest 最终合并、dex、打包、签名——改 `AndroidManifest.xml` 必须用 `apk` 档验证。
  - `apk` = `:app:assembleDebug`。
  - `install` = `apk` + `adb install -r`；可用 `ANDROID_SERIAL=...` 指定设备（无线序列号含空格，必须带引号）。
- `org.gradle.caching=true` 已开启：本地 build cache 在 `GRADLE_USER_HOME` 下，跨 git worktree 共享；前提是同一 commit 且 worktree 干净（`GIT_DIRTY` 是 BuildConfig 输入，脏/净的缓存键不同）。
- 实测（2026-10-23，暖 daemon，`--offline`）：暖 no-op `assembleDebug` 0.8s；强制全量 41 任务约 6–10s；全新 worktree 冷构建 20s（无 cache）→ 5s（有 cache，24/41 任务命中，含 javac 与 dex）。
- Gradle daemon 堆 2G（A/B 实测：强制全量 1G 中位 7–8s，2G 中位 5–6s）；Kotlin daemon 仍 1G。
- 内循环不要跑 `lint`/`test`/`clean`，不要关 configuration cache。

## 构建栈（2026-10 升级后）

- AGP 9.4.0 + Gradle 9.6.1，均用本地缓存（`GRADLE_USER_HOME=D:\dev\.gradle`）。
- AGP 9 内置 Kotlin：**不要再加** `org.jetbrains.kotlin.android` 插件或 `kotlinOptions{}`；Kotlin 编译选项跟随 `compileOptions`（Java 11）。
- `android.nonFinalResIds` 已移除，R 字段为非 final：Java 代码**不要写** `case R.xxx` / `switch(getId())`。

## 依赖升级与构建溯源（2026-10-07）

- **jgit 主包是「官方 jar 剥离版」**：`app/libs/org.eclipse.jgit-*-stripped.jar` 由 `app/libs/make-stripped-jgit-jar.sh <版本号>` 生成（下载官方包后仅剥离 `InflaterCache.class`）；`InflaterCompat`/`InflaterCache` 补丁类在 `app/src/main/java/org/eclipse/jgit/{compatible,lib}/`。升级 = 改 build.gradle 顶部 `jgitVersion` + 重跑脚本。**禁止直接换 Maven 官方包**：Android libcore 对已 `end()` 的 Inflater 复用时抛 "inflater has been closed"（上游 issue #33），补丁类就是防它；历史教训：上游手工补丁 jar 与官方包哈希不同，曾差点被当成可随意替换的同名文件。
- jgit 7.x 是 Java 17 字节码；minSdk 31 核心库 = OpenJDK 11，真机若遇 `NoSuchMethodError`（如 `Stream.toList`）→ 开 `coreLibraryDesugaring`。
- jsch 版本只在 build.gradle 顶部 `jschVersion` 一处定义，同时喂给 `implementation`、`resolutionStrategy` 重映射（JGit 的 com.jcraft 传递依赖靠它换成 mwiede fork）和 BuildConfig。
- 查库最新版本看 `repo1.maven.org/<group>/<artifact>/maven-metadata.xml`；search.maven.org 索引滞后（曾把 7.8.0 显示成 7.3.0）。
- **WebView 资产（jQuery/hljs/CodeMirror）是脚本重建的产物**：`scripts/rebuild-js-assets.sh`（纯英文）按顶部 pin 的版本从 jsdelivr/cdnjs 下载并 concat 出 `assets/js/codemirror-bundle.min.js`（只含 `CodeGuesser` 可达的 mode）等六个文件，校验和台账在 `scripts/js-assets.sha256`。升级 = 改脚本顶部三个版本变量 + `app/build.gradle` 顶部同名三处（→ BuildConfig → 设置页「关于」）+ 重跑脚本。**禁止手改产物文件**。踩坑：cdnjs 直连会 TLS 抖动（curl exit 35），脚本已带 `--retry-all-errors`；jsdelivr 对 ~400 KB 的 CM 核心按需压缩会 404，核心那份走 cdnjs。
- 构建溯源（`GIT_COMMIT`/`GIT_COMMIT_TIME`/`GIT_DIRTY`/库版本 → BuildConfig → 设置页「关于」）：在 build.gradle **配置期**经 `providers.exec` 读 git（configuration-cache 把 exec 当输入跟踪，commit 变化自动失效重建，参考 local-dream）。**不要**把 `providers.exec` 放进任务 doLast——执行期引用脚本对象会被 CC 拒绝；AGP 9 也不接受 Provider 形式的 `sourceSets.srcDir`。
- adb 无线设备序列号含空格/括号（mDNS `_adb-tls-connect._tcp`），`adb -s` 必须加引号；设备随手机休眠从列表消失，安装前先 `adb devices` 确认。模拟器 pixel6 用后台长任务启动。

## 模拟器上操作 MGit app

- 已有项目级 skill：`.zcode/skills/mgit-emulator/SKILL.md`（模拟器装包、权限重授、仓库列表导航、uiautomator 定位、截图验证、debug 数据库直查，以及 MSYS 路径改写 / 拼音 IME 两个坑）。凡是「模拟器 + 操作/测试 mgit app」的任务，先读该 skill 再动手。

## Git 远程

- `origin` = pisces312/M2Git（fork，主远程；`main` 跟踪它，日常提交推这里）
- `upstream` = Zacharia2/M2Git（原仓库，同步用：`git fetch upstream` 后比对 `main..upstream/main`）
- 阿里云 codeup 备份远端已于 2026-10-11 删除（它原先占用 `origin` 名字，fork 远端 `github` 改名 `origin` 后不再存在），当前仓库只有上面两个远端。
- **fork 已开 Issues**（2026-10-17 `gh repo edit pisces312/M2Git --enable-issues`）：设置页「关于 → Feedback」用的 `feedback_url`（`values/strings.xml`，`translatable="false"`，只在 `SettingsFragment` 里经 `ACTION_VIEW` 打开）指向 `https://github.com/pisces312/M2Git/issues/new`，反馈落在自己的仓库。**不要把 URL 改回 upstream**，也不要关 fork 的 Issues：GitHub 对 fork 默认禁用 Issues，禁用时 `/issues/new` 是 404（实测禁用→404、启用→200），而 app 侧没有兜底，点「反馈」就是死页。同步 upstream 时留意这条。

## 签名

- debug 构建使用默认 debug 证书（`~/.android/debug.keystore`）+ **v3-only** 签名（app/build.gradle 中 `enableV1Signing false` / `enableV2Signing false` / `enableV3Signing true`）。minSdk 31 无需 v1/v2，v3-only 是业界常规做法。
- 覆盖安装报签名不一致 = 设备上的包不是当前 debug.keystore 签的（根目录 May 之前的旧 debug APK 用的是另一把钥匙，勿用来覆盖安装）。解法：`adb uninstall ts.realms.m2git.debug` 后重装（会清 debug 应用数据）。
- release 签名**一律用环境变量注入**：**keystore 路径与密码不硬编码**（`KEY_STORE` / `KEY_STORE_PASSWORD` 缺失时 `build.sh` 直接报错退出，不做兜底）。脚本里剩下的 `:-` 默认值都不是密钥（`KEY_ALIAS` 缺省 `pisces312` 等，见下）。
  - `KEY_STORE` = keystore 路径。本机取值为 `D:\my-projects\my-backup\backup-settings\my-android-release.keystore` —— 此处仅作运维说明，**代码/脚本里不得出现该字面量**。
  - `KEY_STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，`KEY_PASSWORD` 缺省回退 `KEY_STORE_PASSWORD`
  - SDK 路径取 `ANDROID_HOME`（缺省 `D:\dev\android_sdk`）；构建前确认四项已设置。
  - release 产物：gradle 产 unsigned 包 → build.sh zipalign + apksigner 签名，成品在仓库根目录 `M2Git-v*-<abi>-signed.apk`。
- **本仓库 release 证书 SHA-256**：`abadebd2fc9523628b5dacfa0fb40f652df0e161b71820c7a4b5a40653ee0b90`
  —— 就是上面 `KEY_STORE` 指向的那把钥匙，自 v1.8.5 起统一（v1.8.5 / 1.8.6 / 1.8.7 / 1.8.8 / 1.8.9 指纹一致，可互相覆盖升级；
  v1.8.9 覆盖安装 v1.8.8 已在真机实测通过，2026-10-11）。
  溯源方法（可复现）：`keytool -list -v -keystore "$KEY_STORE" -storepass "$KEY_STORE_PASSWORD"` 与
  `apksigner verify --print-certs <apk>` 两处指纹应逐字符相同。
- **release 与 debug 的签名方案不同，别当成一回事**（2026-10-11 实测）：
  - debug = **v3-only**（由 app/build.gradle 的三个 `enableV*Signing` 开关决定，见本节第一条）。判据：
    `apksigner verify -v --min-sdk-version 23 <debug.apk>` 直接报 `Missing META-INF/MANIFEST.MF`，即真没写 v1。
  - release = **v1 + v2 + v3 全都写了**。`build.sh` 调 apksigner 时不传 `--vN-signing-enabled`，走其默认（三者全开），
    成品 zip 里有 `META-INF/PISCES31.SF` / `META-INF/PISCES31.RSA`。v1.8.8 与 v1.8.9 两个发布包实测都是这个结果。
    要真做成 v3-only 得在 build.sh 的 sign 命令上显式加 `--v1-signing-enabled false --v2-signing-enabled false`（目前未做，
    minSdk 31 下多写 v1/v2 无安全收益，只是与 debug 不一致）。
  - **判读坑**：`apksigner verify -v <apk>` 不带 `--min-sdk-version` 时是按 APK 自身 minSdk 判「需不需要该方案」，
    minSdk ≥ 24 时 v1/v2 恒显示 `false`，**不代表包里没写**。看真实状态要么 `verify -v --min-sdk-version 23 <apk>`，
    要么直接查 zip 里的 `META-INF/*.SF`、`*.RSA`。
- **上游 Zacharia2/MGit 用的是另一把钥匙**：`50d2034f42e06088d21623e7e2335d8de797f94e38ad5eb2b727d45cc6a40547`
  → 装过上游版的设备**必须先卸载**才能装本仓库包（签名不匹配无法覆盖），release notes 里必须写明。
  上游的 release 附件叫 `app-release.apk`（含着 4 个 ABI），本仓库只出 `arm64-v8a`。

## 仓库列表：用标签，没有分组

- **「组」概念已完全废弃**（2026-10-09 决议，见 `docs/repo-tags-design.md`）：仓库列表平铺展示，
  条目底部带标签行，用标签 + 筛选代替分组。**不要再引入分组/组头（group header）相关代码或布局**。
- 曾有实验分支 `feature/repo-list-sort`（分组交互 + 组头样式）基于旧方案，已废弃删除，勿捡回。

## 搜索交互：不要改造 framework SearchView（2026-10-18 决议）

- **不要**在工具栏 framework `SearchView` 上做「展开即输入 / 去掉第二个放大镜」这类改造。
  现状（`RepoListActivity.configSearchAction()` + `menu/main.xml`，`iconifiedByDefault = true`）
  点一次后还要再点输入框才能打字，**是已知取舍，不是 bug**。
- **正确做法**：要一步直达输入就换成页面自建搜索条（`EditText` + 显隐），照抄仓库详情页 `searchBar`
  的做法（commit 86fe19b）；**动手前先跟用户确认**，不要先写代码。
- **完整原因、框架源码证据（行号）、替代方案与实施要点**见 `docs/searchview-refactor-investigation.md`。
- Android 13+ 重写 `onBackPressed()` 无效，拦返回键用 `getOnBackPressedDispatcher().addCallback(...)`。

## 仓库详情页工具栏（2026-10-20 决议）

- **常驻图标只有「排序」（仅文件页）和「开关抽屉」**；「搜索」在**溢出菜单第一项**（`orderInCategory=1`），
  「编辑标签」在其后。**不要再把搜索改回 `showAsAction="always"`**：411dp 宽的工具栏上每个图标约 48dp，
  标题会重新被挤掉。
- **ActionBar 不显示返回箭头**（`setupActionBar()` 里 `setDisplayHomeAsUpEnabled(false)`）：
  返回统一走系统返回 → `RepoDetailActivity.onKeyUp`（搜索条先收 → 文件页子目录先上跳 → `finish()`）。
  `onOptionsItemSelected` 里**不要**再加 `android.R.id.home` 分支——旧分支直接 `finish()`，与系统返回语义冲突。
- 宽度基线（1080×2400 @420dpi 实测）：去掉箭头 + 搜索进溢出后标题可用宽约 296dp（原约 191dp），
  标题 20sp 约 8.7dp/字符。仓库名过长仍会 `ellipsize`，要"永不截断"需换自适应字号的自定义标题视图。

## 资源与多语言

- 用户可见文案必须同时更新 `values/` 与 `values-zh-rCN/`（arrays.xml、strings.xml）。
- `repo_file_operations` 等菜单数组按**位置索引**对应代码 switch，两种语言的新增项必须在数组末尾按相同顺序追加。
- `*_values` 结尾的数组（如 `markdown_open_mode_values`）是存储键值，**不翻译**，只保留在 `values/`。
