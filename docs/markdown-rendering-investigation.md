# 在 M2Git 里渲染 Markdown 的选项调研（Obsidian / Markor 对照 + 现状）

> 状态：**纯调研，未改任何代码**。结论见 §5：M2Git 现有的 `markdown_open_mode=system`（默认）已经把 `.md` 交给系统应用（如 Markor）打开，对个人使用「两 App 分工」已足够；把 Markor 的渲染内建进 M2Git 基本是重复造轮子，唯一有独占价值的场景是「渲染历史/任意 commit 里的 md blob」（§4.3）。
> 所有字节数都是实测（来源标注在各处）；凡是没构建/没跑过的推算都标了「未实测」。
> 相关文档：Obsidian 工程治理对照在 `obsidian-derived-improvements-p2.md`（**那份讲的是 WebView 资产治理，不是 md 渲染**）；更完整的 Obsidian/Markor 取证在仓外 `D:\3rd-party-projects\obsidian-apk\OBSIDIAN-TECHNIQUES-FOR-M2GIT-AND-MARKOR.md`。

## 0. 三个基线（实测）

| 项 | 数值 | 来源 |
|---|---|---|
| M2Git release APK（v1.8.10 签名版） | 11,748,407 B ≈ 11.75 MB | `stat` 仓库根目录 APK |
| 其中 `classes.dex`（R8 收缩后，**单 dex**） | 8,184,424 B ≈ 7.81 MB | `unzip -l` |
| 其中 `assets/` | 752,541 B ≈ 0.72 MB | `unzip -l "assets/**"` |
| M2Git 已内置代码高亮库 | `highlight.min.js` hljs 11.11.2 / 128,053 B | `app/src/main/assets/js/` |
| Markor `app/thirdparty/assets` 总量 | 7,473,245 B ≈ 7.47 MB | `du -sb` |

（注意：debug APK 是 26.6 MB、多 dex、未收缩，别拿它当体积基线。release 才是 R8 收缩后的真实分发体积。）

## 1. M2Git 现状：`.md` 是怎么打开的（关键，实测源码）

M2Git **已经有**一套 markdown 打开方式的设置，不是只能内建看源码。

- 偏好项：`pref_key_markdown_open_mode`（键名 `markdown_open_mode`，`values/strings.xml:220`，`translatable=false`），`preferences.xml:22-29` 是个 `ListPreference`，**默认值 `system`**。
- 两个选项（`values/arrays.xml:61-68`，双语见 `values-zh-rCN/arrays.xml`）：
  - `preview` → 「使用内置预览」→ 走内部 `ViewFileActivity` → `ViewFileFragment` → `editor.html` 的 CodeMirror，**只是带语法高亮的源码**（`CodeGuesser` 把 `md` 映射到 `text/x-markdown`/`gfm` 模式）。
  - `system` → 「使用系统应用（推荐）」→ **默认**。
- 分派逻辑在 `FilesFragment.java:84-118`：`.md` 且 mode=`system` 时，用 `FileProvider` 生成 content URI，发 `Intent.ACTION_VIEW` + `setDataAndType(uri, "text/markdown")`，`queryIntentActivities` 非空就 `startActivity` 交给外部应用；**没有应用能接**则 `Timber.e` 记一条并回落到内置预览（`:104`）。

**结论**：装了 Markor 并注册了 `text/markdown` 的 VIEW，M2Git 默认就会把 `.md` 弹给 Markor 渲染。这就是「两 App 分工」模型，**已经在跑**，不需要新写任何东西。

## 2. Obsidian 的 md 渲染是自研还是第三方库（从 1.14.4 APK 验证）

**核心排版渲染是自研的，不是公开的 md→html 库。** 证据（`D:\3rd-party-projects\obsidian-apk\extracted\assets\public\`）：

- `app.js`（3,968,611 B）里有自己的 `MarkdownRenderer.renderMarkdown(...) → render(...)`，直接构建 Obsidian 的 Component/DOM，不走「md 字符串 → html 字符串」。
- `lib/` 里**没有** `marked.js`/`markdown-it`/`showdown`；`app.js` 里搜到的 `marked(` 是 `bookmarked(` 的假阳性（已核上下文）。
- 但**子功能用公开第三方库**：`prism.min.js`（代码高亮）、`mathjax/`（数学）、`mermaid.min.js`（图表）、`turndown.js`（反向 HTML→md，用于粘贴/剪藏）、编辑器是 **CodeMirror 5.64.0**（`lib/codemirror/codemirror.js` 头 `version="5.64.0"`；注意仓外那份 OBSIDIAN 文档写成「CodeMirror 6」是错的，实测是 5.64.0）。

即：Obsidian 的渲染效果 = **自研排版器 + 一堆公开功能库**。它的自研渲染器无法抠出来复用（专有 + 与其 Component/运行时耦合）。

## 3. Markor 的 md 渲染由什么组成（实测源码）

两半：**Java 端 Flexmark 渲染 + WebView 里按需注入 JS 资产**。

### 3.1 Java 侧（Flexmark）

- `MarkdownTextConverter.java`（486 行）用 Flexmark `Parser` + `HtmlRenderer`，`flexmarkExtensions` 挂了 **20 个扩展**（表格、删除线、任务列表、脚注、TOC/SimToc、autolink、anchorlink、KaTeX、admonition、wikilink、jekyll tag/front-matter、yaml front-matter、typographic、gitlab、ins、superscript、行号 id）。
- `app/build.gradle:19` pin `flexmark 0.42.14`，`implementation` 了 **19 个 flexmark artifact**（core + util + 17 ext），另有仓内 vendored 的 `other.com.vladsch.flexmark.ext.katex.FlexmarkKatexExtension`。
- `TextConverterBase.java`（208 行）是各格式共用的 WebView 基类，负责 `loadDataWithBaseURL` 把渲染结果喂给预览 WebView。

Flexmark jar 尺寸（从 Maven Central 实拉 `0.42.14`，压缩包）：

| 集合 | artifact 数 | jar 合计 |
|---|---|---|
| 完全对齐 Markor 的 flexmark 集 | 19 | 1,345,971 B ≈ 1.28 MB |
| 精简（README 级：core+util+tables+gfm-strikethrough+gfm-tasklist+autolink+anchorlink+footnotes） | 8 | 973,535 B ≈ 0.93 MB |

（单个大头：`flexmark` 388,623 B、`flexmark-util` 385,068 B。jar 是压缩包，**不等于 dex 增量**；dex 增量见 §4，未构建=未实测。）

### 3.2 资产侧（可选功能，条件注入）

`MarkdownTextConverter` 按正文内容**条件注入**（这是它比 Obsidian 懒加载更朴素的既有实现）：含 `$`→KaTeX、含 ```` ``` ````→Prism、含 ```` ```mermaid ````→Mermaid、含 `!!!`/`???`→admonition。基础排版用的 CSS 全是转换器里的**内联字符串**，几乎不占资产。

`app/thirdparty/assets`（7.47 MB）里为「渲染一个 README」真正必需的部分接近 0，其余都是额外能力：

| 资产 | 大小 | 是否为基础 md 所需 |
|---|---|---|
| `mermaid/mermaid.min.js` | 2.67 MB | 否（仅图表） |
| `fonts/*.ttf`（Liberation/DejaVu/… 7 个） | ~1.5 MB | 否（可用系统字体） |
| `katex/`（js 275 KB + 字体 ~1.9 MB） | ~2.2 MB | 否（仅数学） |
| `asciidoc/asciidoctor.min.js` | 762 KB | 否（不是 md） |
| `prism/` | ~400 KB | 否（**M2Git 已有 hljs 可替代**） |
| `orgmode/org-bundle.js` | 56 KB | 否（不是 md） |
| `flexmark/admonition.{css,js}` | 几 KB | 否（仅 admonition） |

## 4. 若要把渲染内建进 M2Git：改动量与体积

### 4.1 精简档（README 级排版：标题/列表/表格/代码高亮/链接/图片/任务列表/删除线/脚注/TOC）

- **新库**：只加 **1 个库族 = Flexmark**，8 个 artifact（jar 0.93 MB）。代码高亮**复用已有 hljs，不引 Prism**。
- **新资产**：一个 md 预览 CSS + 一个 HTML 模板 ≈ 5–15 KB，可忽略。
- **代码改动**（中等偏小，不需要新 Activity）：
  - 新增 1 个转换器类 ~150–250 行（建 `Parser`/`HtmlRenderer` + 少量扩展 + HTML 模板）。Markor 那 486 行一半是它自己的东西（beamer 演示、jekyll、notable 附件、YAML token），git 客户端可砍。
  - 改 `ViewFileFragment`：加「源码 ⇄ 预览」切换，`.md` 预览走 `loadDataWithBaseURL`（与现有 `CommitDiffActivity` 渲染 diff 同一套 WebView 打法，P2-C 做好的 `WebViewJs` 通道可复用）。图片相对路径靠 base URL 指到仓库工作树解析。
  - 加切换用的字符串/菜单，按 AGENTS.md 约定 `values/` 与 `values-zh-rCN/` 双语同步；`*_values` 存储键不翻译。
- **APK 增量**：dex **估 +0.6–1.2 MB（未实测，需构建）**，资产几乎不变 → 11.75 MB 变约 **12.5–13 MB**。

### 4.2 完全对齐 Markor（数学 + 图表 + 全字体 + 演示）

- **新库**：Flexmark 扩到 19 artifact（jar 1.35 MB）+ vendored KaTeX 扩展。
- **新资产**：mermaid +2.67 MB、katex(js+字体) +~2.2 MB、prism +~0.4 MB、admonition 几 KB ≈ **+5.1 MB**。
- **APK 增量**：dex 估 +0.9–1.6 MB + 资产 ~5 MB → 11.75 MB 变约 **17–18 MB**。

### 4.3 安全（无论哪档都必须做，0 新库）

远端仓库的 md 是**不可信输入**，Flexmark 默认放行原文里的 raw HTML → 必须开 `HtmlRenderer.ESCAPE_HTML`（或抑制 HTML）堵 XSS。这是配置项不是新依赖。呼应 `obsidian-derived-improvements-p2.md` §1 对 diff 页 HTML sink 的提醒——同一类风险。

## 5. 「两 App 分工」vs「内建渲染」：结论

**对个人使用，现有的两 App 模型已经足够，而且严格优于自己重实现**：

- §1 证明 M2Git 默认就把 `.md` 交给系统应用（Markor）。Markor 的渲染器完整度远超任何临时拼装，且**你为此付 0 额外 APK 体积、0 维护成本**（flexmark 版本、CSS、安全补丁都由 Markor 承担）。
- 内建精简档要 +~1 MB 且自己背 XSS sanitize 责任，换来的排版能力还不如 Markor 全。

**内建渲染唯一有独占价值、外部 App 做不到的场景**：

1. **渲染历史/任意 commit 里的 md blob**——git 对象库里某个旧版本的 README 没有工作树文件，无法用 `FileProvider` 递给外部 App；内建渲染可以直接把 `git show <commit>:README.md` 的字节流渲染出来。这是 git 客户端的固有能力，两 App 模型根本覆盖不到。
2. **改善「没装 md 应用」用户的回落体验**——现在回落到 CodeMirror 源码视图（§1 `:104`），对内建了渲染就能回落到真正的排版预览。但这是给 upstream/其他用户的收益，对你本人（已装 Markor）无感。

其余常见「好处」（不切出 App、少一次 chooser）在体验上是真的，但都属于便利性微调，不足以抵消 +1 MB 体积 + 新依赖 + sanitize 维护的成本。

**建议**：维持两 App 分工，不内建。除非将来要做「在提交历史里预览 README 渲染效果」这类 git 原生功能——那时再上 §4.1 精简档，且只为该场景服务。

## 6. 版本选择备注（若真要内建）

Markor pin `flexmark 0.42.14`（旧）。M2Git 是 Java 11 / minSdk 31，可选最新 `0.64.x`——但 0.64 把 `flexmark-util` 拆成多个子模块，artifact 数变多（同一库族），迁移面略大。跟 Markor 用 0.42.14 可最大化「行为一致 + 可直接对照它的转换器代码」，代价是背着旧版本。**本轮未决策**。

## 7. Markor 版本现状 + 升级其 Flexmark 的可行性（2026-10-18 调研）

用户手机装的 Markor = **2.16.1**，与上游 `gsantner/markor` **最新 release 完全一致**（`gh release list`：v2.16.1，2026-03-19，标记 Latest）。本地克隆 `/d/3rd-party-projects/markor` 是 fork（`origin=pisces312/markor`），HEAD `81d040a3`，`versionName 2.16.1 / code 163`——**已是最新，无需升级 App 本体**。工作树只有 1 个未跟踪文件 `CLAUDE.md`。

### 7.1 版本差距（实测 Maven Central `maven-metadata.xml`）

| 项 | 值 |
|---|---|
| Markor 当前 pin | flexmark **0.42.14**（`app/build.gradle:19`，2020-01-23 构建） |
| Maven Central 最新 | flexmark **0.64.8**（`<latest>`/`<release>` 均为 0.64.8，2023-05-18 构建） |
| metadata `lastUpdated` | 20230523 → **flexmark 自 2023-05 起停更**，0.64.8 就是天花板，没有更新的活跃版本 |

即「升级到最新」= 升到一个 2023 年的版本；跨度 0.42.14→0.64.8 约 3.3 年的 flexmark 演进（CommonMark/GFM 规范符合度 + 大量解析边界 bug 修复）。

### 7.2 破坏性改动面（实测，很小）

关键 package 迁移：**`com.vladsch.flexmark.util.options.*` → `com.vladsch.flexmark.util.data.*`**（0.60 模块化时改名）。`util.ast` / `util.builder` / `util.html` / `util.sequence` 四个子包**未改名**（0.42.14 与 0.64.8 里路径一致，已逐一对比 jar）。

全仓引用 flexmark 的 Java 只有 **2 个文件**（`grep -rl com.vladsch.flexmark`）：

| 文件 | 需改的 import | 说明 |
|---|---|---|
| `app/src/main/java/.../markdown/MarkdownTextConverter.java` | 2 处 `util.options`（`MutableDataHolder`/`MutableDataSet`） | 其余 `util.ast`/`util.builder`/`util.html` 不动 |
| `app/thirdparty/java/other/com/vladsch/flexmark/ext/katex/FlexmarkKatexExtension.java`（仓内 vendored） | 4 处 `util.options`（`DataHolder`/`DataKey`/`MutableDataHolder`/`MutableDataSetter`） | vendored 扩展，跟着改 |

合计约 **6 行 import 改名** + build.gradle 版本号。

### 7.3 一个构建坑（实测）

`flexmark-util-0.64.8.jar` 是**空壳**（1828 B，只含 META-INF，无 class）——0.60 起 `flexmark-util` 拆成 `flexmark-util-{ast,builder,data,format,html,misc,options,sequence,visitor,collection,dependency,mappers}` 若干独立 artifact，老坐标变成聚合壳。升级时 build.gradle 要么依赖其 POM 的传递依赖把子模块带进来，要么显式列出用到的 `flexmark-util-*`。**能否只改版本号靠传递依赖搞定，需实际构建确认（未实测）。**

### 7.4 可行性结论与风险

- **机械改动很小**（版本号 + ~6 行 import），但**必须真跑一次 Gradle 构建**才能确认没有更深的 API 漂移（如 `util.html.Attributes#addValue` 签名、各 ext 的 option 常量），本轮未构建=未实测。
- **渲染质量提升是真实但温和的**：主要收益在解析边界（嵌套列表、复杂表格、autolink、HTML 块、脚注）的规范符合度；典型 README 肉眼差异不大。且 Markor 的观感还受其自带 CSS + Prism/KaTeX 版本影响，flexmark 只管 md→HTML 正确性这一层。
- **风险信号**：上游 Markor 到 v2.16.1（2026-03）仍 pin 0.42.14，跨过 flexmark 3 年的版本没升——可能是 vendored katex 扩展/体积/行为回归的顾虑，也可能只是没人做。升级后要回归测：数学($)、表格、任务列表、脚注、TOC、admonition、mermaid 代码块。
- **归属**：改动落在 `pisces312/markor` fork，与 M2Git 仓库无关；M2Git 侧不需要任何改动（它只是通过 `ACTION_VIEW` 把 md 交给 Markor）。
