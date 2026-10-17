(function() {
  var lang = void 0;

  var editor = void 0;

  var displayFileContent = function() {
    var rawCodes = CodeLoader.getCode();
    $('#editor').text(rawCodes);
    var editorElm = document.getElementById("editor");
    var theme = CodeLoader.getTheme();
    var editorOption = {
      lineNumbers: true,
      mode: lang,
      theme: theme,
      matchBrackets: true,
      lineWrapping: true,
      readOnly: true
    };
    editor = CodeMirror.fromTextArea(editorElm, editorOption);
    editor.refresh();
  };

  window.setLang = function(l) {
    lang = l;
    if (editor) {
      return editor.setOption("mode", lang);
    }
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
