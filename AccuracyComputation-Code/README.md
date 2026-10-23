# AccuracyComputation-Code

The Go code behind the accuracy results of the paper (Section 6 and Appendix D). It reads the
datasets from the folders next to it (`../BlowAcoustics-Dataset`, `../ShakeDynamics-Dataset`,
`../FacialLandmark-Dataset`).

    go test -run TestPaperTables -timeout 240m .                # all tables, about 40 minutes
    go test -run 'TestPaperTables/shake' -timeout 60m .         # one case study
    go test -run TestVariants -timeout 240m .                   # discrete Fréchet and the EDR variant, about 30 minutes

`TestPaperTables` writes `paper-tables.txt`: for each case study, every series distance (sum, DTW,
TWED) and local distance ($d_1$, $d_2$, $d_\infty$), FAR / FRR for q = 0, 1, 2, with k = 1 and k = 3.

| file | contents |
|---|---|
| `local_distfuncs.go`, `series_distfuncs.go`, `series_frechet.go` | local distances; sum, DTW, EDR variant, TWED, discrete Fréchet |
| `util.go` | loading, normalization, smoothing |
| `experiments_test.go` | thresholds (`determineThresholds`), FAR/FRR (`compareData`, `processExperiment1`), and the per-case-study loaders |
| `paper_tables_test.go` | regenerates the tables of the paper |
| `variants_test.go` | discrete Fréchet and the EDR variant (eps scanned as 0.1, 0.25, 0.5, 1 times the median local distance), k = 1, q = 0, 1, 2; writes `variants.txt` (Section 6) |
| `seriesdist_check_test.go`, `frechet_check_test.go` | checks of the distance functions (against the Java port, and against brute force) |

**Preprocessing**, as in Appendix C: blow readings are divided by their standard deviation and
smoothed (window 8, step 4); shake readings use the orientation sensor as recorded (scaled by
10^8); facial landmark readings use the x and y coordinates of the four mouth landmarks
(`faceLandmarks`), relative to the first frame.

**Scoring.** For each user, the threshold is set so that q of the user's own readings, each
compared with the user's other readings, are rejected. Every reading of every other user is then
an impostor attempt against the user's base readings, accepted if its k-th nearest base reading is
within the user's threshold. An earlier version of `compareData` scored an impostor attempt by its
(k+1)-th nearest base reading and with the impostor's own threshold, which underestimated the FAR;
this is corrected here, and the paper reports the corrected results.

Some older tests (e.g. `TestExperiment1Motion`, `TestMotionData`) use the public MotionSense
dataset, which is not included; run the tests with `-run` as above.
