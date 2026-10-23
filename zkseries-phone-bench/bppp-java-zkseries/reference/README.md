# Vetted reference code

Copies, for review, of the files that `src/crypto/NormLinearArgument.java` translates.
They are not compiled or run.

Source: BlockstreamResearch/secp256k1-zkp, commit
`8e1f96c20e16bf960be6caec7f3c94acb360cdf2` (master, September 2026),
`src/modules/bppp/`. MIT license; the copyright notices are in each file.

| file | what | provenance |
|---|---|---|
| `bppp_norm_product_impl.h` | the norm argument: commit, prove, verify | merged in PR #205 (S. Kanjalkar), reviewed and merged by J. Nick, 2023-02-27 |
| `bppp_transcript_impl.h` | the challenge derivation the C uses | same module |
| `bppp_util.h` | helpers (log2, point serialization) | same module |

`NormLinearArgument.java` cites lines of `bppp_norm_product_impl.h` as `[C k]`.
