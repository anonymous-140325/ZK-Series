# Bulletproofs++ for ZK-Series

The arithmetic circuit protocol of Section 5.3 of Eagen, Kanjalkar, Ruffing and
Nick, *Bulletproofs++* (ePrint 2022/510, revision of 17 July 2023), inside the
Reciprocal Form Protocol of Section 6.3, and the ZK-Series authentication proof
built on it. It runs over the commitments ZK-Series publishes, `V = v·B + s·B_blind`
on Ristretto255, and it is the only Bulletproofs++ code the paper uses: Table 6's
Bulletproofs++ range rows and authentication rows both come from it.

```
./build.sh                                   build, run the checks (bppp.Check)
./build.sh --bench range 5 50,100,150,200,250,300    Table 6, Bulletproofs++ range rows
./build.sh --bench auth  5 50,100,150,200,250,300    Table 6, authentication rows
```

It compiles against `../SharpRangeProofs-Code` (the `ec`
interfaces, `PedersenCommitment`, `series.*`).

## Provenance: what rests on vetted code

| component | source | vetting |
|---|---|---|
| Ristretto255 group and scalar arithmetic | `curve25519-elisabeth` jar (Weavechain's fork of cryptography-cafe/curve25519-elisabeth, a Java port of curve25519-dalek) | library; the fork's multiscalar multiplication (`MulUtils`) is Weavechain's addition |
| hash to the group (`Generators`) | the library's `fromUniformBytes`, RFC 9496's one-way map on 64 bytes of SHA-512 | standard (RFC 9496) |
| Pedersen generators B, B_blind | `PedersenCommitment.getDefault()`: same construction as dalek's `PedersenGens::default()` (basepoint; SHA3-512 of the compressed basepoint, hashed to the group) | dalek bulletproofs, via Weavechain's port |
| **norm linear argument** (`NormLinearArgument`) | **line-by-line translation** of `secp256k1-zkp/src/modules/bppp/bppp_norm_product_impl.h` at `8e1f96c`, merged in PR #205; copy in `reference/`, cited as `[C k]` | **merged, reviewed code by the Bulletproofs++ authors**; three marked prover-speed deviations (`DEVIATION 1-3` in the class comment): a common scalar factor on bold G, the fold arithmetic of `PublicPointFolding`, and X computed alongside R. None changes a proof byte (see Checks) |
| multiscalar multiplication (`Group.msm`) | the library's own Straus and Pippenger (`MulUtils`), called directly with the library's threshold | library |
| Pippenger windows on several threads (`com.weavechain.curve25519.PippengerWindows`) | the library's Pippenger split into its independent windows; each step matches curve25519-dalek's Pippenger (documented in the class) | own, ~25 lines of arithmetic; result identical |
| generator folds (`com.weavechain.curve25519.PublicPointFolding`) | P_0 + k P_1 by a width-5 NAF of the public k, recoded once per round, using the library's lookup tables | own, ~60 lines; public data only; result identical |
| thread budget (`ParallelTasks`, `ParallelMsm`) | a bounded pool; the caller computes when no worker is free | own; single-threaded unless `Group.configureThreads` is called |
| arithmetic circuit and reciprocal form protocols (`BpppProver`, `BpppVerifier`, `CircuitBuilder`) | written from the protocol boxes of Sections 5.3 and 6.3 of the paper, cited line by line | peer-reviewed paper (EUROCRYPT 2024); no vetted implementation exists (see below) |
| transcript (`Transcript`) | own: labeled, length-prefixed SHA-512 with wide reduction | standard construction, ~70 lines |
| authentication circuit (`ZkSeriesAuth`) | own | this paper |

**No vetted implementation of the arithmetic circuit protocol exists.** The authors' C
range proof (secp256k1-zkp PR #207) is unmerged and specialized to range proofs, with
the circuit protocol folded in. The Rust crate `distributed-lab/bp-pp` (MIT, 26 stars,
unaudited) implements the general protocol, including δ, but in a re-derived
arrangement (9-entry blinding vectors, C(T) with −δC_O and −T²C_R, inputs at H_9) that
does not follow the paper's box, so it cannot serve as a line-by-line reference.

## Reading order

| file | lines | what |
|---|---|---|
| `crypto/Group.java` | ~190 | field and group helpers; `msm`, `foldPublicPairs`, `configureThreads` |
| `crypto/Transcript.java` | ~70 | Fiat-Shamir: labeled, length-prefixed, SHA-512 |
| `crypto/Generators.java` | ~80 | G = B, H_0 = B_blind, the rest hashed to the group |
| `crypto/Poly.java` | ~130 | Laurent polynomials in T |
| `crypto/NormLinearArgument.java` | ~280 | the norm linear argument, translated from the merged C |
| `crypto/ParallelTasks.java`, `crypto/ParallelMsm.java` | ~120 | the shared thread budget |
| `com/weavechain/curve25519/PippengerWindows.java` | ~60 | Pippenger, window by window |
| `com/weavechain/curve25519/PublicPointFolding.java` | ~130 | generator folds by NAF |
| `bppp/BpppCircuit.java` | ~110 | the relation of Section 5, and the convention the box proves |
| `bppp/BpppProver.java` | ~355 | Section 5.3 and Section 6.3, line by line |
| `bppp/BpppVerifier.java` | ~60 | the verifier, one multiscalar multiplication |
| `bppp/CircuitBuilder.java` | ~300 | ranges (base 256), products and linear rows, compiled to Section 6.3 |
| `bppp/ZkSeriesAuth.java` | ~450 | the authentication proof of the ZK-Series paper |
| `bppp/AuthBench.java` | ~185 | the Table 6 harness |
| `bppp/Check.java` | ~300 | completeness, soundness, tampering and thread checks |

About 2,550 lines in all. The two classes in `com.weavechain.curve25519` sit in the
library's package because they use its package-private arithmetic; they are therefore
tied to the bundled `curve25519-elisabeth-0.1.5.jar`.

## Threads

`Group.configureThreads(cap)` sets a thread cap for proof arithmetic (calling thread
included; default 1); the harnesses read it from `-Dbppp.threads`. It parallelizes the
windows of large multiscalar multiplications, the norm argument's X alongside R, and the
generator folds. **Proofs are byte-identical for every cap**: the same group elements
are computed in a different order.

## Timing side channels

The prover is not constant time. Its multiscalar multiplications use the library's
variable-time Straus and Pippenger on witness-dependent scalars (commitments C_L, C_O,
C_R, C_S), as before. The digit reciprocals 1/(alpha + d) are batch-inverted from the
digits rather than looked up in a table indexed by the digit. The C reference states
constant-time prover operations on secret data; this implementation does not.

## Line references

Every protocol line in `BpppProver` cites the box line it implements:
`[5.3 OL k]` and `[5.3 R k]` (the `CommitOL` and `CommitR` boxes, lines counted
top to bottom), `[5.3 IAC s.k]` (Inner Arithmetic Circuit Protocol, step s, line k
of the step), `[6.3 k]` (Reciprocal Form Protocol, step k). The numbering is spelled
out in the class comment. `NormLinearArgument` cites the C it translates as `[C k]`,
and Section 4.3's box and Eqs. (19)–(22) where they meet. `CircuitBuilder` cites Eqs. (67), (74) and (75).

## One circuit shape

Every input is one scalar (N_v = 1) entering a linear row (f_l = 1, f_m = 0), and
the layout F places every w_O entry in n_O. Under this shape the l-parts that the
box routes through F (l_O, l_L, l_R, c_{l,X}, and so c^_l(T)) are zero. Those box
lines are kept in the code with the note "zero here", so that every line has its
place.

## Where the printed boxes are wrong or silent

1. **The relation the box proves** (`BpppCircuit`). Expanding the value term with the
   box's definitions shows that it proves `W_l w − f_l w_V − a_l/2 = 0` and
   `w_L∘w_R = −W_m w + f_m w_V + a_m/2`, not Eqs. (30)/(31) as printed. This is an
   invertible change of encoding; circuits here are encoded in the box's convention.
2. **The sign of ĉ_r,0** (`BpppProver`, class comment). The norm relation closes only
   with ĉ_r,0 = −1 rather than the printed 1. ĉ_r,0 never reaches the verifier, so only
   the prover's r_S changes. With the printed signs every honest proof fails; the C
   reference implementation makes the same correction.
3. **Lengths in the norm argument** (`NormLinearArgument`, class comment). The box
   recurses while len(l) + len(n) ≥ 6; the merged C instead runs max(log₂|n|, log₂|l|)
   rounds, and this code follows the C. If n reached length 1 before l, folding l
   alone would let a prover absorb any defect that is μ times a square; the code
   therefore requires |n| ≥ |l| (here |n| = N_m ≥ 256, |l| = 8), under which this
   cannot happen.

## Checks

`bppp.Check`, 314 checks (pass with `-Dbppp.threads=1` and `8`): the norm argument for
|n| = 8..64, and |n| < |l| refused; a norm argument with |n| = 2048, large enough for every
parallel path, giving the same proof with 1 and 8 threads; ranges at both edges,
refused and rejected above B; the |·|, upper-bound, ERD-bit and product gadgets against
false claims; each proof element tampered in turn; the authentication proof for d_1, d_2,
d_∞ with the sum, DTW, discrete Fréchet, ERD and TWED at m = 1 and 3 (the coupling's cost
equals `SeriesDist`'s distance; verifies for θ = δ + 1, refused and rejected for θ = δ,
rejected for another reading, invalid couplings refused); and k = 2 of three base readings
with DTW and Fréchet under the k-NN sum and max (repeated pairs and pairs outside the
readings refused).

Mutations that the checks catch: undoing the sign correction of item 2, removing δ
from p_n(T), dropping the threshold range from the authentication circuit, dropping σ
from R or not updating it (DEVIATION 1), a wrong coefficient in the expanded f^, a dropped
doubling in the generator fold, a wrong window shift in the threaded Pippenger (caught only
by the |n| = 2048 check), and an off-by-one in the batch inversion.

Equivalence: proofs for 24 statements (T = 5, 20, 50, 97; sum and DTW; d_1, d_2, d_inf)
are byte-identical to those of the single-threaded version without the speed changes,
with 1 and with 8 threads.
