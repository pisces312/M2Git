# 从 Obsidian 对照里剩下的 P2 待办（三项，附现状读数）

> 状态：**P2-C 与 P2-A 已在分支 `p2-webview-js-assets` 实施完毕**（见各节「实施记录」）；P2-B 未开工。
> P0/P1 已提交 —— `3697b0a`（manifest 瘦身 + 删 `highlight.js`）、`f4b0ce0`（提交历史滚动分页 + 文件页列目录挪出主线程）。
> 本文原为剩下三项 P2 的**源码级现状证据 + 落点 + 验证口径**；实施后各节末尾补了实施记录。
> 调研正文（Obsidian 1.14.4 APK 与本仓的完整对照）在仓外：`D:\3rd-party-projects\obsidian-apk\OBSIDIAN-TECHNIQUES-FOR-M2GIT-AND-MARKOR.md`，另有装机取证 dump `m2git-debug-merged-manifest.txt`。
> 凡是本轮没跑过的命令/没实测的结论，都标了「未实测」，别当既成事实读。

## 0. 读数口径（下次复用前先看这条）

`assets/js` 现在的字节账（`assembleDebug` 产物，2026-10-17 实测）：

| 项 | 数值 |
|---|---|
| 源码 `app/src/main/assets/` | 1,268,737 B / 12 文件 |
| debug APK 内 `assets/` | 1,268,737 B / 12 条目 —— **逐条相等** |
| release APK 内 `assets/` | 比源码多 2,713 B = `dexopt/baseline.prof`(2,401) + `.profm`(312) |

**这套字节差只对 release 成立**：debug 不跑 `CompileArtProfileTask`（`app/build/intermediates/binary_art_profile/` 下只有 `release/` 一个子目录）。用字节差核对前先确认比的是哪种构建，否则会把「这类构建本来就不注入」误读成「注入物被裁掉了」。`unzip -l apk "assets/*"` 在这类包上只匹配顶层条目，要写 `"assets/**"`。

## 1. P2-A：js 资产的重建脚本与真实版本 tag

现状（`app/src/main/assets/js/`，全部实测）：

| 文件 | 字节 | 头部版本自述 | 最后一次提交 |
|---|---|---|---|
| `codemirror-compressed.js` | 886,334 | `Generated on 1/1/2016 with …/compress.html` + **`Version: HEAD`** | `e786246 2017-10-22` |
| `jquery.js` | 267,365 | `jQuery JavaScript Library v2.2.4`（2016，内含 Sizzle，有已知 CVE） | `bd68f53 2023-01-01` |
| `highlight.pack.js` | 94,848 | 未读头部（**未实测**） | `e786246 2017-10-22` |
| `local_commits_diff.js` | 2,305 | 本仓自写 | `87fb4ba 2018-06-25` |
| `editor.js` | 1,072 | 本仓自写 | `d72d577 2026-04-28` |

（`highlight.js` 290,394 B 已在 `3697b0a` 删除 —— 全仓引用数 0，实际用的是 `highlight.pack.js`。）

要做的三件事，按性价比排：

1. **补一个和 `app/libs/make-stripped-jgit-jar.sh` 同规格的 js 重建脚本**：输入清单（CodeMirror 版本 + 启用的 mode/theme 列表、jQuery 版本、hljs pack 的 language 选择）、下载/打包命令、产物落点、校验和。**脚本一律纯英文**（注释和 echo 都不写中文，PS 5.1 GBK 解码会吞字节吃换行；见 AGENTS.md 的同类约定）。
2. **把 `Version: HEAD` 换成真实 tag**，并在 `BuildConfig` 里像现在记库版本那样记一笔，形成「APK 里能反查资产版本」的台账。
3. **CodeMirror 按语言切包**：现在是全模式单包 886 KB，而 `CodeGuesser` 的扩展名表（`utils/CodeGuesser.java:15` 起）实际能映射到的语言只是其中一小截。Obsidian 侧 `modes.min.js` 就是独立文件，可以照这个粒度拆。

两条只有读源码才发现的新东西（属于同一类「资产没人管」的病灶，落 #1 时顺手处理）：

- **`editor.js` 里有两个从 Java 侧调不到的死入口**：`window.save`（:36）调 `CodeLoader.save(...)`，而 `ViewFileFragment.CodeLoader`（:120-152）只有 `getCode / copy_all / loadCode / getTheme` 四个 `@JavascriptInterface`，全仓也没有 `save(String)` 的桥方法；`window.setEditable`（:32）同样没有任何 Java 调用点（`grep setEditable` 在 java 侧 0 命中）。也就是说编辑器是只读的，这两条 JS 路径点了也不会成立 —— 和 `highlight.js` 一样，**只看 APK 永远看不出它是死的**。
- **diff 页唯一的 HTML sink 是 hljs 的输出**：`local_commits_diff.js:32` `$('<code>').html(codes)`，`codes` 来自 `hljs.highlight("diff", diff, true)`；而 path / changeType / commitInfo / commitMessage 全走 `.text()`（:26,29,55,58）。今天不构成注入（hljs 生成 HTML 时会转义正文），但**将来把 hljs 换掉或「优化」成直接 `.html(diff)` 就是另一回事了** —— 远端仓库的提交内容是不可信输入。这条要写进重建脚本的注释里。

### 实施记录（2026-10-17，分支 `p2-webview-js-assets`）

三件事全部落地，脚本 `scripts/rebuild-js-assets.sh`（纯英文，规格仿 `make-stripped-jgit-jar.sh`：顶部 pin 版本、curl 加固、末尾校验和台账 `scripts/js-assets.sha256`）：

- **版本**：jQuery 3.7.1 **slim**（70,264 B，原 267,365 B 未压缩完整版；页面只用 DOM + ready，无需 ajax/effects）→ `js/jquery.slim.min.js`；hljs 7/8-era pack → **11.11.2** common bundle（128,053 B，实测含 `grmr_diff`）→ `js/highlight.min.js`；CodeMirror 2016 HEAD → **5.65.21**（CM5 线内封顶，不上 CM6：`editor.js` 是 `fromTextArea`/`setOption` 的 CM5 API，CM6 全重写无必要）。
- **切包**：全模式 886,334 B → 按 `CodeGuesser` 可达语言选 46 mode + 4 addon（matchbrackets/overlay/simple/multiplex）+ 核心，concat 成 `js/codemirror-bundle.min.js` **534,444 B（-40%）**，文件头注释列全 mode 清单。`assets/` 总量 1,268,737 → ~750 KB。
- **来源**（脚本头有说明）：npm 包走 jsdelivr（字节稳定）；CM 核心走 cdnjs（jsdelivr 对 ~400 KB 核心文件的按需 Terser 会 404）；modes/addons 走 jsdelivr 的 GitHub tag 自动压缩。**踩坑：cdnjs 直连 TLS 抖动（curl exit 35），`--retry` 默认不覆盖，须 `--retry-all-errors`（curl ≥ 7.71）**。
- **hljs 连带适配**：11.x 签名改为 `hljs.highlight(diff, {language:"diff"})`；旧 `rainbow.css` 是 pre-9.x 无前缀主题（配老 pack 才能对上），换成官方 11.x rainbow 主题 `css/hljs-rainbow.css`（`.hljs-*` 选择器），`local_commits_diff.js` 的 code 元素补 `class="hljs"`。
- **死资产/死代码清理**：删 `css/default.css`（hljs 9.x 主题，全仓 0 引用）、旧 `jquery.js`/`highlight.pack.js`/`codemirror-compressed.js`/`rainbow.css`；`editor.js` 删 `window.save`/`window.setEditable` 两个死入口（Java 侧从无对应桥方法）。
- **版本台账**：三个版本号进 `app/build.gradle`（与脚本顶部 pin 双处同步，同 jgitVersion 惯例）→ `BuildConfig.{JQUERY,HLJS,CODEMIRROR}_VERSION` → `MainApplication.kt#setAppVersionPref()` 拼进设置页「关于」。
- **MIME 校验发现的既有 CodeGuesser 侧错配（非本次回归，与 2016 旧包逐项比对确认旧包同样不解析）**：`text/x-dart`（dart.js 只注册 mode 名）、`text/x-x-less`（css.js 注册的是 `text/x-less`，多打了个 x-）、`text/x-Fortran`（fortran.js 注册小写，MIME 查找大小写敏感）、`text/x-aspx`/`text/x-jsp`（htmlembedded 注册的是 `application/x-*`）、`text/nginx`（nginx.js 注册 `text/x-nginx-conf`）、另有 `text/x-clojure.` 尾部多句点、`application/x-csh`/`application/x-msclip`/`image/svg+xml` 无对应 mode。这些语言现在和以前一样显示为纯文本；修 CodeGuesser 是独立小改动，未动（留待决定）。

## 2. P2-B：给本 app 的启动路径做真正的 baseline profile

现状（实测）：

- 全仓没有 `baseline-prof.txt` 输入、没有 `art-profile` 目录、`profileinstaller` 依赖 0 命中、`settings.gradle` 只有 `include ':app'`、`app/src` 下只有 `main/ test/ debug/` 三个源集（**无 `androidTest`**）。
- release APK 里那 2,401 B `baseline.prof` **整份来自 `androidx.appcompat:appcompat:1.6.1` AAR 自带的 `baseline-prof.txt`（51,221 B 文本规则）**，由 AGP `CompileArtProfileTask` 合并成二进制；`androidx.core` 等 AAR 不含该文件。也就是说：**本 app 自己的启动路径覆盖行数 = 0**。
- Obsidian 侧是 8,637 B + 1,077 B（约 3.6×）。

落点（两条路，成本差一个量级）：

- **正规做法**：新增 `:baselineprofile` 模块（macrobenchmark + `ConnectedTestMethodRunListener`/`BaselineProfileGenerator`），用 `androidx.benchmark:benchmark-macro` 跑启动场景生成 profile。代价是要新模块 + `androidTest` 源集 + 一台连着跑的设备，且 AGP 9.4 对应的插件 ID 与 task 名**本轮未实测**（开工前先 `gradlew tasks --all | grep -i profile` 核对，别照旧文章抄 `com.android.experimental.baselineprofile`）。
- **低成本做法**：手写 `app/src/main/baseline-prof.txt`，只钉启动链上确定会走的类。**注意 R8 full mode 下规则要带 `@` 前缀/通配写法，写错就静默失效**（本仓 release 已经开着 R8 full mode，见 `pager-title-strip-release-investigation.md` 里 R8 剥离 `@ViewPager$DecorView` 那次教训 —— 同一个失效模式）。

要覆盖的启动链（源码级，按顺序）：`MainApplication.onCreate` → `RepoListActivity`（`onCreate` + 仓库列表装载，含 `RepoDbManager` 读 `repo.db` 与 `CommitsListAdapter`/`Repo.getCommitDisplayName` 的字符串处理）→ 进仓库详情 → `FilesListAdapter.setDir` 的后台列举线程（`f4b0ce0` 之后线程名 `mgit-dir-listing`）与 `entryComparator` → `CommitsListAdapter.resetCommit` + 首屏 `PAGE_SIZE=100` 的 `GetCommitTask`。

验证口径（**别把 `.prof` 字节数当 KPI**）：`.prof/.profm` 是 ART 二进制（magic `006f7270`、version 50），字节差不能换算成 AOT 覆盖行数。要量就量冷启动：同一台模拟器 `adb shell am start -W -n <pkg>/ts.realms.m2git.ui.screens.main.RepoListActivity` 的 `TotalTime` 前后对照（`<pkg>` 是 release 的 `ts.realms.m2git`、debug 带后缀 `ts.realms.m2git.debug`；`RepoListActivity` 是 exported 的，`RepoDetailActivity` 未导出、`am start` 拉不起来 —— 这是本机踩过的）。profile 生效还要等一次 bg-dexopt，这条时序**未实测**，先当待验证项。

## 3. P2-C：`loadUrl("javascript:…")` 换成 `evaluateJavascript` + `JSONObject.quote`

现状（实测，`grep loadUrl` 全仓 6 处、`evaluateJavascript` 0 处）：

| 调用点 | 注入的 JS | 备注 |
|---|---|---|
| `ViewFileFragment.java:84` | `file:///android_asset/editor.html` | 页面加载，本来就该用 `loadUrl` |
| `ViewFileFragment.java:92` | `copy_all();` | 无回调 |
| `ViewFileFragment.java:96-97` | `String.format("setLang('%s')", lang)` → `loadUrl` | **手工拼单引号** |
| `ViewFileFragment.java:162-163` | 同上（`display()` 里） | 同上 |
| `ViewFileFragment.java:165` | `display();` | 无回调 |
| `CommitDiffActivity.java:268` | `notifyEntriesReady();` | 在 diff 任务回调里推给页面 |

包装器是 `utils/CodeGuesser.java:14` `URL_SCRIPT_WRAPPER = "javascript:(function(){%s;})()"`。

三个要解决的点，按真实痛点排（不是「因为 API 更先进」）：

1. **`setLang('%s')` 是手工拼字符串**。今天 `lang` 只来自 `CodeGuesser.guessCodeType()` 的固定表或 `null`（`ViewFileFragment.java:156-161`），注入不成立；但这正是「默认参数 ≠ 实际行为」那类隐患 —— 一旦语言变成用户可选、或把文件名直接掺进 JS，就会破。改成 `JSONObject.quote(lang)` 拼参数，别自己加引号。
2. **没有回调 = 静默失败**。`loadUrl("javascript:…")` 拿不到返回值，也拿不到异常，页面还没 ready 时整条调用直接蒸发。`copyAll()`（:92）如果在 `editor.html` 装载完成前被调，表现就是「点了没反应」。`evaluateJavascript(script, callback)` 至少能把「没反应」变成有日志的失败。落点：`WebViewClient.onPageFinished` 置一个 `mPageReady` 门控，未 ready 时排队或直接丢弃并打日志（两处 WebView 都要）。
3. **`getDiffEntries()` 之后靠 `loadUrl("javascript:notifyEntriesReady()")` 反向通知页面**（`CommitDiffActivity.java:258-270`）—— 这是页面→Java 拉数据、Java→页面推信号的混合模型，换成 `evaluateJavascript` 后这条链路语义不变，但要留意它跑在 diff 任务回调线程上，`evaluateJavascript` 必须在 UI 线程调（现在这段是否已经切回主线程**未逐项核实**，动之前先看 `CommitDiffTask.CommitDiffResult.pushResult` 的调用线程）。

minSdk 31，`evaluateJavascript`（API 19+）没有兼容包袱。回归面很小：查看文件高亮、复制全部、切语言、diff 页渲染 —— 四条都在两个 WebView 上。

### 实施记录（2026-10-17，分支 `p2-webview-js-assets`）

- 新增 `utils/WebViewJs.java`：per-WebView 通道，构造时挂 `WebViewClient.onPageFinished` 置 ready；ready 前 `eval()` 入队（Timber.w 留痕）、ready 后按序补发 —— 「点了没反应」的静默失败面消掉。两个调用文件各持一个实例（`ViewFileFragment.mJs` / `CommitDiffActivity.mJs`）。
- 5 个 `loadUrl("javascript:…")` 调用点全部改为 `WebViewJs.eval`；页面加载本身（`editor.html` / `loadDataWithBaseURL`）仍用 loadUrl/loadDataWithBaseURL，符合预期。
- `setLang` 参数改 `JSONObject.quote(lang)`。附带修正一个既有小 bug：SSH key 模式下 `lang == null`，旧写法拼出 `setLang('null')`（字符串 "null"），quote 后是 JS 字面量 `null`，才是「无语言」的正确语义。
- `CodeGuesser.URL_SCRIPT_WRAPPER` / `wrapUrlScript()` 调用点归零后删除。
- 线程问题已核实（原文标「未逐项核实」）：`MAsyncTask.executeTask` 把 `onPostExecute` 经 `Handler(mainLooper).post` 送回主线程，`CommitDiffTask` 的 `pushResult` 在主线程调，`evaluateJavascript` 可直接用，无需再切线程。
- 已知边界：`evaluateJavascript` 的回执恒为 null（JS 异常不进回调），异常仍靠 `WebChromeClient.onConsoleMessage` 的既有 Timber 日志兜底。

## 4. 已经评估过、明确不做的（防止下次重新发明）

| 项 | 不做的理由（实测依据） |
|---|---|
| WebView 补 `setAllowFileAccess(false)` | targetSdk 31，API 30+ 起该项默认就是 false，写了也是空操作 |
| i18n 懒加载（照 Obsidian 的 `mapping.txt` + 按行号对齐） | 原生应用由 Android 按 locale 裁剪 `res/values-*`，这条路对本 app 不适用 |
| 资源名混淆 / `shrinkResources` | release 的 `res/` 早就是 2 字符名（`res/-5.xml`），已经是既成状态 |
| NDK 侧 `fts_*` 目录 watcher | 纯 Java 工程，唯一的 `.so` 是 2.1 MB 的 conscrypt；为 watcher 引 NDK 不划算 |
| 收窄 JS 桥 | 桥本来就只有 `ViewFileFragment` 4 个（`getCode/copy_all/loadCode/getTheme`，:120-152）/ `CommitDiffActivity` 10 个方法（`getDiff/haveCommitInfo/getCommitInfo/getCommitMessage/getChangeType/getOldPath/getNewPath/getDiffEntries/getDiffSize/getTheme`，:207-280），且是页面主动拉数据（正文走 `getCode()`，不经 `loadUrl`），不存在 Obsidian 那种「整个 Fragment 交给页面 JS」的大桥 |

## 5. 建议顺序

~~P2-C → P2-A → P2-B~~ **P2-C 与 P2-A 已完成（2026-10-17，分支 `p2-webview-js-assets`，见各节实施记录）**。剩 P2-B：成本最高，且需要先解决「新模块 + 连设备跑」的前置；先试手写 `baseline-prof.txt` 那条低成本路，量到收益再上 macrobenchmark。

Markor 侧的对照结论不在本文范围（它 `:1008 addJavascriptInterface(this, "Android")` 把整个 Fragment 暴露给页面 JS，和 P2-C 不是同一个问题）。
