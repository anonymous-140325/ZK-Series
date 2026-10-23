#!/usr/bin/env bash
# Build this project together with the ZK-Series tree it runs on, then run the checks.
#
#   ./build.sh                     build, run bppp.Check
#   ./build.sh --build             build only
#   ./build.sh --bench range 5 50,100,150,200,250,300
#   ./build.sh --bench auth  5 50,100,150,200,250,300
set -uo pipefail
win() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
HERE="$(win "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)")"
ZK="$(win "$HERE/../SharpRangeProofs-Code")"
CP="$HERE/build;$ZK/lib/*"
rm -rf "$HERE/build" && mkdir -p "$HERE/build"
( find "$HERE/src" -name "*.java"; find "$ZK/src" -name "*.java" ) > "$HERE/build/srcs.txt"
javac -nowarn --release 11 -d "$HERE/build" -cp "$ZK/lib/*" @"$(win "$HERE/build/srcs.txt")" || { echo "BUILD FAILED"; exit 1; }
echo "build ok"
case "${1:-}" in
  --build) exit 0 ;;
  --bench) shift; java -cp "$CP" bppp.AuthBench "$@" ;;
  *)       java -cp "$CP" bppp.Check ;;
esac
