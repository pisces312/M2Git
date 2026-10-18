(function() {
  var lang = void 0;

  var editor = void 0;

  // Mirrors FileViewOptions (Java) field names. Keep in sync: the host sends the same
  // keys through window.applyViewOptions() and CodeLoader.getViewOptions().
  var viewOptions = {
    lineNumbers: true,
    lineWrapping: true,
    matchBrackets: true,
    fontSizeSp: 0,
    tabSize: 4
  };

  var mergeOptions = function(source) {
    var merged = {};
    var key;
    for (key in viewOptions) {
      if (viewOptions.hasOwnProperty(key)) {
        merged[key] = viewOptions[key];
      }
    }
    if (source) {
      for (key in source) {
        if (source.hasOwnProperty(key) && merged.hasOwnProperty(key)) {
          merged[key] = source[key];
        }
      }
    }
    return merged;
  };

  // Pull instead of push: the host may apply options before the editor exists (the user can
  // open the dialog while the file is still loading) and again on every change, so the page
  // must also be able to ask for the current values at construction time.
  var readHostOptions = function() {
    try {
      return mergeOptions(JSON.parse(CodeLoader.getViewOptions()));
    } catch (e) {
      // A missing bridge method / non-JSON payload must not blank the viewer.
      return mergeOptions(null);
    }
  };

  var applyFontSize = function() {
    // Set on the wrapper, not on .CodeMirror-line: the gutter derives its line height from
    // the same font metrics, and a per-line font-size leaves line numbers out of sync even
    // after refresh().
    var wrapper = editor.getWrapperElement();
    wrapper.style.fontSize = viewOptions.fontSizeSp > 0 ? viewOptions.fontSizeSp + 'px' : '';
  };

  var lastEdges = null;

  // Push the horizontal scroll edges to the host: Java uses them to decide whether a horizontal
  // gesture belongs to CodeMirror or to the enclosing ViewPager's page swipe.
  // The direction has to be JS -> Java: evaluateJavascript is asynchronous, so Java cannot ask
  // for the value inside ACTION_MOVE.
  var reportScrollEdges = function() {
    if (!editor) {
      return;
    }
    var info = editor.getScrollInfo();
    // 1px 容差：getScrollInfo() 的值是取整的，贴边时会有 ±1 抖动
    var canScrollLeft = info.left > 1;
    var canScrollRight = info.left + info.clientWidth < info.width - 1;
    if (lastEdges && lastEdges.left === canScrollLeft && lastEdges.right === canScrollRight) {
      return; // 值没变就不跨桥，免得每个 scroll 事件都变成一次 JNI 调用
    }
    lastEdges = { left: canScrollLeft, right: canScrollRight };
    try {
      CodeLoader.onScrollEdges(canScrollLeft, canScrollRight);
    } catch (e) {
      // 宿主没有这个方法（旧版本）时忽略，不影响阅读
    }
  };

  var applyOptions = function() {
    if (!editor) {
      return;
    }
    editor.setOption('lineNumbers', !!viewOptions.lineNumbers);
    editor.setOption('lineWrapping', !!viewOptions.lineWrapping);
    editor.setOption('matchBrackets', !!viewOptions.matchBrackets);
    editor.setOption('tabSize', viewOptions.tabSize > 0 ? viewOptions.tabSize : 4);
    applyFontSize();
    editor.refresh();
    // 换行 / 字号 / Tab 都会改变溢出宽度，边界要重算；refresh() 后的量算放到下一轮定时器再读，
    // 避免拿到 measure 之前的旧宽度（最坏情况只是多报一次，去重会挡掉）。
    setTimeout(reportScrollEdges, 0);
  };

  var displayFileContent = function() {
    var rawCodes = CodeLoader.getCode();
    $('#editor').text(rawCodes);
    var editorElm = document.getElementById("editor");
    var theme = CodeLoader.getTheme();
    viewOptions = readHostOptions();
    var editorOption = {
      lineNumbers: !!viewOptions.lineNumbers,
      mode: lang,
      theme: theme,
      matchBrackets: !!viewOptions.matchBrackets,
      lineWrapping: !!viewOptions.lineWrapping,
      tabSize: viewOptions.tabSize > 0 ? viewOptions.tabSize : 4,
      readOnly: true
    };
    editor = CodeMirror.fromTextArea(editorElm, editorOption);
    // 新编辑器可能和上一个文件的边界恰好相同，重置缓存保证首次一定上报
    lastEdges = null;
    // 触摸滚动 / 编程滚动都会改左边界，只在这里订阅就够（applyOptions 里另有一次主动上报）
    editor.on('scroll', reportScrollEdges);
    applyOptions();
  };

  window.setLang = function(l) {
    lang = l;
    if (editor) {
      editor.setOption("mode", lang);
      setTimeout(reportScrollEdges, 0);
    }
  };

  // Called by the host for both the per-file overrides and the global defaults. Safe to call
  // before display(): the value is kept in viewOptions and then overwritten by
  // readHostOptions() at construction time, which fetches the authoritative copy from the host.
  window.applyViewOptions = function(options) {
    viewOptions = mergeOptions(options);
    applyOptions();
  };

  window.display = displayFileContent;

  // 切回本页时宿主调用：WebView 被 ViewPager 移动过之后合成层可能不再重绘（只剩行号、正文空白），
  // refresh() 会触发重新量算与绘制。
  window.refreshEditor = function() {
    if (editor) {
      editor.refresh();
    }
  };

  // window.setEditable / window.save were removed: the Java-side CodeLoader bridge only
  // exposes getCode / copy_all / loadCode / getTheme - there was never a save(String)
  // method to call, and nothing ever invoked setEditable. The viewer is read-only.

  window.copy_all = function() {
    var value = editor.getValue();
    CodeLoader.copy_all(value);
  }


  $(document).ready(function() {
    CodeLoader.loadCode();
  });

}).call(this);
