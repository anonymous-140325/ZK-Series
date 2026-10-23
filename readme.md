A repository for the code and data for the research presented in "ZK-Series: Verifiable Credentials from Behavioral Biometric Data".

All Personally Identifiable Information from the participants has been removed.

# Contents

| folder | contents | paper |
|---|---|---|
| `BlowAcoustics-Dataset` | 1st case study: 50 participants, 10 readings each (5 sessions, sitting and standing), audio amplitude at 50 Hz | Section 5, Appendix C.1 |
| `ShakeDynamics-Dataset` | 2nd case study: 20 participants, 10 readings each, the four motion sensors of the phone | Section 5, Appendix C.2 |
| `FacialLandmark-Dataset` | 3rd case study: 20 participants, 10 to 12 readings each, MediaPipe face landmarks relative to the first frame | Section 5, Appendix C.3 |
| `AccuracyComputation-Code` | Go code for the accuracy results: distance functions, thresholds, FAR and FRR | Section 6, Appendix D |
| `zkseries-phone-bench` | Java code for the zero-knowledge proofs (Bulletproofs++ and Bulletproofs) and the Android benchmark app | Section 7, Appendix B |
| `results` | the raw logs of the phone runs behind the efficiency results | Tables 5 and 6 |

# Reproducing the results

**Accuracy** (Section 6 and Appendix D). With Go 1.20 or later:

    cd AccuracyComputation-Code
    go test -run TestPaperTables -timeout 240m .

This writes every accuracy table of the paper to `paper-tables.txt` (see `AccuracyComputation-Code/README.md`).

**Efficiency** (Section 7). See `zkseries-phone-bench/README.md` and `zkseries-phone-bench/HOW-TO-RUN.txt`: the folder is self-contained, can be zipped and run on a phone with Android Studio (or Termux), and contains the checks for the protocol implementations. The logs of the runs reported in the paper are in `results`.

# Implementation Details

The authentication proof of Section 3.2 consists of index pair sequences (the selected base and new readings, and the coupling), a zero-knowledge range proof that the authentication distance is below the threshold, and zero-knowledge proofs of the correct computation of the local distances, step costs, and series distance. We instantiate it as a single Bulletproofs++ arithmetic circuit proof over Ristretto255, in which range statements are proven as base-256 digits against one lookup table and multiplications are circuit gates (Section 7 of the paper). Its norm linear argument is a line-by-line translation of the implementation merged into secp256k1-zkp; the remaining protocols follow the protocol boxes of the Bulletproofs++ paper, and each line of the code cites the line of the C code or of the protocol box it implements. `zkseries-phone-bench/bppp-java-unoptimized` contains the same implementation without the prover optimizations, which produces identical proofs. For comparison, `zkseries-phone-bench/SharpRangeProofs-Code` contains the Java implementation of Bulletproofs by Weavechain, which we reuse under the [MIT license](https://opensource.org/license/mit) ([source](https://github.com/weavechain/bulletproofs)), and a port of the dedicated aggregated range proof of Bulletproofs after the Rust implementation of dalek-cryptography.

# Ethics 

For each case study, we have ensured that no information about the participants, including their names, has been shared or entered long-term storage. Furthermore, the ordering in the dataset is random and the datasets do not have complete overlap, which makes it impossible to link participants across studies. Finally, for all case studies we have focused on the knowledge-based component, which can all be reset.

In the following, we discuss for each case study what the potential leakage of Personally Identifiable Information (PII) is and what steps were taken to mitigate these risks. 

- _Blow acoustics._ The frequency for the macro patterns in the blow acoustics (50 Hz) is too low to obtain characteristic voice information (which typically requires between 300 and 4000 Hz). As mentioned in the paper, the frequency spectrograms can also be extracted from the audio samples, but these are not shared due to the potential leakage of PII. 
    
- _Shake dynamics._ The data collection period for shake gestures is too short (i.e., several seconds) to reveal personally identifiable information (e.g., gait).
    
- _Facial gestures._ Regarding the facial landmark data, we only shared the results after pre-processing: in particular, from each data point we subtract the first element in the time series. This removes the initial relationship between the points in the face mesh, which could in theory be used to re-construct (parts of) the face.

