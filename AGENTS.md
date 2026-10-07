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

## Git 远程

- `upstream` = Zacharia2/M2Git（原仓库，同步用：`git fetch upstream` 后比对 `main..upstream/main`）
- `github` = pisces312/M2Git（fork 主远程）
- `origin` = 阿里云 codeup（备份）

## 签名

- debug 构建使用默认 debug 证书（`~/.android/debug.keystore`）+ **v3-only** 签名（app/build.gradle 中 `enableV1Signing false` / `enableV2Signing false` / `enableV3Signing true`）。minSdk 31 无需 v1/v2，v3-only 是业界常规做法。
- 覆盖安装报签名不一致 = 设备上的包不是当前 debug.keystore 签的（根目录 May 之前的旧 debug APK 用的是另一把钥匙，勿用来覆盖安装）。解法：`adb uninstall ts.realms.m2git.debug` 后重装（会清 debug 应用数据）。
- release 签名**一律用环境变量注入，零硬编码**（脚本与命令行同理，不写 keystore 路径/密码/别名明文）：
  - `KEY_STORE` = keystore 路径（`D:\my-projects\my-backup\backup-settings\my-android-release.keystore`）
  - `KEY_STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，`KEY_PASSWORD` 缺省回退 `KEY_STORE_PASSWORD`
  - `build.sh` 只读上述环境变量（SDK 路径取 `ANDROID_HOME`，缺省 `D:\dev\android_sdk`）；构建前确认四项已设置。
  - release 产物：gradle 产 unsigned 包 → build.sh zipalign + apksigner 签名，成品在仓库根目录 `M2Git-v*-<abi>-signed.apk`。

## 资源与多语言

- 用户可见文案必须同时更新 `values/` 与 `values-zh-rCN/`（arrays.xml、strings.xml）。
- `repo_file_operations` 等菜单数组按**位置索引**对应代码 switch，两种语言的新增项必须在数组末尾按相同顺序追加。
- `*_values` 结尾的数组（如 `markdown_open_mode_values`）是存储键值，**不翻译**，只保留在 `values/`。
