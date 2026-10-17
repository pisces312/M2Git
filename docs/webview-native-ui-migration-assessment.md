# WebView/JS 界面迁移 Android 原生 UI 调研与决策

> 日期：2026-10-17
>
> 状态：调研完成，暂不实施全面原生迁移
>
> 结论：保留「Android 原生应用外壳 + WebView 内容渲染」的混合架构；文件查看器继续使用 CodeMirror，Diff 查看器先优化 JS，只有在实测仍不能满足性能或交互要求时再单独迁移原生。

## 1. 调研目的

评估 M2Git 当前使用 HTML/JavaScript 的界面是否应迁移到 Android 官方现代 UI 技术栈（Jetpack Compose），并明确以下问题：

- JS 界面的真实范围和职责；
- 迁移到 Compose 的收益、成本和风险；
- 保留 WebView 是否会成为性能或维护负担；
- 适合本项目的长期 UI 架构和实施顺序。

## 2. 当前实现范围

M2Git 并不是整体基于 Web 技术的应用。仓库管理、Git 操作、导航、设置、菜单、文件系统、保存和分享均为 Android 原生实现。WebView 只承担两类富文本内容展示。

### 2.1 文件查看器

主要文件：

- `app/src/main/java/ts/realms/m2git/ui/components/fragments/ViewFileFragment.java`
- `app/src/main/assets/editor.html`
- `app/src/main/assets/js/editor.js`
- `app/src/main/assets/js/codemirror-bundle.min.js`
- `app/src/main/assets/css/codemirror.css`
- `app/src/main/assets/css/midnight.css`

实现方式：

1. `ViewFileFragment` 加载 `file:///android_asset/editor.html`；
2. JS 通过 `CodeLoader.loadCode()` 请求 Java 后台读取文件；
3. Java 读取全文后调用 JS 的 `setLang()` 和 `display()`；
4. CodeMirror 负责行号、自动换行、主题、语法高亮、括号匹配和文本选择；
5. SSH key 模式下，JS 将全文交给原生 `ClipboardManager`。

当前 CodeMirror 配置包含 `readOnly: true`。Java bridge 没有保存接口，因此该页面是只读查看器；普通文件的“编辑”功能由 Android Intent 交给外部应用。

### 2.2 Diff 查看器

主要文件：

- `app/src/main/java/ts/realms/m2git/ui/screens/commitDiff/CommitDiffActivity.java`
- `app/src/main/java/ts/realms/m2git/tasks/CommitDiffTask.java`
- `app/src/main/assets/js/local_commits_diff.js`
- `app/src/main/assets/js/highlight.min.js`
- `app/src/main/assets/js/jquery.slim.min.js`
- `app/src/main/assets/css/hljs-rainbow.css`
- `app/src/main/assets/css/local_commits_diff.css`

实现方式：

1. `CommitDiffTask` 在后台通过 JGit 生成 `DiffEntry` 和原始 patch；
2. 页面加载完成后，JS 通过 `CodeLoader` bridge 拉取 commit 信息和每个 DiffEntry；
3. highlight.js 负责 diff 文本着色；
4. JS 为每个文件创建完整 DOM，并追加到页面；
5. 保存和分享直接使用 Java 层保留的原始 patch，不依赖 DOM 内容。

Diff 页支持 commit、index、working tree、初始提交及单文件过滤等比较场景。这些能力属于 JGit/Java 数据层，与采用 WebView 或 Compose 渲染无关。

### 2.3 公共通信机制

`WebViewJs` 封装了 `evaluateJavascript()`：

- `onPageFinished` 前的调用先进入队列；
- 页面 ready 后按顺序执行；
- JS 异常主要通过 `WebChromeClient.onConsoleMessage` 记录。

两页都使用 `addJavascriptInterface`，并存在“JS 发起加载 → Java 后台计算 → Java 通知 JS → JS 再拉取数据”的双向通信链路。

## 3. 项目现有 Android UI 技术栈

当前项目主要使用：

- Java 11；
- AppCompat Activity；
- AndroidX Fragment；
- XML Layout；
- ViewBinding 和 DataBinding；
- ViewModel 和 LiveData；
- Material Components；
- RecyclerView；
- 旧版 `ViewPager`、`FragmentPagerAdapter` 和 `PagerTitleStrip`。

项目尚未引入 Compose，也没有 `@Composable`、`ComposeView` 或 Compose 依赖。构建参数为 `minSdk 31`、`targetSdk 31`、`compileSdk 34`，并使用 AGP 9 内置 Kotlin。

因此，如果只为两个内容页面引入 Compose，短期内会同时维护 XML View、Compose 和 Web/第三方代码组件。除非已经决定逐步迁移整个 App，否则这会增加技术栈，而不一定减少复杂度。

## 4. 全面原生迁移方案评估

Android 官方将 Jetpack Compose 定位为现代声明式 UI 工具包，并推荐用不可变 UI State、事件和 ViewModel 构建单向数据流。若迁移，可采用：

```text
Activity / Fragment
        │
        ▼
Compose Screen
        │ event
        ▼
ViewModel
        │
        ├── FileLoader / SyntaxHighlighter
        └── CommitDiffUseCase / PatchParser
        │
        ▼
StateFlow<UiState>
```

### 4.1 原生文件查看器

可使用 `LazyColumn` 按逻辑行渲染：

- 行号；
- 等宽字体；
- `AnnotatedString` 语法着色；
- 横向滚动或自动换行；
- 后台文件读取和分词；
- 按行缓存高亮结果。

主要问题是 Android 官方没有与 CodeMirror 等价的源代码查看/编辑组件。完整迁移意味着以下三种选择之一：

1. 自行维护多语言 tokenizer 和高亮规则；
2. 引入第三方原生代码编辑器，再通过 `AndroidView` 嵌入 Compose；
3. 放弃部分语言和交互能力，不支持的语言退化为纯文本。

其中，自研方案开发和长期维护成本最高；第三方方案虽然移除了 WebView，但并没有消除外部组件依赖。

### 4.2 原生 Diff 查看器

Diff 的结构有限，比源代码高亮更适合原生化。可将 patch 解析成：

- commit header；
- file header；
- hunk header；
- context line；
- added line；
- deleted line；
- no-newline/binary metadata。

Compose 使用 `LazyColumn` 和不同 item 类型进行虚拟化渲染。必须继续保留原始 patch，保存和分享不能从渲染模型反向生成，否则可能破坏 patch 内容。

需要正确处理：

- ADD、DELETE、MODIFY、RENAME、COPY；
- 初始提交和空文件；
- binary diff；
- 路径转义；
- `No newline at end of file`；
- 超长行；
- old/new 行号；
- index 与 working tree 的特殊比较。

### 4.3 原生迁移的主要风险

1. **语法覆盖下降**：自研高亮短期无法覆盖 CodeMirror 当前语言集合。
2. **文本选择体验下降**：虚拟化列表中的跨屏选择比 CodeMirror 困难。
3. **重复建设**：需要重新实现行号、换行、选择、长行和高亮缓存。
4. **技术栈增加**：只迁两个页面会让项目同时维护 XML 和 Compose。
5. **Patch 解析边界**：原生 Diff 解析器需要覆盖 Git patch 的特殊形式。
6. **性能不必然改善**：如果将全文放入单个 Compose `Text`，仍可能比 WebView 更慢。

## 5. 为什么当前不建议全面迁移

### 5.1 内容类型适合 Web 技术

CodeMirror 已成熟解决多语言代码查看中的核心问题。文件查看页未来如果增加真正的编辑、查找替换、跳转、折叠或代码选择能力，Web 编辑器生态的优势会进一步扩大。

将其迁到 Compose 不是普通的 UI 替换，而是在开发和维护一个简化代码编辑器。

### 5.2 JS 并不天然意味着不流畅

Obsidian 等应用说明，文档型应用是否流畅主要取决于：

- 可见区域渲染；
- DOM 虚拟化；
- 增量更新；
- 节点复用；
- 数据分块；
- 原生与 JS 通信频率；
- 大文档降级策略。

M2Git 的文件查看器由 CodeMirror 管理，已经具备较成熟的可视区域处理。当前更值得关注的是 Diff 页一次创建全部 DOM、每个条目多次跨 bridge 拉取数据，而不是 JS 本身。

### 5.3 当前 WebView 边界较小且可控

页面只加载 APK 内置 assets，不访问在线服务，也不执行仓库内的脚本。Git、文件、保存和分享仍在 Java 层。只要进一步收紧 WebView 权限和 bridge 生命周期，其安全风险是可控的。

### 5.4 迁移收益不足以覆盖成本

全面原生化能够消除 WebView/JS bridge，并改善生命周期建模，但会付出多语言高亮、文本交互、性能调优和第三方依赖等成本。目前没有已确认的用户问题证明这项投入优先级足够高。

## 6. 决策

### 6.1 文件查看器

**继续使用 CodeMirror/JS。**

原因：

- 代码查看是 CodeMirror 的优势领域；
- 当前语言覆盖和交互能力较完整；
- 原生没有官方等价组件；
- 迁移成本最高、直接收益最低；
- 未来增加编辑能力时仍可复用。

### 6.2 Diff 查看器

**短期继续使用 JS，但优先进行性能和生命周期优化。**

只有在建立基准并完成 JS 优化后，大型 Diff 仍然不能达到目标，才单独迁移为 RecyclerView 或 Compose `LazyColumn`。Diff 原生化不应与文件查看器迁移绑定。

### 6.3 App 其他 UI

继续使用 Android 原生 UI。若未来确定全项目逐步 Compose 化，可优先从新页面或业务型页面开始，而不是首先重写代码查看器。

## 7. 推荐优化路线

### 7.1 建立性能基准

在同一台目标设备上记录：

- 1 MB、5 MB、20 MB 文件的首次显示时间、内存峰值和滚动卡顿；
- 1,000、10,000、50,000 行 Diff 的生成时间、首次显示时间和滚动卡顿；
- 页面旋转、后台恢复和快速返回时的行为；
- 深浅主题切换结果。

没有基准前，不应把“换成原生”视为性能优化结论。

### 7.2 优化 Diff JS 渲染

按优先级建议：

1. 减少每个 DiffEntry 的同步 bridge 调用；
2. 按批次传递结构化数据，而不是逐字段拉取；
3. 使用 `DocumentFragment` 批量插入；
4. 使用 `requestAnimationFrame` 分帧构建；
5. 使用 `IntersectionObserver` 延迟生成屏幕外文件；
6. 大型 Diff 默认折叠单文件内容；
7. 去掉仅用于 DOM/ready 的 jQuery，改用原生 API；
8. 避免 Java、JS 字符串和 DOM 同时长期持有不必要的副本。

### 7.3 改善生命周期

- 将文件读取和 Diff 计算移入 ViewModel；
- 使用可取消的后台任务；
- 页面旋转后复用结果；
- Fragment view 销毁后停止回调；
- `onDestroyView`/`onDestroy` 中移除 bridge、停止加载并销毁 WebView；
- 防止已经销毁的 Activity 被后台任务引用。

### 7.4 收紧 WebView

- 明确禁止不需要的网络请求和导航；
- 只允许本地受控 assets；
- 禁用不需要的文件、内容和窗口能力；
- 缩小 JavaScript bridge 暴露面；
- 对所有进入 HTML 的仓库内容坚持文本转义；
- 不允许把原始 diff 直接交给 `.innerHTML`/`.html()`；
- 评估使用受控消息通道替代同步 bridge 的可行性。

## 8. 重新考虑原生迁移的触发条件

出现以下任一条件时重新评估：

- 项目已经决定逐步全面迁移 Compose；
- WebView 在目标 Android 版本出现无法规避的兼容问题；
- JS 优化后，大文件或大型 Diff 仍明显不流畅；
- 无障碍或原生文本交互成为明确产品要求；
- 团队愿意长期维护原生 tokenizer；
- 找到可靠的原生代码组件，并接受其许可证、体积和维护风险；
- 不再要求当前多语言语法高亮覆盖。

## 9. 如果未来决定迁移，推荐顺序

```text
Compose 基础设施
  → 原生 Diff 页面
  → 评估原生文件查看器的高亮方案
  → 原生文件查看器
  → 删除 WebView/JS assets 和版本台账
  → 最后迁移 ViewPager 等宿主 UI
```

迁移完成的验收条件至少包括：

- 文件查看和 Diff 行为与当前版本一致；
- 保存/分享 patch 内容与迁移前一致；
- ADD/DELETE/MODIFY/RENAME/binary 等场景正确；
- 大文件和大型 Diff 没有主线程长时间阻塞；
- 旋转后不重复执行昂贵任务；
- 深浅主题和中英文资源正确；
- 默认 arm64 debug 构建通过。

## 10. 最终架构判断

适合 M2Git 的长期结构是：

```text
Android 原生层
├── 仓库、文件和 Git 业务
├── Activity / Fragment / 菜单
├── 权限、设置、保存和分享
└── ViewModel / 生命周期

受控 WebView 内容层
├── CodeMirror：源代码查看及未来编辑
└── Diff Renderer：Git patch 展示
```

这不是退而求其次的临时架构，而是按技术优势划分职责。近期应优先提升 WebView 边界、Diff 渲染性能和生命周期管理，而不是追求形式上的全原生。

## 参考资料

- [Android Developers — Thinking in Compose](https://developer.android.com/develop/ui/compose/mental-model)
- [Android Developers — Compose UI Architecture](https://developer.android.com/develop/ui/compose/architecture)

官方资料相关表述均经过重新组织和概括。