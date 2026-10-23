# Phone results

Raw logs of the benchmark runs on a Samsung Galaxy A71 (Android 13, release build of the app in
`../zkseries-phone-bench/android`), unedited. Every step file starts with the device, the timing
policy and the thread cap, and records the phone's thermal state before and after the step; the
session log lists every step, its cooldown and its outcome.

| folder | run | paper |
|---|---|---|
| `tables-run-2026-10-21` | TABLES RUN (`Bench.tablesPlan`): protocol checks, range proofs (Bulletproofs, unoptimized and optimized Bulletproofs++), case studies, synthetic grid | Table 5, Table 6 synthetic rows |
| `cases-run-2026-10-23` | CASES RUN (`Bench.casesPlan`): protocol checks, the five case-study rows (blow, shake median/maximum, face median/maximum) | Table 6, case-study rows |
| `accuracy-variants.txt` | output of `go test -run TestVariants` in `../AccuracyComputation-Code`: discrete Fréchet and the EDR variant | Section 6 |
| `accuracy-tables.txt` | output of `go test -run TestPaperTables` in `../AccuracyComputation-Code`, for reference | Tables 2-4, Appendix D |
