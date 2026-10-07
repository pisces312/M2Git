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

## Git 远程

- `upstream` = Zacharia2/M2Git（原仓库，同步用：`git fetch upstream` 后比对 `main..upstream/main`）
- `github` = pisces312/M2Git（fork 主远程）
- `origin` = 阿里云 codeup（备份）

## 签名

- debug 构建使用默认 debug 证书（`~/.android/debug.keystore`）+ **v3-only** 签名（app/build.gradle 中 `enableV1Signing false` / `enableV2Signing false` / `enableV3Signing true`）。minSdk 31 无需 v1/v2，v3-only 是业界常规做法。
- 覆盖安装报签名不一致 = 设备上的包不是当前 debug.keystore 签的（根目录 May 之前的旧 debug APK 用的是另一把钥匙，勿用来覆盖安装）。解法：`adb uninstall ts.realms.m2git.debug` 后重装（会清 debug 应用数据）。
- release 签名经环境变量注入，见全局约定。

## 资源与多语言

- 用户可见文案必须同时更新 `values/` 与 `values-zh-rCN/`（arrays.xml、strings.xml）。
- `repo_file_operations` 等菜单数组按**位置索引**对应代码 switch，两种语言的新增项必须在数组末尾按相同顺序追加。
- `*_values` 结尾的数组（如 `markdown_open_mode_values`）是存储键值，**不翻译**，只保留在 `values/`。
