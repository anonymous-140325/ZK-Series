#!/usr/bin/env bash
# Build and run the benchmarks with a plain JDK: in Termux on the phone, or on a desktop.
# Results go to results/, one file per step, each with a provenance and thermal header.
#
#   bash run-on-phone.sh smoke    checks, then a short run of every harness (minutes)
#   bash run-on-phone.sh cases    the case-study rows of Table 6 (blow, shake, face), as the app's CASES RUN
#   bash run-on-phone.sh tables   the plan for Tables 5 and 6, as the app's TABLES RUN (3-4 hours)
#   bash run-on-phone.sh final    the measurement plan for the paper, as the app's FINAL RUN
#                                 (several hours; a cooldown before every step)
#
# In Termux: pkg install openjdk-17, then unzip this folder and run the script from it.
# Environment: COOLDOWN_SECONDS (default 180) between final-run steps.
set -uo pipefail
cd "$(dirname "$0")"
MODE="${1:-smoke}"
COOLDOWN_SECONDS="${COOLDOWN_SECONDS:-180}"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac
CP="build${SEP}SharpRangeProofs-Code/lib/*"
# Timing policy: warm the JIT, then discard the first runs of each cell.
POLICY="-Dbench.burnInMs=15000 -Dbench.discard=5 -Dbench.seed=100001"
[ "$MODE" = smoke ] && POLICY="-Dbench.burnInMs=3000 -Dbench.discard=1 -Dbench.seed=100001"

rm -rf build && mkdir -p build results
find SharpRangeProofs-Code/src bppp-java-zkseries/src bppp-java-unoptimized/src -name "*.java" > build/srcs.txt
javac -nowarn --release 11 -d build -cp "SharpRangeProofs-Code/lib/*" @build/srcs.txt || { echo "BUILD FAILED"; exit 1; }
echo "build ok"

# Thermal state, as far as Termux can read it: CPU frequencies (current, and the current cap,
# which drops when the phone throttles) and the thermal zones.
phone_state() {
  local cur="" max="" z=""
  for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do cur="$cur$(cat "$f" 2>/dev/null || echo unavailable);"; done
  for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_max_freq; do max="$max$(cat "$f" 2>/dev/null || echo unavailable);"; done
  for f in /sys/class/thermal/thermal_zone*/temp; do z="$z$(cat "$f" 2>/dev/null || echo -);"; done
  echo "cpu_cur_khz=${cur%;}, cpu_max_khz=${max%;}, thermal_zones_millic=${z%;}"
}

provenance() {   # provenance <thread cap>
  echo "date      : $(date '+%Y-%m-%d %H:%M:%S')"
  echo "device    : $(getprop ro.product.manufacturer 2>/dev/null) $(getprop ro.product.model 2>/dev/null)"
  echo "android   : $(getprop ro.build.version.release 2>/dev/null) (API $(getprop ro.build.version.sdk 2>/dev/null))"
  echo "kernel    : $(uname -srm)"
  echo "java      : $(java -version 2>&1 | head -1)  (OpenJDK in Termux, not ART)"
  echo "cores     : $(nproc 2>/dev/null)"
  echo "policy    : $POLICY"
  echo "threads   : $1 (BP++ harnesses only)"
  echo "mode      : $MODE"
  echo "PHONE_STATE before: $(phone_state)"
  echo
}

run() {   # run <name> <thread cap> <main class> <args...>
  local name="$1" threads="$2"; shift 2
  local out="results/$(date '+%Y%m%d-%H%M%S')-$name.txt"
  echo "== $name -> $out"
  { provenance "$threads"; java $POLICY -Dbppp.threads="$threads" -cp "$CP" "$@"; echo "PHONE_STATE after: $(phone_state)"; } 2>&1 | tee "$out"
  grep -q "FAILED\|Exception" "$out" && { echo "STOPPED: $name failed, see $out"; exit 1; }
}

cool() { { [ "$MODE" = final ] || [ "$MODE" = tables ] || [ "$MODE" = cases ]; } && { echo "cooling down ${COOLDOWN_SECONDS} s ..."; sleep "$COOLDOWN_SECONDS"; }; }

if [ "$MODE" = cases ]; then
  # Same plan and order as the app's CASES RUN (android/.../Bench.kt, casesPlan).
  for t in 1 8; do run checks-bppp-t$t $t bppp.Check; done
  for t in 1 8; do cool; run cases-t$t $t bppp.AuthBench cases 10; done
  echo "CASES RUN COMPLETE; results in $(pwd)/results"
elif [ "$MODE" = tables ]; then
  # Same plan and order as the app's TABLES RUN (android/.../Bench.kt, tablesPlan).
  GRID="50,100,150,200,250,300"
  for t in 1 8; do run checks-bppp-t$t $t bppp.Check; done
  run checks-bpppref 1 bpppref.Check
  run checks-native 1 experiments.DedicatedRangeProofCheck
  cool; run bulletproofs-native 1 experiments.BulletproofsBench native 20 "$GRID"
  cool; run bppp-unoptimized-range 1 bpppref.AuthBench range 20 "$GRID"
  for t in 1 8; do cool; run bppp-range-t$t $t bppp.AuthBench range 20 "$GRID"; done
  for t in 1 8; do cool; run cases-t$t $t bppp.AuthBench cases 10; done
  for T in 50 97 100 150 200 250 300; do
    for t in 1 8; do cool; run dtw1-T$T-t$t $t bppp.AuthBench dtw1 10 "$T"; done
  done
  echo "TABLES RUN COMPLETE; results in $(pwd)/results"
elif [ "$MODE" = final ]; then
  # Same plan and order as the app's FINAL RUN (android/.../Bench.kt, finalPlan).
  GRID="50,100,150,200,250,300"
  for t in 1 8; do run checks-t$t $t bppp.Check; done
  cool; run bulletproofs 1 experiments.BulletproofsBench 20 "$GRID"
  for t in 1 8; do cool; run bppp-range-t$t $t bppp.AuthBench range 20 "$GRID"; done
  for T in 50 97 100 150 200 250 300; do
    for t in 1 8; do cool; run auth-T$T-t$t $t bppp.AuthBench auth 10 "$T"; done
  done
  echo "FINAL RUN COMPLETE; results in $(pwd)/results"
else
  for t in 1 8; do run checks-t$t $t bppp.Check; done
  run checks-bpppref 1 bpppref.Check
  run checks-native 1 experiments.DedicatedRangeProofCheck
  run bulletproofs 1 experiments.BulletproofsBench 3 50,100
  run bulletproofs-native 1 experiments.BulletproofsBench native 3 50,100
  run bppp-unoptimized-range 1 bpppref.AuthBench range 3 50,100
  run cases-t8 8 bppp.AuthBench cases 2
  run dtw1-t8 8 bppp.AuthBench dtw1 2 50,97
  for t in 1 8; do
    run bppp-range-t$t $t bppp.AuthBench range 3 50,100
    run authentication-t$t $t bppp.AuthBench auth 3 50,97,100
  done
  echo "done; results in $(pwd)/results"
fi
