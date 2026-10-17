#!/usr/bin/env bash
# Rebuild the WebView JS/CSS assets from pinned upstream releases.
#
# Artifacts (exact file names, referenced from editor.html and CommitDiffActivity.HTML_TMPL):
#   app/src/main/assets/js/jquery.slim.min.js        jQuery slim (no ajax/effects - pages only use DOM + ready)
#   app/src/main/assets/js/highlight.min.js          hljs common browser bundle (must contain the "diff" language)
#   app/src/main/assets/js/codemirror-bundle.min.js  CodeMirror core + addons + only the modes CodeGuesser can ask for
#   app/src/main/assets/css/codemirror.css           CM base css (includes the "default" theme)
#   app/src/main/assets/css/midnight.css             CM "midnight" theme  } the two entries of R.array.codemirror_theme_names
#   app/src/main/assets/css/hljs-rainbow.css         hljs 11 "rainbow" theme (.hljs-* prefixed selectors)
# A checksum ledger of the six artifacts is written to scripts/js-assets.sha256.
#
# Versions below MUST match app/build.gradle (jqueryVersion / hljsVersion / codeMirrorVersion
# -> BuildConfig -> Settings "About"), same convention as jgitVersion + make-stripped-jgit-jar.sh.
#
# Sources (all pinned; picked for reachability from this box - plain cdnjs TLS-flaked mid-run):
#   npm packages via jsdelivr (byte-stable, straight from the npm dist) for jQuery / hljs;
#   cdnjs for the CodeMirror core lib (jsdelivr's on-the-fly Terser 404s on the ~400 KB core file);
#   jsdelivr auto-minified GitHub tag codemirror/codemirror5@<ver> for addons/modes/css
#   (jsdelivr minifies with Terser and stamps provenance into each file header).
#
# This file is intentionally English-only (project convention for scripts: PS 5.1 GBK
# decoding eats bytes/newlines when bat/ps1/sh contain CJK text).
#
# Usage: run from the repo root:  ./scripts/rebuild-js-assets.sh
set -euo pipefail

JQUERY_VERSION="3.7.1"
HLJS_VERSION="11.11.2"
CM_VERSION="5.65.21"

JS_OUT="app/src/main/assets/js"
CSS_OUT="app/src/main/assets/css"
LEDGER="scripts/js-assets.sha256"

NPM="https://cdn.jsdelivr.net/npm"
GH="https://cdn.jsdelivr.net/gh/codemirror/codemirror5@${CM_VERSION}"
CDNJS="https://cdnjs.cloudflare.com/ajax/libs"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# --ssl-no-revoke: schannel revocation check fails behind proxies on this box
# --retry-all-errors: plain --retry does NOT cover transient TLS failures (curl exit 35,
#   observed on this box mid-run); needs curl >= 7.71
fetch() { # fetch <url> <out-file>
  curl -sfL --ssl-no-revoke --retry 5 --retry-delay 2 --retry-all-errors --max-time 180 -o "$2" "$1"
  echo "fetched $(wc -c < "$2") bytes -> $2"
}

# ---------------------------------------------------------------- jQuery
fetch "$NPM/jquery@${JQUERY_VERSION}/dist/jquery.slim.min.js" "$JS_OUT/jquery.slim.min.js"

# ------------------------------------------------------------ highlight.js
fetch "$NPM/@highlightjs/cdn-assets@${HLJS_VERSION}/highlight.min.js" "$JS_OUT/highlight.min.js"
# local_commits_diff.js is the only consumer; it calls hljs.highlight(diff, {language:"diff"}).
# A bundle without the diff language would silently render diffs unhighlighted.
# In minified hljs 11 builds each grammar is emitted as grmr_<lang>, hence this pattern.
grep -q 'grmr_diff' "$JS_OUT/highlight.min.js" || {
  echo "ERROR: diff language missing from hljs bundle"; exit 1; }
# The hljs 11 rainbow theme uses .hljs-* selectors. The previous css/rainbow.css was a
# pre-9.x unprefixed theme that only matched the ancient hljs 7/8 pack shipped before.
# SECURITY NOTE for future maintainers: local_commits_diff.js feeds UNTRUSTED remote-repo
# content through hljs.highlight() into the page's single .html() sink. hljs escapes the
# code text; never replace that path with a raw .html(diff).
fetch "$NPM/@highlightjs/cdn-assets@${HLJS_VERSION}/styles/rainbow.min.css" "$CSS_OUT/hljs-rainbow.css"

# ------------------------------------------------------------- CodeMirror 5
# Addons actually used by editor.js / required by the modes below:
#   matchbrackets  editorOption matchBrackets:true
#   overlay        required by gfm
#   simple         required by nginx
#   multiplex      required by htmlembedded (jsp/aspx)
CM_ADDONS=(
  addon/edit/matchbrackets.min.js
  addon/mode/overlay.min.js
  addon/mode/simple.min.js
  addon/mode/multiplex.min.js
)
# Mode list = every mode reachable from utils/CodeGuesser.java FILENAME_EXTENSION_ARRAY
# (plus the display-only tags jinja2/gfm). Dependency-sensitive order: base modes
# (xml, clike, javascript, css, htmlmixed, markdown, ruby) must be concatenated before
# the modes that build on them (dtd, jinja2, htmlembedded, php, haml, gfm, dart, sass).
CM_MODES=(
  xml clike javascript css htmlmixed markdown ruby
  apl asterisk clojure cobol coffeescript commonlisp d dart diff dtd ecl
  eiffel erlang fortran gas go groovy gfm haml haskell htmlembedded
  jinja2 livescript lua mllike nginx octave pascal perl php pig properties
  pug python r rust sass scheme shell smalltalk sql stex vbscript yaml
)

BUNDLE="$JS_OUT/codemirror-bundle.min.js"
{
  echo "/* CodeMirror $CM_VERSION bundle for M2Git."
  echo " * Rebuilt by scripts/rebuild-js-assets.sh - do not edit by hand."
  echo " * core from cdnjs codemirror/$CM_VERSION; addons+modes minified by jsDelivr (Terser)"
  echo " * from github tag codemirror/codemirror5@$CM_VERSION."
  echo " * addons: ${CM_ADDONS[*]}"
  echo " * modes:  ${CM_MODES[*]}"
  echo " */"
} > "$TMP/bundle.js"
append_bundle_part() { # append_bundle_part <part-file>
  cat "$1" >> "$TMP/bundle.js"
  # jsDelivr parts end with a //# sourceMappingURL comment and may have no final
  # newline. Always terminate that comment and separate adjacent minified programs.
  printf '\n;\n' >> "$TMP/bundle.js"
}

fetch "$CDNJS/codemirror/$CM_VERSION/codemirror.min.js" "$TMP/part.js"
append_bundle_part "$TMP/part.js"
for a in "${CM_ADDONS[@]}"; do
  fetch "$GH/$a" "$TMP/part.js"
  append_bundle_part "$TMP/part.js"
done
for m in "${CM_MODES[@]}"; do
  fetch "$GH/mode/$m/$m.min.js" "$TMP/part.js"
  append_bundle_part "$TMP/part.js"
done
command -v node >/dev/null 2>&1 || {
  echo "ERROR: node is required to validate the generated CodeMirror bundle"; exit 1; }
node --check "$TMP/bundle.js" >/dev/null || {
  echo "ERROR: generated CodeMirror bundle is not valid JavaScript"; exit 1; }
mv "$TMP/bundle.js" "$BUNDLE"
echo "bundle: $(wc -c < "$BUNDLE") bytes (was 886,334 for the 2016 all-modes pack)"

fetch "$GH/lib/codemirror.css" "$CSS_OUT/codemirror.css"
fetch "$GH/theme/midnight.css" "$CSS_OUT/midnight.css"

# ---------------------------------------------------------------- verify
# Every MIME CodeGuesser can emit (guessCodeType return values + display-only tags)
# must have a defineMIME in the bundle, otherwise that file type silently degrades
# to plain text. Known pre-existing exceptions (no CM mode ever existed / registers
# a different MIME) are listed in MIME_KNOWN_GAPS and reported as INFO, not WARN.
MIMES=(
  text/apl text/x-asterisk text/x-csrc text/x-c++src text/x-csharp
  application/x-csh text/x-dart text/x-java application/x-msclip text/x-clojure
  text/x-cobol text/x-coffeescript text/x-common-lisp text/css text/x-scss
  text/x-sass text/x-x-less text/x-d text/x-diff application/xml-dtd
  text/x-ecl text/x-eiffel text/x-erlang text/x-Fortran text/x-gas
  text/x-go text/x-groovy text/x-haml text/x-haskell text/x-aspx
  text/x-jsp text/html text/x-jade text/javascript jinja2
  text/x-livescript text/x-lua text/x-markdown gfm text/nginx
  text/x-ocaml text/x-octave text/x-pascal application/x-httpd-php text/x-pig
  text/x-perl text/x-ini text/x-properties text/x-python text/x-rsrc
  text/x-ruby text/x-rustsrc text/x-scala text/x-scheme text/x-sh
  text/x-stsrc text/x-sql image/svg+xml text/x-stex text/typescript
  text/vbscript application/xml text/x-kotlin text/x-yaml
)
# Known gaps - verified byte-for-byte against the OLD 2016 bundle (codemirror-compressed.js):
# every MIME below was already unresolvable there, i.e. these files rendered as plain text
# before this rebuild too. They are CodeGuesser-side tag typos / legacy MIMEs, not bundle
# regressions. Fixing them means changing CodeGuesser (behavior change), tracked separately.
#   image/svg+xml      xml.js never registered it
#   application/x-csh  shell.js registers text/x-sh + application/x-sh only
#   application/x-msclip  no CLIPS mode exists in CodeMirror
#   text/x-dart        dart.js does not register this MIME (mode name is plain "dart")
#   text/x-x-less      css.js registers text/x-less (CodeGuesser has a doubled x-)
#   text/x-Fortran     fortran.js registers lowercase text/x-fortran (lookup is case-sensitive)
#   text/x-aspx        htmlembedded.js registers application/x-aspx
#   text/x-jsp         htmlembedded.js registers application/x-jsp
#   text/nginx         nginx.js registers text/x-nginx-conf
MIME_KNOWN_GAPS="image/svg+xml application/x-csh application/x-msclip
text/x-dart text/x-x-less text/x-Fortran text/x-aspx text/x-jsp text/nginx"

rc=0
is_known_gap() { # unquoted expansion collapses the multi-line list to words
  local g
  for g in $MIME_KNOWN_GAPS; do [ "$g" = "$1" ] && return 0; done
  return 1
}
for mime in "${MIMES[@]}"; do
  if grep -q "\"$mime\"" "$BUNDLE"; then continue; fi
  if is_known_gap "$mime"; then
    echo "INFO: $mime has no mode (known pre-existing gap)"
  else
    echo "WARN: $mime NOT resolvable by the new bundle"; rc=1
  fi
done

# ---------------------------------------------------------------- ledger
{
  echo "# checksums of rebuilt WebView assets - $JQUERY_VERSION / $HLJS_VERSION / $CM_VERSION"
  echo "# regenerate with: ./scripts/rebuild-js-assets.sh"
  sha256sum "$JS_OUT/jquery.slim.min.js" "$JS_OUT/highlight.min.js" "$BUNDLE" \
            "$CSS_OUT/codemirror.css" "$CSS_OUT/midnight.css" "$CSS_OUT/hljs-rainbow.css"
} > "$LEDGER"
echo "ledger written: $LEDGER"

echo
if [ "$rc" -eq 0 ]; then
  echo "OK: all CodeGuesser MIMEs covered (except known gaps)."
  echo "Remember: keep versions in sync with app/build.gradle, and do not delete"
  echo "files referenced from editor.html / CommitDiffActivity.HTML_TMPL."
else
  echo "FAILED: unresolved MIMEs above - fix CM_MODES or CodeGuesser before shipping."
  exit 1
fi
