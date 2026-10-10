# M2Git 项目约定

## 构建与 ABI

- **默认仅构建 arm64-v8a**，不构建 x86_64：模拟器（x86_64 镜像带 ARM 转译）可直接安装运行 arm 版 APK，无需单独的 x86_64 包。
- 需要其他 ABI 时手动指定：`./gradlew assembleDebug -PbuildAbi=x86_64`。
- 一键脚本：`./build.sh [debug|release] [arm64|x86_64]`（默认 release arm64；release 需签名环境变量，见全局约定）。
- CI（`.github/workflows/build.yml`）跑裸 `assembleDebug`，跟随 app/build.gradle 的默认 ABI 列表。

## 构建栈（2026-10 升级后）

- AGP 9.4.0 + Gradle 9.6.1，均用本地缓存（`GRADLE_USER_HOME=D:\dev\.gradle`）。
- AGP 9 内置 Kotlin：**不要再加** `org.jetbrains.kotlin.android` 插件或 `kotlinOptions{}`；Kotlin 编译选项跟随 `compileOptions`（Java 11）。
- `android.nonFinalResIds` 已移除，R 字段为非 final：Java 代码**不要写** `case R.xxx` / `switch(getId())`。

## 依赖升级与构建溯源（2026-10-07）

- **jgit 主包是「官方 jar 剥离版」**：`app/libs/org.eclipse.jgit-*-stripped.jar` 由 `app/libs/make-stripped-jgit-jar.sh <版本号>` 生成（下载官方包后仅剥离 `InflaterCache.class`）；`InflaterCompat`/`InflaterCache` 补丁类在 `app/src/main/java/org/eclipse/jgit/{compatible,lib}/`。升级 = 改 build.gradle 顶部 `jgitVersion` + 重跑脚本。**禁止直接换 Maven 官方包**：Android libcore 对已 `end()` 的 Inflater 复用时抛 "inflater has been closed"（上游 issue #33），补丁类就是防它；历史教训：上游手工补丁 jar 与官方包哈希不同，曾差点被当成可随意替换的同名文件。
- jgit 7.x 是 Java 17 字节码；minSdk 31 核心库 = OpenJDK 11，真机若遇 `NoSuchMethodError`（如 `Stream.toList`）→ 开 `coreLibraryDesugaring`。
- jsch 版本只在 build.gradle 顶部 `jschVersion` 一处定义，同时喂给 `implementation`、`resolutionStrategy` 重映射（JGit 的 com.jcraft 传递依赖靠它换成 mwiede fork）和 BuildConfig。
- 查库最新版本看 `repo1.maven.org/<group>/<artifact>/maven-metadata.xml`；search.maven.org 索引滞后（曾把 7.8.0 显示成 7.3.0）。
- 构建溯源（`GIT_COMMIT`/`GIT_COMMIT_TIME`/`GIT_DIRTY`/库版本 → BuildConfig → 设置页「关于」）：在 build.gradle **配置期**经 `providers.exec` 读 git（configuration-cache 把 exec 当输入跟踪，commit 变化自动失效重建，参考 local-dream）。**不要**把 `providers.exec` 放进任务 doLast——执行期引用脚本对象会被 CC 拒绝；AGP 9 也不接受 Provider 形式的 `sourceSets.srcDir`。
- adb 无线设备序列号含空格/括号（mDNS `_adb-tls-connect._tcp`），`adb -s` 必须加引号；设备随手机休眠从列表消失，安装前先 `adb devices` 确认。模拟器 pixel6 用后台长任务启动。

## 模拟器上操作 MGit app

- 已有项目级 skill：`.zcode/skills/mgit-emulator/SKILL.md`（模拟器装包、权限重授、仓库列表导航、uiautomator 定位、截图验证、debug 数据库直查，以及 MSYS 路径改写 / 拼音 IME 两个坑）。凡是「模拟器 + 操作/测试 mgit app」的任务，先读该 skill 再动手。

## Git 远程

- `upstream` = Zacharia2/M2Git（原仓库，同步用：`git fetch upstream` 后比对 `main..upstream/main`）
- `github` = pisces312/M2Git（fork 主远程）
- `origin` = 阿里云 codeup（备份）

## 签名

- debug 构建使用默认 debug 证书（`~/.android/debug.keystore`）+ **v3-only** 签名（app/build.gradle 中 `enableV1Signing false` / `enableV2Signing false` / `enableV3Signing true`）。minSdk 31 无需 v1/v2，v3-only 是业界常规做法。
- 覆盖安装报签名不一致 = 设备上的包不是当前 debug.keystore 签的（根目录 May 之前的旧 debug APK 用的是另一把钥匙，勿用来覆盖安装）。解法：`adb uninstall ts.realms.m2git.debug` 后重装（会清 debug 应用数据）。
- release 签名**一律用环境变量注入**：**代码与脚本不硬编码凭据**（keystore 路径/密码/别名全从环境读取，`build.sh` 只读环境变量、不做任何 fallback）。
  - `KEY_STORE` = keystore 路径。本机取值为 `D:\my-projects\my-backup\backup-settings\my-android-release.keystore` —— 此处仅作运维说明，**代码/脚本里不得出现该字面量**。
  - `KEY_STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，`KEY_PASSWORD` 缺省回退 `KEY_STORE_PASSWORD`
  - SDK 路径取 `ANDROID_HOME`（缺省 `D:\dev\android_sdk`）；构建前确认四项已设置。
  - release 产物：gradle 产 unsigned 包 → build.sh zipalign + apksigner 签名，成品在仓库根目录 `M2Git-v*-<abi>-signed.apk`。
- **本仓库 release 证书 SHA-256**：`abadebd2fc9523628b5dacfa0fb40f652df0e161b71820c7a4b5a40653ee0b90`
  —— 就是上面 `KEY_STORE` 指向的那把钥匙，自 v1.8.5 起统一（v1.8.5 / 1.8.6 / 1.8.7 / 1.8.8 指纹一致，可互相覆盖升级）。
  结果恒为 **v3-only**（v1/v2 均 false，minSdk 31 无需 v1/v2），release 与 debug 一致。
  溯源方法（可复现）：`keytool -list -v -keystore "$KEY_STORE" -storepass "$KEY_STORE_PASSWORD"` 与
  `apksigner verify --print-certs <apk>` 两处指纹应逐字符相同。
- **上游 Zacharia2/MGit 用的是另一把钥匙**：`50d2034f42e06088d21623e7e2335d8de797f94e38ad5eb2b727d45cc6a40547`
  → 装过上游版的设备**必须先卸载**才能装本仓库包（签名不匹配无法覆盖），release notes 里必须写明。
  上游的 release 附件叫 `app-release.apk`（含着 4 个 ABI），本仓库只出 `arm64-v8a`。

## 资源与多语言

- 用户可见文案必须同时更新 `values/` 与 `values-zh-rCN/`（arrays.xml、strings.xml）。
- `repo_file_operations` 等菜单数组按**位置索引**对应代码 switch，两种语言的新增项必须在数组末尾按相同顺序追加。
- `*_values` 结尾的数组（如 `markdown_open_mode_values`）是存储键值，**不翻译**，只保留在 `values/`。
