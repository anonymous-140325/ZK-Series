# ZK-Series benchmarks: everything needed to regenerate Tables 5 and 6

This folder is self-contained: zip it, copy it to the phone, and run one script.
**For the measurements reported in the paper, follow `HOW-TO-RUN.txt`** (the TABLES RUN and the CASES RUN).

```
HOW-TO-RUN.txt           instructions for the phone runs behind Tables 5 and 6
run-on-phone.sh          build, checks, harnesses: `smoke`, `cases`, `tables` or `final` (Termux or desktop)
bppp-java-zkseries/      Bulletproofs++ and the ZK-Series authentication proof (its own README)
bppp-java-unoptimized/   the same Bulletproofs++ without prover optimizations, for Table 5 (its own README)
SharpRangeProofs-Code/   the artifact's Java tree, reduced to what the paper uses
android/                 alternative: an Android app running the same harnesses (Android Studio)
```

Earlier, larger versions of the code, including the Sharp implementation and the extra
Bulletproofs protocols that the paper no longer uses, are not part of this repository.

## Running on the phone (Termux)

1. Install Termux (from F-Droid), open it, and run `pkg install openjdk-17 unzip`.
2. Copy the zip to the phone. Run `termux-setup-storage` once, so that Termux can
   read the Downloads folder, then:
   `cd ~ && unzip ~/storage/downloads/zkseries-phone-bench.zip && cd zkseries-phone-bench`
3. `bash run-on-phone.sh smoke` -- a few minutes; it stops at the first failure.
4. `bash run-on-phone.sh cases` -- the case-study rows of Table 6, as the app's CASES RUN;
   `bash run-on-phone.sh tables` -- the plan for Tables 5 and 6, as the app's TABLES RUN;
   `bash run-on-phone.sh final` -- the measurement plan for the paper, the same as the
   app's FINAL RUN, with a 3-minute cooldown (`COOLDOWN_SECONDS`) before every step.
5. Copy `results/` back: `cp -r results ~/storage/downloads/`.

Before a measurement run: mains power, airplane mode, screen on with Termux in the
foreground (`termux-wake-lock` keeps it from sleeping), phone cooled between runs.
Every result file starts with a provenance block (device, Android version, Java
version, timing policy).

This runs the code on OpenJDK inside Termux, not on Android's own runtime (ART). If
the paper reports these figures, it should say so; the `android/` app below runs the
same code on ART instead.

The same script runs on a desktop with a JDK (11 or later), e.g. to check a change.

## What is in `SharpRangeProofs-Code/src`

| package | what | origin |
|---|---|---|
| `ec` | Ristretto255 behind small interfaces | published artifact |
| `zk.bulletproofs` | Bulletproofs constraint system (R1CS) prover and verifier; `PedersenCommitment`, whose default generators are those of dalek's `PedersenGens::default()` | Weavechain's Java port of dalek, MIT |
| `zk.bulletproofs.gadgets` | `NumberInRange`, `NumbersInRange`: values in [0, B] | Weavechain's bulletproofs-gadgets, MIT, vendored |
| `series` | local and series distances, ported from the Go code | ported; `frechet` added |
| `zk.bulletproofs.rangeproof` | `RangeProof`: the dedicated aggregated range proof of Bulletproofs (Sections 4.1-4.3), after dalek's `RangeProof` | own port; checked by `DedicatedRangeProofCheck` (106 checks) |
| `experiments` | `BulletproofsBench`: the gadget rows, and with `native` the dedicated range proof rows of Table 5; `BulletproofRangeCheck`, `DedicatedRangeProofCheck` | new |

## Running on the desktop

```
cd SharpRangeProofs-Code
find src -name "*.java" > srcs.txt
javac -nowarn --release 11 -d build -cp "lib/*" @srcs.txt
java -cp "build;lib/*" experiments.BulletproofsBench 20 50,100,150,200,250,300
java -cp "build;lib/*" experiments.BulletproofRangeCheck
java -cp "build;lib/*" series.SeriesDistCheck
java -cp "build;lib/*" series.FrechetCheck
```

`BulletproofsBench` prints two rows per N: **BP**, one proof per value (one timed,
the N-fold total extrapolated, since the proofs are independent and the same size),
and **BPb**, all N values aggregated into one proof. Sizes are wire bytes, 32 per
point and scalar, without the N commitments to the proven values.

## Alternative: the Android app

`android/` compiles `SharpRangeProofs-Code/src` and `bppp-java-zkseries/src` in
place, so the app runs the same files as the script above, but on ART. The module has never been
compiled; expect Android Studio to need a first sync.

1. Open `android/` in Android Studio, accept the offer to create the Gradle wrapper.
2. Rebuild and Run. Both build variants are non-debuggable, so either gives valid timings.
3. Tap **CASES RUN** (`Bench.casesPlan`, 4 steps: the case-study rows of Table 6),
   **TABLES RUN** (`Bench.tablesPlan`, 24 steps: Tables 5 and 6, readings quantized to 16 bits) or
   **FINAL RUN**. The latter runs the plan in `Bench.finalPlan` (19 steps: checks at 1 and 8
   threads, the Bulletproofs baseline, Bulletproofs++ ranges and the authentication proof
   per series length T at 1 and 8 threads), with a cooldown before every step: at least
   2 minutes, then until Android's thermal status is "none" and the battery is within
   1 C of its starting temperature, at most 15 minutes.
4. `adb pull /sdcard/Android/data/org.zkseries.bench/files/`

Every result file starts with a provenance block (device, SoC, Android version, heap,
timing policy, thread cap) and records the thermal state before and after its step:
Android's thermal status, the battery temperature, and each core's current frequency
and frequency cap (`scaling_max_freq`, which drops when the phone throttles). The session
log (`CASES-RUN-*.txt`, `TABLES-RUN-*.txt` or `FINAL-RUN-*.txt`) lists every step, its cooldown and its outcome.

## Changes to the published artifact

1. **`SeriesDist.java` was an empty stub.** It is now a port of the Go
   `series_distfuncs.go` (`diagSum`, `dtw`, `erd`, `twed`, `findShortestPath`), checked
   identical to the Go code over 90 combinations by `SeriesDistCheck` together with
   `../AccuracyComputation-Code/seriesdist_check_test.go`. Two Go quirks are kept on purpose: the `d[j][i]`
   index order, and `findShortestPath`'s three `if`s, which can advance twice in one
   step on a three-way tie (the accuracy results use only the distance, not the path).
2. **Discrete Fréchet was added**, in Java and in Go (`../AccuracyComputation-Code/series_frechet.go`), and
   checked against brute force over every coupling (`FrechetCheck`,
   `../AccuracyComputation-Code/frechet_check_test.go`).
3. **The Go timing harness accumulated across T**; fixed in `../AccuracyComputation-Code/experiments_test.go`.
4. **SHA3-512 came from the JCA**, which Android does not reliably offer; it is now
   keccakj's (`zk.bulletproofs.Sha3`).
5. **No Bulletproofs range proof could be built**: `Gadget` had no implementation.
   Weavechain's range gadgets are vendored, with mechanical changes only (names,
   lombok getters, logging).

## Known defects in the vendored Bulletproofs code

These affect the Bulletproofs baseline, not ZK-Series or Bulletproofs++, and are left
as vendored so the baseline is the library as published:

- `Transcript.challengeScalar` does not absorb its label or its output, so two
  challenges drawn back to back are equal: every R1CS proof here has y = z and u = x.
  In the arithmetic circuit protocol y = z is exploitable. This does not change the
  timings.
- `Utils.randomScalar` does not reduce its 255 random bits mod the group order.
