#!/usr/bin/env bash
# Stages the jars the compat fixture and allocation harness run against.
#   ./stage-libs.sh old    downloads the 1.16.2 baseline jars from Maven Central
#   ./stage-libs.sh new    copies the locally built jars (run ./gradlew ... jar first)
#   ./stage-libs.sh main   like new, but into libs-main (build unmodified main first)
set -euo pipefail
cd "$(dirname "$0")"

target="${1:?usage: stage-libs.sh old|new|main}"

case "$target" in
  old)
    mkdir -p libs-old
    for artifact in micrometer-core micrometer-commons micrometer-observation; do
      [ -f "libs-old/$artifact-1.16.2.jar" ] || curl -sfL -o "libs-old/$artifact-1.16.2.jar" \
        "https://repo1.maven.org/maven2/io/micrometer/$artifact/1.16.2/$artifact-1.16.2.jar"
    done
    [ -f libs-old/jspecify-1.0.0.jar ] || curl -sfL -o libs-old/jspecify-1.0.0.jar \
      "https://repo1.maven.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar"
    ls libs-old
    ;;
  new|main)
    ./stage-libs.sh old > /dev/null # for the jspecify jar
    mkdir -p "libs-$target"
    rm -f "libs-$target"/*.jar
    for artifact in micrometer-commons micrometer-core micrometer-observation; do
      # newest jar only, in case build/libs still holds jars of older versions
      jar=$(ls -t ../"$artifact"/build/libs/"$artifact"-*.jar | grep -vE -- '-(sources|javadoc)\.jar$' | head -1)
      [ -n "$jar" ] || { echo "no $artifact jar found; run ./gradlew :$artifact:jar first" >&2; exit 1; }
      cp "$jar" "libs-$target/"
    done
    cp libs-old/jspecify-1.0.0.jar "libs-$target/"
    ls "libs-$target"
    ;;
  *)
    echo "usage: stage-libs.sh old|new|main" >&2
    exit 1
    ;;
esac
