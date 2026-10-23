# bppp-java-unoptimized: the Bulletproofs++ baseline without prover optimizations

The "unoptimized Bulletproofs++" rows of the paper's Table 5. This is the version of
`bppp-java-zkseries` from before its prover optimizations, with two changes:

1. **Packages renamed**, `bppp` to `bpppref` and `crypto` to `cryptoref`, so that both versions
   compile into one Android app and run in the same session.
2. **The last prover-speed deviation removed.** The archived norm argument still kept bold G as
   sigma * G~ (DEVIATION 1 of the optimized version). Here bold G is folded as in the C,
   G' = rho G_0 + gamma G_1 [C 331-336], so the norm argument is a straight line-by-line
   translation of `secp256k1-zkp/src/modules/bppp/bppp_norm_product_impl.h` at `8e1f96c`.

Everything else is unchanged: single-threaded, the library's own Straus and Pippenger, the
digit reciprocals inverted one by one, f^ from the full polynomial norms, and the verifier's
final multiplication with each generator's coefficients unmerged.

**Same proofs.** For the same seeds and statement, this version and `bppp-java-zkseries` produce
byte-identical proofs (checked for N = 1, 50 and 300, three seeds each, by hashing the four
commitments and the norm argument's messages). Its own checks: `bpppref.Check`, 142 (the
optimized version's 144 minus its two thread checks). The changes for 16-bit readings (one range
statement per value when B = 2^16 - 1, and B = 2^16 - 1 allowed) are made in both versions alike.

    java -cp "build;../SharpRangeProofs-Code/lib/*" bpppref.AuthBench range 20 50,100,150,200,250,300
