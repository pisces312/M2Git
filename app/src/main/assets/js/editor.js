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

  window.setEditable = function() {
    editor.setOption("readOnly", false);
  };

  window.save = function() {
    editor.setOption("readOnly", true);
    CodeLoader.save(editor.getValue());
  };

  window.copy_all = function() {
    value = editor.getValue();
    CodeLoader.copy_all(value);
  }


  $(document).ready(function() {
    CodeLoader.loadCode();
  });

}).call(this);
