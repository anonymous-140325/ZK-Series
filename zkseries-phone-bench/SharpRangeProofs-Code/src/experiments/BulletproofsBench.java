package experiments;

import zk.bulletproofs.BpProof;
import zk.bulletproofs.BulletProofGenerators;
import zk.bulletproofs.BulletProofs;
import zk.bulletproofs.PedersenCommitment;
import zk.bulletproofs.R1CSProof;
import zk.bulletproofs.Transcript;
import zk.bulletproofs.Utils;
import zk.bulletproofs.rangeproof.RangeProof;
import zk.bulletproofs.gadgets.NumberInRange;
import zk.bulletproofs.gadgets.NumberInRangeParams;
import zk.bulletproofs.gadgets.NumbersInRange;
import zk.bulletproofs.gadgets.NumbersInRangeParams;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;

/**
 * The Bulletproofs rows of the paper's Table 6: N values, each proven in [0, B] with
 * B = 8000, through Weavechain's range gadget (the arithmetic circuit protocol of
 * the Bulletproofs paper, Section 5).
 *
 *   java -cp "build;lib/*" experiments.BulletproofsBench [reps] [N,N,...]
 *   java -cp "build;lib/*" experiments.BulletproofsBench native [reps] [N,N,...]
 *
 * The second form measures the dedicated range proof instead (see nativeRows).
 *
 * Two rows per N:
 *   BP   one proof per value. A single proof is timed and the N-fold total is
 *        reported as an extrapolation: the proofs are independent and the same size.
 *   BPb  all N values aggregated into one proof (NumbersInRange), timed as a whole.
 *
 * Properties: bench.burnInMs (3000), bench.discard (1), bench.seed (100001).
 * Each cell: median [min-max] in seconds. Sizes are wire bytes, 32 per point and
 * scalar, excluding the N commitments to the proven values (part of the statement).
 */
public class BulletproofsBench {

    static final int B = 8000;
    static final int BITS = 13;                     // ceil(log2(B + 1)): the tightest width covering [0, B]
    static long burnInMs = Long.getLong("bench.burnInMs", 3000L);
    static int discard = Integer.getInteger("bench.discard", 1);
    // Separate generators, so the values measured depend only on the seed, not on how many
    // burn-in iterations ran (which depends on the device's speed).
    static final long SEED = Long.getLong("bench.seed", 100001L);
    // Reseeded at the start of every run (main): the Android app runs several harnesses in one process.
    static Random data, warm;

    public static void main(String[] args) throws Exception {
        data = new Random(SEED); warm = new Random(SEED + 2);
        if (args.length > 0 && args[0].equals("native")) { nativeRows(Arrays.copyOfRange(args, 1, args.length)); return; }
        int reps = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        int[] sizes = args.length > 1 ? Arrays.stream(args[1].split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray()
                                      : new int[] { 50, 100, 150, 200, 250, 300 };
        PedersenCommitment pc = PedersenCommitment.getDefault();
        System.out.println("Bulletproofs baseline, java " + System.getProperty("java.version") + ", reps = " + reps);
        long until = System.currentTimeMillis() + burnInMs;
        while (System.currentTimeMillis() < until) aggregated(pc, 4, warm);
        System.out.println("     N  row  gen (s)                   vrf (s)                   size (kB)");
        for (int n : sizes) {
            long[] gen = new long[reps], vrf = new long[reps];
            long bytes = 0;
            for (int r = -discard; r < reps; r++) {
                long[] t = single(pc, data);
                if (r >= 0) { gen[r] = t[0] * n; vrf[r] = t[1] * n; bytes = t[2] * n; }
            }
            System.out.printf("  %4d  BP   %s  %s  %8.2f   (one proof timed, x N)%n", n, fmt(gen), fmt(vrf), bytes / 1024.0);
            for (int r = -discard; r < reps; r++) {
                long[] t = aggregated(pc, n, data);
                if (r >= 0) { gen[r] = t[0]; vrf[r] = t[1]; bytes = t[2]; }
            }
            System.out.printf("  %4d  BPb  %s  %s  %8.2f%n", n, fmt(gen), fmt(vrf), bytes / 1024.0);
        }
    }

    /** One value, one proof: {gen ns, vrf ns, wire bytes}. */
    static long[] single(PedersenCommitment pc, Random rnd) throws Exception {
        NumberInRange gadget = new NumberInRange();
        NumberInRangeParams params = new NumberInRangeParams(0L, (long) B, BITS);
        // A BulletProofGenerators instance is consumed by proving: prover and verifier each need their own.
        BulletProofGenerators g1 = new BulletProofGenerators(BulletProofs.DEFAULT_GENERATORS_SIZE, 1);
        BulletProofGenerators g2 = new BulletProofGenerators(BulletProofs.DEFAULT_GENERATORS_SIZE, 1);
        BigInteger v = BigInteger.valueOf(rnd.nextInt(B + 1));
        long t0 = System.nanoTime();
        BpProof proof = gadget.generate(v, params, Utils.randomScalar(), pc, g1);
        long t1 = System.nanoTime();
        boolean ok = proof != null && gadget.verify(params, proof, pc, g2);
        long t2 = System.nanoTime();
        if (!ok) throw new IllegalStateException("VERIFICATION FAILED (BP)");
        return new long[] { t1 - t0, t2 - t1, wireSize(proof, 1) };
    }

    /** N values, one aggregated proof: {gen ns, vrf ns, wire bytes}. */
    static long[] aggregated(PedersenCommitment pc, int n, Random rnd) throws Exception {
        NumbersInRange gadget = new NumbersInRange();
        NumbersInRangeParams params = new NumbersInRangeParams(n, 0L, (long) B, BITS);
        BulletProofGenerators g1 = generators(n * 2 * BITS), g2 = generators(n * 2 * BITS);
        BigInteger[] vs = new BigInteger[n];
        for (int j = 0; j < n; j++) vs[j] = BigInteger.valueOf(rnd.nextInt(B + 1));
        long t0 = System.nanoTime();
        BpProof proof = gadget.generate(vs, params, Utils.randomScalar(), pc, g1);
        long t1 = System.nanoTime();
        boolean ok = proof != null && gadget.verify(params, proof, pc, g2);
        long t2 = System.nanoTime();
        if (!ok) throw new IllegalStateException("VERIFICATION FAILED (BPb)");
        return new long[] { t1 - t0, t2 - t1, wireSize(proof, n) };
    }

    /** Generators for a circuit of this many multipliers, padded to a power of two as the inner product argument needs. */
    static BulletProofGenerators generators(int multipliers) throws Exception {
        int cap = 1;
        while (cap < multipliers) cap <<= 1;
        return new BulletProofGenerators(cap, 1);
    }

    /**
     * Wire bytes: 11 points (A_I1, A_O1, S1, A_I2, A_O2, S2, T1, T3, T4, T5, T6), the inner
     * product argument's L and R, 5 scalars, and the gadget's 3 commitments per value
     * minus the n that belong to the statement.
     */
    static long wireSize(BpProof proof, int n) {
        R1CSProof p = proof.getProof();
        int points = 11 + p.getIppProof().getL().size() + p.getIppProof().getR().size() + proof.getCommitments().size() - n;
        return 32L * (points + 5);
    }

    /**
     * Mode "native": the dedicated range proof of Bulletproofs (zk.bulletproofs.rangeproof,
     * after dalek's RangeProof), which is what the Bulletproofs++ paper compares against, for
     * the statement the Bulletproofs++ rows prove: N readings quantized to 16 bits, each value
     * in [0, 2^16), one 16-bit statement per value. Two rows per N:
     *   BPn  one proof per value (m = 1); one timed, the N-fold total reported.
     *   BPa  all N statements in one aggregated proof, padded with zeros to a power of two,
     *        as the protocol requires.
     */
    static void nativeRows(String[] args) throws Exception {
        int reps = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        int[] sizes = args.length > 1 ? Arrays.stream(args[1].split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray()
                                      : new int[] { 50, 100, 150, 200, 250, 300 };
        PedersenCommitment pc = PedersenCommitment.getDefault();
        System.out.println("Bulletproofs dedicated range proof, java " + System.getProperty("java.version") + ", reps = " + reps
                + ", one 16-bit statement per value");
        long until = System.currentTimeMillis() + burnInMs;
        while (System.currentTimeMillis() < until) nativeProof(pc, 4, 4, warm);
        System.out.println("     N  row  m     gen (s)                   vrf (s)                   size (kB)");
        for (int n : sizes) {
            long[] gen = new long[reps], vrf = new long[reps];
            long bytes = 0;
            for (int r = -discard; r < reps; r++) {
                long[] t = nativeProof(pc, 1, 1, data);
                if (r >= 0) { gen[r] = t[0] * n; vrf[r] = t[1] * n; bytes = t[2] * n; }
            }
            System.out.printf("  %4d  BPn  %4d  %s  %s  %8.2f   (one proof timed, x N)%n", n, 1, fmt(gen), fmt(vrf), bytes / 1024.0);
            int m = 1;
            while (m < n) m <<= 1;
            for (int r = -discard; r < reps; r++) {
                long[] t = nativeProof(pc, n, m, data);
                if (r >= 0) { gen[r] = t[0]; vrf[r] = t[1]; bytes = t[2]; }
            }
            System.out.printf("  %4d  BPa  %4d  %s  %s  %8.2f%n", n, m, fmt(gen), fmt(vrf), bytes / 1024.0);
        }
    }

    /** n values in [0, 2^16), zero-padded to m statements: {gen ns, vrf ns, wire bytes}. */
    static long[] nativeProof(PedersenCommitment pc, int n, int m, Random rnd) throws Exception {
        long[] values = new long[m];
        for (int j = 0; j < n; j++) values[j] = rnd.nextInt(1 << 16);
        ec.Scalar[] blindings = new ec.Scalar[m];
        for (int j = 0; j < m; j++) blindings[j] = Utils.randomScalar().reduce();
        BulletProofGenerators gens = new BulletProofGenerators(16, m);      // not consumed by the dedicated proof
        java.util.List<ec.ECPoint> commitments = RangeProof.commitments(pc, values, blindings);
        long t0 = System.nanoTime();
        RangeProof proof = RangeProof.prove(new Transcript(), pc, gens, values, blindings, commitments, 16);
        long t1 = System.nanoTime();
        boolean ok = proof.verify(new Transcript(), pc, gens, commitments, 16);
        long t2 = System.nanoTime();
        if (!ok) throw new IllegalStateException("VERIFICATION FAILED (native)");
        return new long[] { t1 - t0, t2 - t1, proof.wireSize() };
    }

    static String fmt(long[] samples) {
        long[] c = samples.clone();
        Arrays.sort(c);
        return String.format("%8.4f [%.4f-%.4f]", c[c.length / 2] / 1e9, c[0] / 1e9, c[c.length - 1] / 1e9);
    }
}
