# MGit vs M2Git 对比分析

> 分析日期：2026-04-26
> 最终选择：**M2Git**（MGit 生态中唯一活跃的 fork）

## 项目基本信息

| 项目 | MGit | M2Git |
|------|------|-------|
| GitHub | maks/MGit | Zacharia2/M2Git |
| Stars | 较高（原项目） | 29 |
| 最后更新 | 2024-09-24（停止维护） | 2026-02-26 |
| Fork 自 | - | maks/MGit |
| 版本 | v1.7.0 (versionCode 240) | v1.8.4 (versionCode 278) |
| compileSdk | 31 | 34 |
| minSdk | 21 | 31 |
| JGit | 3.7.1 (2015) | **6.10.1 (2025)** |
| JSch | 0.2.0 | 0.2.11 (mwiede fork) |
| AGP | 8.4.2 | 8.13.2 |
| Gradle | - | 8.13 |
| 语言 | Kotlin + Java | Java 全面重写 |

## 核心改动

| 类别 | 改动 | 评价 |
|------|------|------|
| 语言迁移 | Kotlin → Java 全面重写 | 工程量大，删除 DataBinding |
| JGit 升级 | 3.7.1 → **6.10.1**（2025年） | ✅ 解决最大痛点 |
| LFS 支持 | JGit-LFS 内置 | ✅ |
| WebDAV 服务端 | Milton Server，新增 WebDAV Level 1 | 手机变 Git 服务器 |
| 仓库同步 | SyncRepoAction 双向同步 | ✅ |
| 包名重构 | me.sheimi.sgit → ts.realms.m2git | 破坏性升级 |
| 多账号凭证 | CredentialActivity 多账号管理 | ✅ |
| 撤销 commit | UndoAction / UndoCommitTask | ✅ |
| WebDAV 安全 | TLS + 权限增强 | |
| Gradle/AGP | 适配新版构建工具 | |
| 国际化 | 保留中文，删除所有其他语言 | |

## 功能覆盖对比

| 功能 | MGit | M2Git |
|------|------|-------|
| add/commit/push/pull/fetch/diff | ✅ | ✅ |
| 分支管理（new/delete/merge/rebase） | ✅ | ✅ |
| 远程管理（add/remove/config） | ✅ | ✅ |
| 文件管理（new file/dir/delete） | ✅ | ✅ |
| reset / cherry-pick | ✅ | ✅ |
| SyncRepoAction（双向同步） | ✅ | ✅ |
| UndoAction（撤销 commit） | ✅ | ✅ |
| SSH/TOKEN 凭证 | ✅ | ✅ |
| LFS 支持 | ❌ | ✅ 新增 |
| WebDAV 服务端 | ❌ | ✅ 新增 |
| 多账号凭证管理 | ❌ | ✅ 新增 |
| JGit 版本 | 3.7.1 (2015) | **6.10.1 (2025)** |
| 国际化 | 多语言 | 仅中英文 |

**结论：M2Git = MGit 超集，功能上无缺失。**

## 安全性对比

### MGit 安全亮点
- SSH 私钥密码用 Android KeyStore (RSA) + SecurePreferences (AES) 加密存储
- 私钥存 app 内部私有目录
- TLS 强制 1.2/1.3 (MGitSSLSocketFactory)
- 引入 Conscrypt 安全加密库
- 无第三方 SDK/追踪/广告

### M2Git 安全亮点
- JGit 版本最新（6.10.1）
- JSch 用 mwiede fork（维护活跃）
- TLS 强制 1.2/1.3 + Conscrypt
- SecurePreferences 存储密钥
- 无广告/追踪 SDK

### 共同安全风险

| 风险 | 级别 | 说明 |
|------|------|------|
| SSH StrictHostKeyChecking=no | 🔴 | 硬编码关闭服务器身份验证，易受 MITM |
| FileProvider 暴露根目录 | 🟡 | path="/" 权限过宽 |
| allowBackup=true | 🟡 | adb backup 可导出数据 |

### M2Git 额外风险（修复前）

| 风险 | 文件 | 说明 |
|------|------|------|
| Timber 日志泄露 token 明文 | Repo.java:571 | "Set token: " + tokenAccount |
| Timber 日志泄露 secretKey | PreferenceHelper.java | set tokenSecretKey:%s |
| Timber 日志泄露用户名 | PreferenceHelper.java | setWebdavUser:%s |
| Timber 日志泄露密码 | SecurePrefsHelper.java | pref password %s |
| WebDAV 暴露整个存储 | WebDavService | 前台服务局域网内任意文件读写 |
| ACRA 崩溃上报 | build.gradle | 可能包含敏感上下文 |

### 已修复项 ✅
- 5 处 Timber 日志泄露已删除
- ACRA 依赖和初始化代码已移除
- MainApplication.kt: DebugTree 改为 BuildConfig.DEBUG 条件加载

## 结论

- **MGit**：已停止维护近 2 年，JGit 版本过旧（2015年），不建议继续使用
- **M2Git**：唯一活跃的 fork，解决了 JGit 过旧和多账号凭证两个核心痛点，功能完全覆盖 MGit
- 安全性方面 M2Git 略差于 MGit（WebDAV 新攻击面），但已修复日志泄露和 ACRA 问题后整体可接受
