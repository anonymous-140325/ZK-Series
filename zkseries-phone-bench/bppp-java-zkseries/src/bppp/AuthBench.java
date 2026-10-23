package bppp;

import crypto.Generators;
import crypto.Group;
import ec.ECPoint;
import ec.Scalar;
import series.LocalDist.LocalDistType;
import series.SeriesDist.SeriesDistType;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Timings and sizes for the paper's Table 6, desktop or handset:
 *
 *   java ... bppp.AuthBench range [reps] [N,N,...]   N values, each proven in [0, B]
 *   java ... bppp.AuthBench auth  [reps] [T,T,...]   the authentication proof, m = 3,
 *                                                    for SUM and DTW x d_1, d_2, d_inf,
 *                                                    and the time to find its coupling
 *   java ... bppp.AuthBench dtw1  [reps] [T,T,...]   the same for DTW with d_1 only
 *   java ... bppp.AuthBench cases [reps]             the case studies at their largest
 *                                                    circuits (see cases)
 *
 * Properties: bench.burnInMs (3000), bench.discard (1), bench.seed, auth.m (3), and
 * bppp.threads (1): the thread cap for proof arithmetic, calling thread included.
 * Proofs are byte-identical for every cap; only the time changes.
 * Each cell: median [min-max] in seconds over reps, after discarding the first runs.
 * Before timing, every proof is checked to verify, and in auth mode theta = delta
 * (one too small) is checked to be refused.
 *
 * All randomness, blindings included, comes from one seeded java.util.Random so that
 * runs repeat. That makes these proofs neither hiding nor zero-knowledge: fine for
 * timing, not a pattern to copy. Real use passes a SecureRandom.
 */
public final class AuthBench {

    static final long B = (1 << 16) - 1;                    // readings quantized to 16 bits
    static long burnInMs = Long.getLong("bench.burnInMs", 3000L);
    static int discard = Integer.getInteger("bench.discard", 1);
    static int m = Integer.getInteger("auth.m", 3);
    // Three independent generators, so that the readings and values measured depend only on
    // the seed, not on how many burn-in iterations ran (which depends on the device's speed):
    // data for the readings and values, rnd for blindings and proof randomness, warm for burn-in.
    static final long SEED = Long.getLong("bench.seed", 100001L);
    // Reseeded at the start of every run (main), as the Android app runs several harnesses in
    // one process and static state would otherwise carry over from the previous run.
    static Random data, rnd, warm;

    public static void main(String[] args) {
        data = new Random(SEED); rnd = new Random(SEED + 1); warm = new Random(SEED + 2);
        Group.configureThreads(Integer.getInteger("bppp.threads", 1));               // thread cap, caller included
        String mode = args.length > 0 ? args[0] : "auth";
        int reps = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int[] sizes = args.length > 2 ? Arrays.stream(args[2].split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray()
                                      : new int[] { 50, 100, 150, 200, 250, 300 };
        System.out.println("bppp-java-zkseries, mode " + mode + ", java " + System.getProperty("java.version")
                + ", " + System.getProperty("os.arch") + ", " + Runtime.getRuntime().availableProcessors() + " cores");
        System.out.println("readings in [0, " + B + "], m = " + m + ", reps = " + reps + ", discard = " + discard
                + ", threads = " + Group.threads());
        burnIn();
        System.out.println("     n  row                digits  gates    N_m   prove (s)                 verify (s)                bytes");
        if (mode.equals("cases")) { cases(reps); return; }
        for (int n : sizes) {
            if (mode.equals("range")) rangeRow(n, reps);
            else if (mode.equals("dtw1")) {                                    // the reduced grid: DTW with d_1 only
                long[][] x = walk(n, data), y = near(x, data);
                authRow(n, LocalDistType.MANHATTAN, SeriesDistType.DTW, x, y, reps);
            } else {
                long[][] x = walk(n, data), y = near(x, data);
                for (SeriesDistType s : new SeriesDistType[] { SeriesDistType.SUM, SeriesDistType.DTW })
                    for (LocalDistType d : LocalDistType.values()) authRow(n, d, s, x, y, reps);
            }
        }
    }

    /**
     * Mode "cases": the case studies' best configurations at their largest circuits, i.e. the
     * longest new reading and the longest coupling any base reading can give. The circuit's
     * size depends only on m, the new reading's length and the coupling's length, so these
     * bound the cost of every authentication in the case study.
     *   blow           m = 1, T = 68 (the longest reading after smoothing with s = 4), sum with d_1
     *   shake median   m = 3, new reading T = 50, base reading 97, DTW with d_1, staircase coupling
     *   shake maximum  m = 3, new reading T = 97, base reading 97, DTW with d_1, staircase coupling
     *   face median    m = 8, new reading T = 42, base reading 44, DTW with d_1, staircase coupling
     *   face maximum   m = 8, new reading T = 44, base reading 44, DTW with d_1, staircase coupling
     * The staircase (1,1), (2,1), ..., (|X|,1), (|X|,2), ..., (|X|,|Y|) is a valid warping path of
     * the maximal length |X| + |Y| - 1. The proof bounds the cost of the coupling it reveals
     * (Section 3.2), so any valid coupling gives a valid proof; theta is set just above its cost.
     */
    static void cases(int reps) {
        int saved = m;
        m = 1;
        { long[][] x = walk(68, data), y = near(x, data);   // the longest smoothed blow reading
          caseRow("blow, T=68", LocalDistType.MANHATTAN, SeriesDistType.SUM, x, y, ZkSeriesAuth.coupling(SeriesDistType.SUM, LocalDistType.MANHATTAN, x, y), reps); }
        m = 3;
        { long[][] x = walk(97, data), y = walk(50, data);
          caseRow("shake, T=50", LocalDistType.MANHATTAN, SeriesDistType.DTW, x, y, staircase(97, 50), reps); }
        { long[][] x = walk(97, data), y = walk(97, data);
          caseRow("shake, T=97", LocalDistType.MANHATTAN, SeriesDistType.DTW, x, y, staircase(97, 97), reps); }
        m = 8;                                              // facial landmarks: x and y of 4 mouth landmarks, at most 44 frames
        { long[][] x = walk(44, data), y = walk(42, data);
          caseRow("face, T=42", LocalDistType.MANHATTAN, SeriesDistType.DTW, x, y, staircase(44, 42), reps); }
        { long[][] x = walk(44, data), y = walk(44, data);
          caseRow("face, T=44", LocalDistType.MANHATTAN, SeriesDistType.DTW, x, y, staircase(44, 44), reps); }
        m = saved;
    }

    static int[][] staircase(int tx, int ty) {
        int[][] c = new int[tx + ty - 1][];
        int l = 0;
        for (int i = 0; i < tx; i++) c[l++] = new int[] { i, 0 };
        for (int j = 1; j < ty; j++) c[l++] = new int[] { tx - 1, j };
        return c;
    }

    static void caseRow(String label, LocalDistType d, SeriesDistType s, long[][] x, long[][] y, int[][] c, int reps) {
        long cost = ZkSeriesAuth.cost(new ZkSeriesAuth.Statement(m, x.length, y.length, d, s, B, 1, c), x, y);
        ZkSeriesAuth.Statement st = new ZkSeriesAuth.Statement(m, x.length, y.length, d, s, B, cost + 1, c);
        ZkSeriesAuth.Statement tight = new ZkSeriesAuth.Statement(m, x.length, y.length, d, s, B, cost, c);
        Scalar[] sV = new Scalar[st.inputs()];
        List<ECPoint> V = ZkSeriesAuth.commit(x, y, sV, rnd);
        CircuitBuilder shape = ZkSeriesAuth.builder(st, null, null);
        Generators g = Generators.create(shape.nm());
        boolean refused = false;
        try { ZkSeriesAuth.prove(ZkSeriesAuth.generators(tight), tight, x, y, sV, V, rnd); }
        catch (IllegalArgumentException expected) { refused = true; }
        if (!refused) throw new IllegalStateException("theta = delta was not refused: " + label);
        Timer tm = new Timer(reps);
        BpppProver.Proof pf = null;
        for (int r = -discard; r < reps; r++) {
            long t0 = System.nanoTime();
            pf = ZkSeriesAuth.prove(g, st, x, y, sV, V, rnd);
            long t1 = System.nanoTime();
            boolean ok = ZkSeriesAuth.verify(g, st, V, pf);
            long t2 = System.nanoTime();
            if (!ok) throw new IllegalStateException("VERIFICATION FAILED: " + label);
            tm.add(r, t1 - t0, t2 - t1);
        }
        print(y.length, label + " L=" + c.length, shape, tm, pf.wireSize());
    }

    /** N values in [0, B], one proof. */
    static void rangeRow(int n, int reps) {
        long[] vals = new long[n];
        Scalar[] v = new Scalar[n];
        for (int i = 0; i < n; i++) { vals[i] = data.nextInt((int) B + 1); v[i] = Group.ofUnsigned(vals[i]); }
        Scalar[] s = new Scalar[n];
        List<ECPoint> V = ZkSeriesAuth.commit(new long[][] { vals }, new long[0][], s, rnd);
        CircuitBuilder shape = rangeOnly(null, n);
        Generators g = Generators.create(shape.nm());
        Timer tm = new Timer(reps);
        BpppProver.Proof pf = null;
        for (int r = -discard; r < reps; r++) {
            long t0 = System.nanoTime();
            pf = BpppProver.proveCircuit(g, rangeOnly(v, n), s, V, rnd);
            long t1 = System.nanoTime();
            boolean ok = BpppVerifier.verifyCircuit(g, rangeOnly(null, n), V, pf);
            long t2 = System.nanoTime();
            if (!ok) throw new IllegalStateException("VERIFICATION FAILED, range N = " + n);
            tm.add(r, t1 - t0, t2 - t1);
        }
        print(n, "range", shape, tm, pf.wireSize());
    }

    static CircuitBuilder rangeOnly(Scalar[] vals, int n) {
        CircuitBuilder cb = new CircuitBuilder(n, vals);
        for (int i = 0; i < n; i++) ZkSeriesAuth.inBound(cb, cb.input(i), B);
        return cb;
    }

    /** The authentication proof for one local x series distance, with theta = delta + 1. */
    static void authRow(int T, LocalDistType d, SeriesDistType s, long[][] x, long[][] y, int reps) {
        long[] tc = new long[reps];                                    // finding the coupling, in the clear
        int[][] c = null;
        for (int r = -discard; r < reps; r++) {
            long t0 = System.nanoTime();
            c = ZkSeriesAuth.coupling(s, d, x, y);
            if (r >= 0) tc[r] = System.nanoTime() - t0;
        }
        long cost = ZkSeriesAuth.cost(new ZkSeriesAuth.Statement(m, T, T, d, s, B, 1, c), x, y);
        ZkSeriesAuth.Statement st = new ZkSeriesAuth.Statement(m, T, T, d, s, B, cost + 1, c);
        ZkSeriesAuth.Statement tight = new ZkSeriesAuth.Statement(m, T, T, d, s, B, cost, c);
        Scalar[] sV = new Scalar[st.inputs()];
        List<ECPoint> V = ZkSeriesAuth.commit(x, y, sV, rnd);
        CircuitBuilder shape = ZkSeriesAuth.builder(st, null, null);
        Generators g = Generators.create(shape.nm());
        boolean refused = false;
        try { ZkSeriesAuth.prove(ZkSeriesAuth.generators(tight), tight, x, y, sV, V, rnd); }
        catch (IllegalArgumentException expected) { refused = true; }
        if (!refused) throw new IllegalStateException("theta = delta was not refused at T = " + T);
        Timer tm = new Timer(reps);
        BpppProver.Proof pf = null;
        for (int r = -discard; r < reps; r++) {
            long t0 = System.nanoTime();
            pf = ZkSeriesAuth.prove(g, st, x, y, sV, V, rnd);
            long t1 = System.nanoTime();
            boolean ok = ZkSeriesAuth.verify(g, st, V, pf);
            long t2 = System.nanoTime();
            if (!ok) throw new IllegalStateException("VERIFICATION FAILED at T = " + T + " " + s + " " + d);
            tm.add(r, t1 - t0, t2 - t1);
        }
        print(T, s + " " + d, shape, tm, pf.wireSize());
        long[] c2 = tc.clone(); Arrays.sort(c2);
        System.out.printf("        %-18s coupling in the clear: %.6f s (median)%n", "", c2[c2.length / 2] / 1e9);
    }

    static final class Timer {
        final long[] prove, verify;
        Timer(int reps) { prove = new long[reps]; verify = new long[reps]; }
        void add(int r, long p, long v) { if (r >= 0) { prove[r] = p; verify[r] = v; } }
    }

    static void print(int n, String row, CircuitBuilder shape, Timer tm, int bytes) {
        System.out.printf("  %4d  %-18s %6d  %5d  %5d  %s  %s  %6d%n", n, row, shape.digits(), shape.gates(), shape.nm(),
                fmt(tm.prove), fmt(tm.verify), bytes);
    }

    static String fmt(long[] samples) {
        long[] c = samples.clone();
        Arrays.sort(c);
        return String.format("%8.4f [%.4f-%.4f]", c[c.length / 2] / 1e9, c[0] / 1e9, c[c.length - 1] / 1e9);
    }

    /** A base reading: a random walk in [0, B] per coordinate, as sensor signals move. */
    static long[][] walk(int T, Random rnd) {
        long[][] x = new long[T][m];
        for (int j = 0; j < m; j++) {
            long v = rnd.nextInt((int) B + 1);
            for (int t = 0; t < T; t++) {
                v = Math.max(0, Math.min(B, v + rnd.nextInt((int) (B / 10) + 1) - B / 20));   // steps of up to B/20
                x[t][j] = v;
            }
        }
        return x;
    }

    /** A new reading within B/40 of x per value. */
    static long[][] near(long[][] x, Random rnd) {
        long[][] y = new long[x.length][m];
        for (int t = 0; t < x.length; t++)
            for (int j = 0; j < m; j++) y[t][j] = Math.max(0, Math.min(B, x[t][j] + rnd.nextInt((int) (B / 20) + 1) - B / 40));
        return y;
    }

    static void burnIn() {
        long until = System.currentTimeMillis() + burnInMs;
        while (System.currentTimeMillis() < until) {
            long[][] x = walk(8, warm), y = near(x, warm);
            int[][] c = ZkSeriesAuth.coupling(SeriesDistType.DTW, LocalDistType.EUCLIDEAN, x, y);
            ZkSeriesAuth.Statement st = new ZkSeriesAuth.Statement(m, 8, 8, LocalDistType.EUCLIDEAN, SeriesDistType.DTW, B, 1L << 40, c);
            Scalar[] s = new Scalar[st.inputs()];
            List<ECPoint> V = ZkSeriesAuth.commit(x, y, s, warm);
            Generators g = ZkSeriesAuth.generators(st);
            ZkSeriesAuth.verify(g, st, V, ZkSeriesAuth.prove(g, st, x, y, s, V, warm));
        }
    }
}
