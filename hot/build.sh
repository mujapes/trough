#!/bin/sh
# The Java half of Trough: source tree -> classes.dex. One definition, run by tools/hot.sh in
# the VM and by Trough itself on the phone (hot/.../Self.java) from the source it holds.
#   build.sh <src> <out-dir> <android.jar> <d8.jar>      javac and java come from PATH
# POSIX sh: on the phone this is /system/bin/sh with toybox find/grep.
set -eu
SRC=$1 OUT=$2
TMP="${TMPDIR:-/tmp}"   # the JDK defaults java.io.tmpdir to /tmp, which Android lacks
rm -rf "$OUT/obj" "$OUT/dex" "$OUT/jars"
mkdir -p "$OUT/obj" "$OUT/dex" "$OUT/jars"
# Payloads are stored by bare sha256; d8 decides a file is an archive by its extension.
ln -s "$3" "$OUT/jars/android.jar"; ln -s "$4" "$OUT/jars/d8.jar"
AJ=$OUT/jars/android.jar D8=$OUT/jars/d8.jar
find "$SRC" -name '*.java' > "$OUT/sources.txt"
if ! javac -J-Djava.io.tmpdir="$TMP" -nowarn -source 8 -target 8 -bootclasspath "$AJ" \
    -classpath "$AJ" -d "$OUT/obj" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
    grep -v 'warning: \[options\]' "$OUT/javac.log" >&2; echo "javac failed" >&2; exit 1
fi
find "$OUT/obj" -name '*.class' > "$OUT/classes.txt"
java -Djava.io.tmpdir="$TMP" -Xmx384m -cp "$D8" com.android.tools.r8.D8 --output "$OUT/dex" \
    --lib "$AJ" --min-api 28 @"$OUT/classes.txt"
mv "$OUT/dex/classes.dex" "$OUT/classes.dex"
rm -rf "$OUT/obj" "$OUT/dex" "$OUT/jars"
