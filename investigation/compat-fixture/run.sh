#!/usr/bin/env bash
# Binary/source compatibility fixture runner. See ../README.md.
# Compiles the fixture ONCE against the old (1.16.2) jars, runs the resulting classes
# against both old and new jars, recompiles the shared sources against the new jars,
# and finally compiles each source-compatibility probe against old and new jars.
set -uo pipefail
cd "$(dirname "$0")"

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; *) SEP=':' ;; esac

join_jars() {
  local dir=$1 out="" jar
  for jar in "$dir"/*.jar; do out="${out:+$out$SEP}$jar"; done
  printf '%s' "$out"
}

cp_old=$(join_jars ../libs-old)
cp_new=$(join_jars ../libs-new)

banner() { printf '\n===== %s =====\n' "$1"; }
die() { echo "FATAL: $1" >&2; exit 1; }

banner 'compile fixture against OLD (1.16.2) jars'
rm -rf out-old && mkdir -p out-old
javac --release 8 -nowarn -cp "$cp_old" -d out-old src/*.java src-oldonly/*.java || die 'compile against old jars failed'
echo 'compiled OK'

banner 'run old-compiled classes on OLD jars'
java -cp "out-old$SEP$cp_old" CompatMain old old || die 'CompatMain failed on old jars'
java -cp "out-old$SEP$cp_old" RegistryOverrideMain old || die 'RegistryOverrideMain failed on old jars'

banner 'run old-compiled classes on NEW (prototype) jars'
java -cp "out-old$SEP$cp_new" CompatMain new old || die 'CompatMain (old-compiled) failed on new jars'
java -cp "out-old$SEP$cp_new" RegistryOverrideMain new || die 'RegistryOverrideMain (old-compiled) failed on new jars'

banner 'recompile shared sources against NEW jars and run'
rm -rf out-new && mkdir -p out-new
javac --release 8 -nowarn -cp "$cp_new" -d out-new src/*.java || die 'recompile against new jars failed'
java -cp "out-new$SEP$cp_new" CompatMain new new || die 'CompatMain (new-compiled) failed on new jars'

banner 'source compatibility survey'
for probe in src-probes/*.java src-oldonly/MyRegistry.java; do
  name=$(basename "$probe")
  rm -rf out-probe && mkdir -p out-probe
  if javac --release 8 -nowarn -cp "$cp_old" -d out-probe "$probe" >/dev/null 2>&1; then old_ok=true; else old_ok=false; fi
  new_output=$(javac --release 8 -Xlint:deprecation,-options -cp "$cp_new" -d out-probe "$probe" 2>&1); new_rc=$?
  if [ $new_rc -eq 0 ]; then new_ok=true; else new_ok=false; fi
  echo "PROBE $name: compiles-against-old=$old_ok compiles-against-new=$new_ok"
  if [ -n "$new_output" ]; then
    echo "--- javac output against new jars for $name ---"
    echo "$new_output"
  fi
done

echo
echo 'fixture run complete'
exit 0
