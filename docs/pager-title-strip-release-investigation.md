# Release 版仓库详情页顶部标签条（文件/提交/状态）整行消失 —— 排查记录

> 状态：**已修复**（2026-10-07 晚）。真根因 = **R8（AGP 9 full mode）剥离 `@ViewPager$DecorView` 运行时注解**，与资源收缩器**无关**；早期「资源收缩器删样式」模型是错的（见 §10 纠错）。
> 修复 = `RepoDetailActivity.setupViewPager()` 与 `ViewFileActivity.onCreate()` 显式置 `ViewPager.LayoutParams.isDecor = true`（2 行/处），模拟器已验证 strip 恢复 `0,0-1080,80`。
> 本文供后续会话继续分析，所有命令均可直接复用。

## 1. 现象

- 真机（release 包）：仓库详情页顶部原有的「文件 / 提交 / 状态」标签条**整行消失**；左右滑动切换 ViewPager 页面正常、各页内容正常。
- 模拟器（pixel6，release 包）复现一致；**debug 包同屏正常**。
- 该问题与设置页版本信息功能无关（版本信息只加 BuildConfig 字段），与 jsch/JGit 升级无关（见 §6 推理）。

## 2. 已确认的事实（实测）

| # | 实验 | 结果 |
|---|---|---|
| 1 | `dumpsys activity top` 看视图树 | release：`PagerTitleStrip{... 0,0-0,0}`（**存在但被量成 0×0**，内部 3 个 TextView 尺寸 0、x 偏移 -42/0/+42）；debug：`0,0-1080,80` 正常 |
| 2 | 同一模拟器、同一代码，debug vs release | debug 正常，release 0×0 → 差异在 release 构建管线 |
| 3 | release 构建 `shrinkResources false`（其余不动） | **strip 恢复 1080×80，问题消失** → 根因 = 资源收缩器（**此读数存疑**：shrink-off 包 dex 同样无 DecorView 注解，按 §10 机制应仍坏；疑似踩了 §4 的多 activity 取块陷阱，见 §10.7） |
| 4 | release APK 资源表对比（`aapt2 dump resources`） | 收缩后 2770 项 vs 不收缩 5932 项；被删 875 个 style，含 `TextAppearance.AppCompat.Small`、`Base.TextAppearance.AppCompat.Small` 等 |
| 5 | `res/raw/keep.xml` + `tools:keep` 钉住上述样式 | **无效**：keep.xml 进入了 merged 资源目录，但样式仍被删（AGP 9.4 收缩器无视/未追踪，疑似 regression） |
| 6 | 布局显式 `android:textAppearance="@style/TextAppearance.AppCompat.Small"` | 样式确实保留进 APK（aapt2 可见），但模拟器实测 strip 仍 0×0（该轮测试受错误弹窗干扰，见 §5 注意事项；真机确认仍复现） |

**根因模型（旧，已证伪）**：~~PagerTitleStrip 内部程序化 `new TextView(context)` 创建 3 个标签 TextView，其默认文字样式经主题的框架属性间接解析，资源收缩器不追踪而删样式 → textSize 0 → 0×0。~~ 见 §10：textSize 从未为 0（坏包中子 TextView 高 58px = 14sp 行高），资源收缩器无辜。

**真根因（实测证据链，见 §10）**：viewpager 1.0.0 的 `ViewPager.isDecorView()` 靠 `getClass().getAnnotation(ViewPager$DecorView.class)` **运行时注解反射**识别 decor；`PagerTitleStrip` 上的 `@ViewPager$DecorView` 是 RuntimeVisibleAnnotation。AGP 9 的 R8（full mode）在无有效 keep 时剥离该注解**及注解类**（debug/D8 保留、minify 即剥离，与 shrinkResources 开关无关）→ `isDecor=false` → ViewPager 按非 decor 路径处理：onMeasure 用 `EXACTLY(childWidthSize * widthFactor=0)` 量 strip（子 TextView 宽 0、高自然 58px），onLayout 的非 decor 分支要求 ItemInfo、strip 没有 → **永不 layout** → `0,0-0,0`；子 TextView 的 -42/0/+42、-69/-11 偏移来自 `updateTextPositions()`（翻页回调直接 layout 三个 TextView，与 strip 自身是否 layout 无关），与观测完全吻合。

## 3. 受影响 / 不受影响

- 受影响：仅 release 且 `minifyEnabled true`（R8 剥注解）；与 `shrinkResources` 开关无关（shrink-off 包 dex 同样无注解）。debug 不 minify，永远正常。
- 程序化创建 TextView 的只有 PagerTitleStrip（全仓 grep 过 `new TextView(`），它出现在两个布局：
  - `app/src/main/res/layout/activity_repo_detail_content.xml`（仓库详情页）
  - `app/src/main/res/layout/activity_view_file.xml`（文件查看页）

## 4. 快速复现（模拟器）

环境：pixel6 AVD 已装 release（`ts.realms.m2git`）与 debug（`ts.realms.m2git.debug`，仓库列表有分组「3rd(2)」需点开）。

```bash
# 每次重装 release 后权限会被清掉，必须重新授（否则打开仓库弹 "Error occurred"）
adb -s emulator-5554 shell pm grant ts.realms.m2git android.permission.READ_EXTERNAL_STORAGE
adb -s emulator-5554 shell pm grant ts.realms.m2git android.permission.WRITE_EXTERNAL_STORAGE
adb -s emulator-5554 shell appops set ts.realms.m2git MANAGE_EXTERNAL_STORAGE allow

adb -s emulator-5554 shell am start -n ts.realms.m2git/ts.realms.m2git.ui.screens.main.RepoListActivity
adb -s emulator-5554 shell input tap 540 413   # 点第一个仓库行
# 判定（关键命令）：0,0-0,0 = 复现；0,0-1080,80 = 正常
# 注意：dumpsys activity top 会同时 dump 多个 resumed activity（debug 包后台残留的
# RepoDetail 也会出块且 strip 正常），裸 grep 会先命中 debug 块造成"已修复"假象！
# 必须按包名精确取块：
adb -s emulator-5554 shell dumpsys activity top > /tmp/top.txt
awk '/ACTIVITY ts.realms.m2git\//,0' /tmp/top.txt | grep -E "PagerTitleStrip|widget.TextView\{"
```

- Git Bash 下 adb 的 `/sdcard/...` 参数会被 MSYS 改写成 `D:/dev/git/sdcard/...`，**必须加 `MSYS_NO_PATHCONV=1`**。
- release 包无 Timber 日志（`MainApplication` 只在 DEBUG 植入），异常被通用错误弹窗吞掉，logcat 看不到堆栈；调试时可临时 `Timber.plant()` 无条件生效。

## 5. 干扰因素（重要）

- 模拟器上已注册的仓库（MusicFree，路径 `/storage/emulated/0/Download/...`）**文件可能并不存在**，打开必弹 "Error occurred" 弹窗；debug 包同弹但 strip 仍正常渲染（弹窗不影响 strip 布局）。做 strip 判定以 dumpsys 为准，截图仅辅助。
- `adb install -r` 重装会保留数据但**清空运行时权限**。
- release 重签名/重装后务必冷启动（`am force-stop`）。

## 6. 与本次 jsch/JGit 升级无关的推理

- 触发条件是 R8（minify）剥离运行时注解；jsch 2.28.7 / JGit 7.8.0 不含任何 Android 资源与注解依赖。
- 真机上一次正常的 release 构建于 AGP 8 时代；AGP 9.4.0 升级（commit e2149b2）后这是第一次出 release 包——回归窗口是「AGP 8→9 构建管线」，不是依赖升级。
- R8 的 `Missing class com.sun.jna.*`（jsch 的 Windows Pageant 可选依赖）与 `com.googlecode.javaewah.*`（JGit 位图索引可选依赖）警告与本病无关（6.10.1 时代同样存在，Android 触达不到）。

## 7. 下一步建议（**已全部作废**，根因不在资源收缩器，见 §10；保留原文仅供回溯）

1. **临时兜底方案（可直接发布）**：`app/build.gradle` release 块 `shrinkResources false`。实测体积代价 ~0.5MB（12.7M vs 12.0M）。旧版一直开着收缩，代价可接受。
2. **二分定位**：release 构建 2×2 矩阵 `{minify on/off} × {shrink on/off}`，确认除 shrink 外 minify 是否也有影响（当前证据：shrink off + minify on = 正常，minify 基本可排除，但值得补一轮）。
3. **系统排查被删资源**：`aapt2 dump resources` 两个 APK（好/坏）逐类 diff，重点看 `style/Widget.AppCompat.TextView*`（`android:textViewStyle` 主题链）、`dimen/abc_text_size_*`、dark 主题 `Small.Inverse`。方法：坏包按 §4 复现 0×0 后，把怀疑资源逐个用 `tools:keep` 或布局/主题显式引用钉回，每轮装模拟器看 strip 尺寸。
   - 注意：单独钉 `TextAppearance.AppCompat.Small` 已证无效（实验 6），要么不止一个资源，要么是 §2 尚未解释的 (b) 类问题。
4. **测 arsc 优化**：`gradle.properties` 加 `android.enableResourceOptimizations=false`（收缩器保留资源但关掉 arsc 优化/路径混淆），排除"资源还在但运行时解析被混淆破坏"的可能。
5. **查 AGP 已知问题**：搜 "AGP 9 shrinkResources tools:keep ignored"、"resource shrinker android:textAppearanceSmall"（AGP 9.4.0 收缩器可能 regression，可上报）。
6. 若确认收缩器盲区无法绕过，长期方案：给 PagerTitleStrip 换成 Material `TabLayout`+ViewPager2（顺带现代化），或保留 strip 但 textSize/textColor 全部在布局里写死 px/sp（绕过主题样式解析）。

## 8. 当前工作区状态（未提交）

```
 M app/src/main/java/.../repoDetail/RepoDetailActivity.java   # setupViewPager() 显式 isDecor=true（修复）
 M app/src/main/java/.../fragments/ViewFileActivity.java      # onCreate() 同上（修复）
?? docs/pager-title-strip-release-investigation.md            # 本文
```

早期实验性改动（布局 `android:textAppearance`、`res/raw/keep.xml`、proguard `-keepattributes`/`-keep DecorView`、
`gradle.properties` 的 `android.enableResourceOptimizations=false`、`shrinkResources false`）**已全部回滚**：
它们基于错误模型或实测无效（keepattributes 两种写法都保不住注解实例）。

## 9. 相关背景

- 分支列表"看不到新分支"已澄清：真机上的仓库 remote 是 **codeup（origin）**，新分支只推了 github 时手机 pull 不到；推 codeup 后可见。另：app 的分支列表用 JGit `branchList()`（`Repo.java:509`），默认只列**本地**分支。
- 本轮升级主线（jsch 2.28.7 + JGit 7.8.0 方案 A）已完成并推送：`cd70043`（升级）、`f287e23`（版本溯源+AGENTS.md）。
- 版本溯源字段（BuildConfig `GIT_COMMIT/GIT_COMMIT_TIME/GIT_DIRTY/JGIT_VERSION/JSCH_VERSION`）经 configuration-cache 值源实现，见工作区 AGENTS.md「依赖升级与构建溯源」。

## 10. 真根因证据链与修复（2026-10-07 晚，实测）

1. **坏包视图树精确读数**（按包名取块，见 §4）：release strip `0,0-0,0`，三个子 TextView `宽 0、高 58px`、偏移 `-42/0/+42`、`-69/-11`。高 58px = 14sp 行高 → textSize 解析正常，推翻「textSize=0」旧模型；宽 0 + 永不 layout 指向 ViewPager 的**非 decor 路径**：onMeasure 对非 decor 子用 `EXACTLY(childWidthSize * lp.widthFactor)`（strip 的 widthFactor=0 → 宽 0），onLayout 非 decor 分支需 ItemInfo（strip 没有）→ 跳过 layout；子 TextView 的偏移由 `updateTextPositions()`（翻页回调直接 layout）产生，数字与 width=0/height=0 + SIDE_PADDING 16dp=42px + gravity BOTTOM 完全吻合。
2. **decor 判定机制**（viewpager 1.0.0 字节码 javap 确认）：`ViewPager.isDecorView()` = `getClass().getAnnotation(ViewPager$DecorView.class)`；`PagerTitleStrip` 以 **RuntimeVisibleAnnotation** 形式带 `@ViewPager$DecorView`（不是接口）。
3. **注解被 R8 剥离**：debug dex（D8，不 minify）含 `ViewPager$DecorView`；当前分支 release dex（minify on，**shrink 开或关都一样**）该注解类 0 次出现 → `isDecor` 恒 false。main 分支 proguard 规则与本分支逐字相同、buildTypes 同为 minify+shrink，故规则不是变量；变量是 AGP/R8 版本行为（"main 正常"的包是 AGP9 升级提交 e2149b2 之前的旧产物，该提交已在 main 上）。
4. **keepattributes 死路**（实测两轮）：`-keepattributes RuntimeVisibleAnnotations,...` 与 `-keepattributes *Annotation*`（配合 `-keep class androidx.viewpager.widget.ViewPager$DecorView`）都保不住 PagerTitleStrip 上的注解实例（dex 验证：注解类在、实例无）。不再纠缠 R8 语义。
5. **修复**：两处 inflate 后显式 `((ViewPager.LayoutParams) findViewById(R.id.pager_title_strip).getLayoutParams()).isDecor = true;`（`isDecor` 是 public 字段，measure/layout 时才读，onCreate 里置即可）。不关 minify、不关 shrink、不重写 tab，体积收益全保留。
6. **验证**：`./build.sh release arm64` → 装 pixel6 → 重授权限（§4）→ 开仓库 → 按包名取块 dumpsys：strip `0,0-1080,80`、curr TextView `496,11-585,69`（有文字），截图目视「Files / Commits」标签条恢复；Error 弹窗为模拟器仓库路径不存在的已知现象（§5），与 strip 无关。
7. **教训**：`dumpsys activity top` 会 dump 多个 resumed activity，debug 包后台残留页面会让裸 grep 给出"正常"假象（本轮与旧实验 3 都踩过）；判定必须按包名取块。旧实验 3「shrink off = 正常」与 shrink-off 包 dex 无注解的事实矛盾，应是同一陷阱的产物（推断，未重装复测）。
