# M2Git 桌面快捷方式功能设计文档

## 概述

为 M2Git 仓库列表中的每个仓库添加「创建桌面快捷方式」功能，用户长按仓库后可通过菜单在桌面创建快捷图标，点击后直接进入该仓库的详情页（跳过仓库列表）。

## 技术方案

### API 选择

使用 Android `ShortcutManager` API（Pinned Shortcut），要求 API 25+，M2Git minSdk 31，满足要求。

### 数据传递

| 方案 | 选择 | 原因 |
|------|------|------|
| Intent 序列化 Repo 对象 | ❌ | Repo 可能有大量文件数据，序列化后 Intent payload 过大 |
| 传递 repo_id (int) | ✅ | 轻量，从数据库加载保证数据最新 |

### 快捷方式创建流程

1. 用户长按仓库 → 弹出选项菜单
2. 点击「Create shortcut」
3. 调用 `ShortcutManager.requestPinShortcut()` 创建 pinned shortcut
4. Launcher 确认后桌面显示快捷图标
5. 用户点击图标 → Intent 携带 `repo_id` → `RepoDetailActivity` 从数据库加载仓库

## 代码改动

### 1. RepoListAdapter.java

**改动点：**
- 新增 import：`ShortcutManager`、`Icon`、`Locale`
- `showRepoOptionsDialog()` 菜单数组从 3 项扩展为 4 项：
  - `[0]` Rename
  - `[1]` Delete
  - `[2]` Create shortcut（新增）
  - `[3]` Open remote（原 `[2]`，仅在 repo 有 HTTP remote 时显示）
- 新增 `createShortcut()` 方法：
  - 构建 `ShortcutInfo.Builder`，设置短名称（仓库名）和长名称（仓库路径）
  - Intent 设置 `ACTION_VIEW` + `RepoDetailActivity.class` + `repo_id` extra
  - 调用 `ShortcutManager.requestPinShortcut()`

### 2. RepoDetailActivity.java

**改动点：**
- `onCreate()` 开头增加对 `repo_id` extra 的处理：
  - 检查 `getIntent().getIntExtra("repo_id", -1)`
  - 若有效，直接通过 `mRepoDataSource.findRepo(id)` 从数据库加载仓库
  - 跳过正常的 Intent 反序列化流程
  - 避免声明同名 `Intent intent` 变量（与 lambda 内变量冲突），改为直接调用 `getIntent()` 链式处理

### 3. strings.xml

- 新增字符串资源：`dialog_create_shortcut`

### 4. arrays.xml

- `repo_options` 数组新增选项："Create shortcut"

## 已知限制

- 部分国产 ROM Launcher 可能不支持 pin shortcut（如 MIUI、ColorOS 的旧版本）
- 图标复用 app icon，未做仓库独立图标
- 删除仓库时未自动移除对应 shortcut（需后续补充）

## 构建

- AGP 8.13.2，Gradle 8.13.2-bin
- `assembleRelease` → unsigned APK → `zipalign` → `apksigner` 签名
- 签名文件：`D:\nili\my-git-projects\my-backup\backup-settings\my-android-release.keystore`
- 签名 APK：`M2Git-v1.8.4-shortcut-signed.apk`（35.8MB）
