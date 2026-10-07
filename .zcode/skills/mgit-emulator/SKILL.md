---
name: mgit-emulator
description: 在 Android 模拟器（pixel6）上安装、操作、自动化测试 MGit/M2Git 应用时使用。涵盖装包、权限重授、仓库列表导航、UI 元素定位（uiautomator）、界面截图验证、debug 数据库查看，以及 Git Bash 路径改写与中文拼音 IME 两个必踩的坑。触发词：模拟器 / pixel6 / emulator + mgit、UI 冒烟、复现 app 问题、adb 操作 app。
---

# 在模拟器上使用 MGit

目标设备：AVD `pixel6`（x86_64 镜像带 ARM 转译，arm64 单 ABI 包直接可装）。工具路径：`D:/dev/android_sdk/platform-tools/adb.exe`、`D:/dev/android_sdk/emulator/emulator.exe`。

## 0. 启动与连接

- 模拟器要用**后台长任务**承载：`D:/dev/android_sdk/emulator/emulator.exe -avd pixel6 -no-boot-anim -no-snapshot-save`（run_in_background）。
- 等开机：`adb -s emulator-5554 shell getprop sys.boot_completed` 返回 `1`。
- 真机与模拟器可能同时在线，命令**始终带 `-s` 指定序列号**。

## 1. 两个应用变体

| | debug | release |
|---|---|---|
| 包名 | `ts.realms.m2git.debug` | `ts.realms.m2git` |
| APK | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | 仓库根 `M2Git-v*-arm64-signed.apk`（`./build.sh release arm64` 产物，需签名环境变量） |
| 日志 | Timber 已植入，logcat 可看 | **无日志**，异常只弹 "Error occurred"，堆栈不可见 |
| 调试 | `run-as` 可用（读 shared_prefs / databases） | 不可 |

- `adb install -r` 重装**保留数据但清空运行时权限**，装完必须重授：

```bash
adb -s emulator-5554 shell pm grant ts.realms.m2git android.permission.READ_EXTERNAL_STORAGE
adb -s emulator-5554 shell pm grant ts.realms.m2git android.permission.WRITE_EXTERNAL_STORAGE
adb -s emulator-5554 shell appops set ts.realms.m2git MANAGE_EXTERNAL_STORAGE allow
# debug 包名加 .debug 后同样执行一遍
```

## 2. 启动与页面地图

- 仓库列表：`am start -n <包名>/ts.realms.m2git.ui.screens.main.RepoListActivity`
- 溢出菜单（右上角 ≈ tap 1020,190）：Sort / Groups / Clone / Import / Settings
- Clone 对话框字段：Remote URL、Local Path、**Init Local** 勾选（纯本地建仓，无需网络）、Shallow depth、Clone recursively、CANCEL/CLONE
- 仓库详情：顶部「文件/提交/状态」PagerTitleStrip + ViewPager；底部 commitName 条
- **仓库列表按分组折叠**：分组 chip（如「3rd(2)」）需先点开（≈ tap 100,320）才能看到仓库行

## 3. UI 自动化标准流程（dump → 算坐标 → tap）

```bash
cd /tmp
adb -s emulator-5554 shell input tap X Y                       # 操作
adb -s emulator-5554 exec-out screencap -p > shot.png          # 截图（Read 工具可直接看）
MSYS_NO_PATHCONV=1 adb -s emulator-5554 shell uiautomator dump /sdcard/wd.xml
MSYS_NO_PATHCONV=1 adb -s emulator-5554 pull /sdcard/wd.xml wd.xml
sed 's/></>\n</g' wd.xml | grep -oE 'text="[^"]+"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"'
```

- 用 dump 里的 bounds 算中心点再 tap，**不要背坐标**（布局会变）。
- 被动检查视图（不触发任何 UI 动作）：`adb shell dumpsys activity top | grep <关键字>`，能看到完整视图树和每个 view 的尺寸/位置（如排查「某元素消失」类 bug 首选）。
- 系统原生 UI（弹窗、输入法）的 resource-id 是 `android:id/...`，app 内是 `ts.realms.m2git[.debug]:id/...`。

## 4. 两个必踩的坑

1. **Git Bash 路径改写**：adb 参数里的 `/sdcard/...` 会被 MSYS 改写成 `D:/dev/git/sdcard/...`，导致命令静默失败或作用到错误路径。凡是以 `/` 开头的设备路径参数，**必须加 `MSYS_NO_PATHCONV=1` 前缀**。
2. **中文拼音输入法**：模拟器默认 Gboard 拼音模式，`input text` 输入的 ASCII 会被 IME 拼音组合劫持转成中文（实测：输入 `/sdcard/xxx` 变成「／打他／...」）。**不要往输入框打路径/URL**；改用免输入方案（Init Local 勾选 + 短名、或预先放好文件、或直接改数据库，见 §5）。

## 5. 数据层（debug 包可直查）

- 仓库注册在 SQLite：`databases/repo.db`，表 `repo`（local_path/remote_url/group_id/...，local_path 形如 `external:///storage/emulated/0/Download/xxx`）、`repo_group`、`credentials`。
- 查看方式：`MSYS_NO_PATHCONV=1 adb -s emulator-5554 shell run-as ts.realms.m2git.debug cat databases/repo.db > repo.db`，然后 python sqlite3 解析。debug 应用可经 run-as 读写（改完记得还原属主或直接 force-stop 后改）。
- 模拟器上已注册仓库的**文件可能不存在**（数据是残留的），打开报 "Error occurred" 属预期，不代表新构建有问题；判定 UI 问题以视图树为准。

## 6. 典型任务速查

- **装新构建并冒烟**：install -r → 重授权限（§1）→ 冷启动（`am force-stop` 后 am start）→ screencap 看首屏。
- **验证某视图存在/尺寸**（如「标签条消失」类 bug）：`dumpsys activity top | grep pager_title_strip`，看 bounds 是 `0,0-0,0` 还是正常尺寸。
- **复现需要仓库数据的场景**：优先 debug 包（可 run-as 改 DB 注册路径），或走 Clone 对话框 + Init Local。
