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
    applyOptions();
  };

  window.setLang = function(l) {
    lang = l;
    if (editor) {
      return editor.setOption("mode", lang);
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
