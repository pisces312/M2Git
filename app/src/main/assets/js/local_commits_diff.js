(function() {
  var colorDiff = function(index) {
    var changeTypeBlock, codeBlock, codes, diffBlock, infoBlock, pathBlock;
    var diff = CodeLoader.getDiff(index);
    if (!diff) {
      return;
    }
    var changeType = CodeLoader.getChangeType(index);
    var path;
    if (changeType === 'ADD') {
      path = CodeLoader.getNewPath(index);
    } else {
      path = CodeLoader.getOldPath(index);
    }
    // hljs 11 signature: highlight(code, {language}); the old 7/8-era pack took
    // (language, code, ignoreIllegals). hljs escapes the untrusted diff text itself -
    // this output is the page's ONLY .html() sink, keep it that way (see rebuild-js-assets.sh).
    var highlighted = hljs.highlight(diff, { language: "diff" });
    var diffContent = highlighted.value.split('\n');
    codes = diffContent.slice(4).join('\n');
    diffBlock = $('<div>', {
      "class": 'diff-block'
    });
    infoBlock = $('<div>', {
      "class": 'info-block'
    });
    pathBlock = $('<div>', {
      "class": 'path-block'
    }).text(path);
    changeTypeBlock = $('<div>', {
      "class": 'change-type'
    }).text(changeType);
    infoBlock.append(changeTypeBlock);
    infoBlock.append(pathBlock);
    // hljs 11 themes style .hljs (the old unprefixed rainbow.css styled bare `pre code`)
    codeBlock = $('<code>', { "class": 'hljs' }).html(codes);
    codeBlock = $('<pre>', {
      "class": "diff"
    }).append(codeBlock);
    diffBlock.append(infoBlock);
    diffBlock.append(codeBlock);
    $('body').append(diffBlock);
  };

  var commitInfo = function() {
    if (!CodeLoader.haveCommitInfo()) {
      return;
    }
    var commitMessage = CodeLoader.getCommitMessage();
    var commitInfo = CodeLoader.getCommitInfo();
    var diffBlock = $('<div>', {
      "class": 'diff-block'
    });
    var infoBlock = $('<div>', {
      "class": 'info-block'
    });
    var commitInfoBlock = $('<pre>', {
      "class": 'commitinfo-block'
    }).text(commitInfo);
    infoBlock.append(commitInfoBlock);
    diffBlock.append(infoBlock);
    var codeBlock = $('<code>').text(commitMessage);
    codeBlock = $('<pre>', {
      "class": "diff"
    }).append(codeBlock);
    diffBlock.append(codeBlock);
    $('body').append(diffBlock);
  };

  // 浅克隆边界：本地没有父提交，算不出 diff（CommitDiffTask 直接返回空结果）。
  // 这不是错误，也不能渲染成「全部文件新增」，否则整仓 diff 会把页面拖死。
  var shallowBoundaryNotice = function() {
    if (!CodeLoader.isShallowBoundary()) {
      return;
    }
    var diffBlock = $('<div>', {
      "class": 'diff-block'
    });
    var noticeBlock = $('<div>', {
      "class": 'info-block shallow-boundary'
    });
    noticeBlock.append($('<div>', {
      "class": 'shallow-boundary-title'
    }).text(CodeLoader.getShallowBoundaryTitle()));
    noticeBlock.append($('<div>', {
      "class": 'shallow-boundary-hint'
    }).text(CodeLoader.getShallowBoundaryHint()));
    diffBlock.append(noticeBlock);
    $('body').append(diffBlock);
  };

  window.notifyEntriesReady = function() {
    commitInfo();
    shallowBoundaryNotice();
    var length = CodeLoader.getDiffSize();
    var results = [];
    var ref = length - 1;
    for (var index = 0; index <= ref; index += 1) {
      results.push(colorDiff(index));
    }
    return results;
  };

  $(document).ready(function() {
    CodeLoader.getDiffEntries();
  });

}).call(this);
