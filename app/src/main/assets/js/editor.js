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

  // ------------------------------------------------------------------ 滚动位置指示条
  // Android WebView 不会常显滚动条，也拖不动；这里自绘两条指示条。
  // 位置只从 getScrollInfo() 推算，与 reportScrollEdges 同源 —— 显示的位置永远等于真实位置，
  // 也不参与触摸（CSS 里 pointer-events: none），所以不影响 ViewPager 翻页的判断。
  var SCROLLBAR_SIZE = 4;      // 与 viewer.css 里的粗细保持一致
  var SCROLLBAR_MARGIN = 2;
  var SCROLLBAR_MIN_LENGTH = 20;

  var scrollbarV = null;
  var scrollbarH = null;
  var scrollbarThumbV = null;
  var scrollbarThumbH = null;
  var scrollbarRaf = 0;

  var createScrollbar = function(className) {
    var bar = document.createElement('div');
    bar.className = 'cm-scrollbar ' + className;
    var thumb = document.createElement('div');
    thumb.className = 'cm-scrollbar-thumb';
    bar.appendChild(thumb);
    return { bar: bar, thumb: thumb };
  };

  var ensureScrollbars = function() {
    var wrapper = editor.getWrapperElement();
    // 同一页面里重复 display() 会换掉 wrapper：元素还在但挂到了旧节点上，得重建
    if (scrollbarV && scrollbarV.parentNode === wrapper) {
      return;
    }
    var vert = createScrollbar('cm-scrollbar-v');
    var horiz = createScrollbar('cm-scrollbar-h');
    scrollbarV = vert.bar;
    scrollbarThumbV = vert.thumb;
    scrollbarH = horiz.bar;
    scrollbarThumbH = horiz.thumb;
    wrapper.appendChild(scrollbarV);
    wrapper.appendChild(scrollbarH);
  };

  var setBarVisible = function(bar, visible) {
    var next = visible ? 'block' : 'none';
    if (bar.style.display !== next) {
      bar.style.display = next;
    }
  };

  var updateScrollbars = function() {
    if (!editor || !scrollbarV) {
      return;
    }
    var info = editor.getScrollInfo();
    var viewW = info.clientWidth;
    var viewH = info.clientHeight;
    if (!viewW || !viewH) {
      return;
    }
    var maxLeft = info.width - viewW;
    var maxTop = info.height - viewH;
    var needH = maxLeft > 1;
    var needV = maxTop > 1;

    // 竖向：有横向条时底端让出条宽，两条不在右下角叠在一起
    setBarVisible(scrollbarV, needV);
    if (needV) {
      var trackH = viewH - SCROLLBAR_MARGIN - (needH ? SCROLLBAR_SIZE + SCROLLBAR_MARGIN : 0);
      var thumbH = Math.max(SCROLLBAR_MIN_LENGTH, trackH * viewH / info.height);
      thumbH = Math.min(thumbH, trackH);
      var top = maxTop > 0 ? (info.top / maxTop) * (trackH - thumbH) : 0;
      scrollbarV.style.right = SCROLLBAR_MARGIN + 'px';
      scrollbarV.style.height = trackH + 'px';
      scrollbarThumbV.style.height = thumbH + 'px';
      scrollbarThumbV.style.transform = 'translateY(' + top + 'px)';
    }

    // 横向：行号区固定不横滚，轨道从 gutter 右侧起算
    setBarVisible(scrollbarH, needH);
    if (needH) {
      var gutterW = editor.getGutterElement().offsetWidth || 0;
      var trackW = viewW - gutterW - SCROLLBAR_MARGIN;
      // 行号区固定不横滚：可见宽度与内容宽度都要扣掉它，比例才和真实滚动量一致
      var visibleW = Math.max(1, viewW - gutterW);
      var contentW = Math.max(1, info.width - gutterW);
      var thumbW = Math.max(SCROLLBAR_MIN_LENGTH, trackW * visibleW / contentW);
      thumbW = Math.min(thumbW, trackW);
      var left = maxLeft > 0 ? (info.left / maxLeft) * (trackW - thumbW) : 0;
      scrollbarH.style.left = gutterW + 'px';
      scrollbarH.style.width = trackW + 'px';
      scrollbarH.style.bottom = SCROLLBAR_MARGIN + 'px';
      scrollbarThumbH.style.width = thumbW + 'px';
      scrollbarThumbH.style.transform = 'translateX(' + left + 'px)';
    }
  };

  // 滚动时每帧最多算一次：thumb 位置必须在下一帧前落位，所以用 rAF 而不是 setTimeout
  var requestScrollbarUpdate = function() {
    if (scrollbarRaf) {
      return;
    }
    scrollbarRaf = window.requestAnimationFrame(function() {
      scrollbarRaf = 0;
      updateScrollbars();
    });
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
    // 换行 / 字号 / Tab 都会改变溢出宽度，边界与滚动条要重算：refresh() 后的量算放到下一轮
    // 定时器再读，避免拿到 measure 之前的旧宽度（最坏情况只是多报一次，去重会挡掉）。
    updateScrollbars();
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
    ensureScrollbars();
    // 触摸滚动 / 编程滚动都会改左边界，只在这里订阅就够（applyOptions 里另有一次主动上报）
    editor.on('scroll', function() {
      reportScrollEdges();
      requestScrollbarUpdate();
    });
    applyOptions();
  };

  window.setLang = function(l) {
    lang = l;
    if (editor) {
      editor.setOption("mode", lang);
      // 换模式会改变最长行的宽度：边界和横向条都要重算
      setTimeout(function() {
        reportScrollEdges();
        updateScrollbars();
      }, 0);
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
      // WebView 被 ViewPager 挪回来之后视口尺寸才最终确定，切回页时补一次量算
      updateScrollbars();
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

  // 旋转 / 软键盘 / 分屏都会改变视口，thumb 长度按新视口重算
  window.addEventListener('resize', requestScrollbarUpdate);

}).call(this);
