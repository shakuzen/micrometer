#!/usr/bin/env bash
# Allocation/time harness runner. See ../README.md.
#   ./run.sh old          measure the 1.16.2 baseline
#   ./run.sh main         measure locally built unmodified-main jars
#   ./run.sh new --new    measure prototype jars including new-API-only scenarios
set -euo pipefail
cd "$(dirname "$0")"

tag="${1:?usage: run.sh old|main|new [--new]}"
with_new="${2:-}"

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; *) SEP=':' ;; esac

join_jars() {
  local dir=$1 out="" jar
  for jar in "$dir"/*.jar; do out="${out:+$out$SEP}$jar"; done
  printf '%s' "$out"
}

cp_libs=$(join_jars "../libs-$tag")
out="out-$tag"
rm -rf "$out" && mkdir -p "$out"

sources=(src/*.java)
[ "$with_new" = "--new" ] && sources+=(src-new/AllocBenchNew.java)
javac -nowarn -cp "$cp_libs" -d "$out" "${sources[@]}"

for scenario in cached_sample lifecycle_ltt lifecycle_noltt tags_convert; do
  java -XX:+UseParallelGC -Xms512m -Xmx512m -cp "$out$SEP$cp_libs" AllocBench "$scenario"
done
for scenario in timer_solo lifecycle_solo mixed; do
  java -XX:+UseParallelGC -Xms512m -Xmx512m -cp "$out$SEP$cp_libs" AllocBenchMixed "$scenario"
done
if [ "$with_new" = "--new" ]; then
  for scenario in tags_of_kv tags_convert5 id_gettags; do
    java -XX:+UseParallelGC -Xms512m -Xmx512m -cp "$out$SEP$cp_libs" AllocBenchNew "$scenario"
  done
fi
