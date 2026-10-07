#!/usr/bin/env bash
# 从 Maven Central 官方 jgit jar 剥离 InflaterCache.class，产出 app/libs 使用的 stripped jar。
# InflaterCache/InflaterCompat 由 app 源码提供（见 app/src/main/java/org/eclipse/jgit/{lib,compatible}/），
# 不剥离会与 app 源码 duplicate class 导致编译失败。
# 用法：仓库根目录执行 ./app/libs/make-stripped-jgit-jar.sh <version>
#   例：./app/libs/make-stripped-jgit-jar.sh 7.8.0.202609011348-r
set -euo pipefail

VER="${1:?usage: $0 <jgit-version>  e.g. 7.8.0.202609011348-r}"
DROP="org/eclipse/jgit/lib/InflaterCache.class"
OUT="app/libs/org.eclipse.jgit-${VER}-stripped.jar"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

curl -sL -o "$TMP/jgit.jar" \
  "https://repo1.maven.org/maven2/org/eclipse/jgit/org.eclipse.jgit/${VER}/org.eclipse.jgit-${VER}.jar"

python - "$TMP/jgit.jar" "$OUT" "$DROP" <<'EOF'
import sys, zipfile
src, dst, drop = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    dropped = False
    for item in zin.infolist():
        if item.filename == drop:
            dropped = True
            continue
        zout.writestr(item, zin.read(item.filename))
    if not dropped:
        sys.exit(f"ERROR: {drop} not found in {src} — wrong version or upstream changed")
print("written:", dst)
EOF

unzip -l "$OUT" | grep -q "$DROP" && { echo "ERROR: strip failed"; exit 1; }
echo "OK: $DROP removed"
