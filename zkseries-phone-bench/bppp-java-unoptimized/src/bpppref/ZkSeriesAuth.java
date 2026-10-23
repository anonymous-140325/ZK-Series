package bpppref;

import cryptoref.Generators;
import cryptoref.Group;
import ec.ECPoint;
import ec.Scalar;
import series.LocalDist;
import series.LocalDist.LocalDistType;
import series.SeriesDist.SeriesDistType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The ZK-Series authentication proof (Section 3.2 of the ZK-Series paper) as one
 * Bulletproofs++ reciprocal-form circuit.
 *
 * Statement: base readings X_1..X_n and new readings Y_1..Y_n' (each T elements of
 * dimension m), committed value by value as V = v B + s B_blind; k public, pairwise
 * distinct pairs (g, h) with a public coupling C_(g,h) = ((i_1, j_1), ..., (i_L, j_L))
 * each; a public threshold theta. The proof shows
 *
 *   f*( f_delta(a_1..a_L) over C_(g_1,h_1), ..., f_delta(a_1..a_L) over C_(g_k,h_k) )  <  theta,
 *
 * with f* the sum or the maximum (k-NN sum, k-NN max), the step costs a_l and total
 * function f_delta of the series distance (sum, DTW, discrete Frechet, the EDR variant
 * ERD, or TWED), and the local distance d = d_1 (Manhattan), d_2 (squared Euclidean) or
 * d_inf (Chebyshev). Every value of every Y is proven in [0, B]; the X were committed
 * at registration and are not re-ranged.
 *
 * <b>Why a bound suffices</b> (the paper's "Upper Bounds Suffice"): the series distance
 * is a minimum over couplings, so the cost of one valid coupling below theta implies
 * delta* < theta. The proof reveals the couplings, as item 4 of the paper's proof
 * structure does, and the verifier checks that they are valid for the series distance.
 * For the same reason, every gadget below only needs to bound its output from above:
 * every later step (sum, maximum, the EDR threshold, f*) is non-decreasing, so a
 * prover who overstates a value only makes its own statement harder to prove.
 *
 * <b>The pieces</b>, each in CircuitBuilder terms (base 256):
 * <ul>
 * <li>v in [0, B]: v and v + (2^16 - 1 - B) in [0, 2^16); 4 digits.</li>
 * <li>d_2: (x - y)^2 as one product per coordinate.</li>
 * <li>|x - y|: a new wire a with a in [0, 2^16) and (a - d)(a + d) = 0, so a = +-d and
 *     the range rules out -d (|d| <= B < 2^16); 2 digits, 1 gate.</li>
 * <li>d_inf, an upper bound z on max(a_1..a_m): z - a_j in [0, 2^16) for all j; 2 digits
 *     per coordinate, no gates.</li>
 * <li>ERD step, eps * b for a bit b: b (1 - b) = 0 and eps - d + b M in [0, 256^r), with
 *     M the largest local distance; b = 0 forces d <= eps. 1 gate, r digits.</li>
 * <li>TWED step: its direction is public, so it is lambda + d(x_i, x_i-1), lambda +
 *     d(y_j, y_j-1), or d(x_i, y_j) + d(x_i-1, y_j-1), all local distances as above.</li>
 * <li>discrete Frechet: max_l a_l < theta as theta - 1 - a_l in [0, 256^r) for every
 *     step; within a k-NN sum, an upper bound z on the maximum as for d_inf.</li>
 * <li>delta* < theta: theta - 1 - delta* in [0, 2^64); 8 digits. With every reading in
 *     [0, B], delta* is an integer far below the group order, so this cannot wrap.</li>
 * </ul>
 */
public final class ZkSeriesAuth {

    static final int READING_DIGITS = CircuitBuilder.digitsForBits(16), THRESHOLD_DIGITS = CircuitBuilder.digitsForBits(64);
    static final long READING_MAX = (1L << 16) - 1;

    /** f*: how the k series distances are combined. */
    public enum Aggregate { SUM, MAX }

    public static final class Statement {
        public final int m;
        public final int[] tx, ty;              // lengths of the base readings, then of the new readings
        public final LocalDistType local;
        public final SeriesDistType series;
        public final long bound, theta, param;  // param: eps for ERD, lambda for TWED, unused otherwise
        public final Aggregate aggregate;
        public final int[][] pairs;             // (g, h): base reading g, new reading h
        public final int[][][] couplings;       // one per pair; (i, j): x index, y index

        /** k = 1: one base reading and one new reading. */
        public Statement(int m, int tx, int ty, LocalDistType local, SeriesDistType series, long bound, long theta,
                         int[][] coupling) {
            this(m, tx, ty, local, series, bound, theta, 0, coupling);
        }

        /** k = 1, with the parameter of ERD or TWED. */
        public Statement(int m, int tx, int ty, LocalDistType local, SeriesDistType series, long bound, long theta,
                         long param, int[][] coupling) {
            this(m, new int[] { tx }, new int[] { ty }, local, series, bound, theta, param, Aggregate.SUM,
                    new int[][] { { 0, 0 } }, new int[][][] { coupling });
        }

        public Statement(int m, int[] tx, int[] ty, LocalDistType local, SeriesDistType series, long bound, long theta,
                         long param, Aggregate aggregate, int[][] pairs, int[][][] couplings) {
            if (m < 1 || tx.length < 1 || ty.length < 1) throw new IllegalArgumentException("empty reading");
            for (int t : tx) if (t < 1) throw new IllegalArgumentException("empty reading");
            for (int t : ty) if (t < 1) throw new IllegalArgumentException("empty reading");
            if (bound < 0 || bound > READING_MAX) throw new IllegalArgumentException("B must be at most 2^16 - 1"); // so |x - y| < 2^16
            if (theta < 1) throw new IllegalArgumentException("theta must be at least 1");
            if (param < 0 || param > (1L << 40)) throw new IllegalArgumentException("parameter out of range");
            if (pairs.length < 1 || pairs.length != couplings.length) throw new IllegalArgumentException("one coupling per pair");
            this.m = m; this.tx = tx; this.ty = ty; this.local = local; this.series = series;
            this.bound = bound; this.theta = theta; this.param = param; this.aggregate = aggregate;
            this.pairs = pairs; this.couplings = couplings;
        }

        public int k() { return pairs.length; }

        /** Commitments in the order the circuit reads them: each X row by row, then each Y. */
        public int inputs() {
            int n = 0;
            for (int t : tx) n += t;
            for (int t : ty) n += t;
            return m * n;
        }

        /** Whether f_delta is the maximum (discrete Frechet) rather than the sum. */
        boolean maxTotal() { return series == SeriesDistType.FRECHET; }
    }

    private ZkSeriesAuth() {}

    // ---- couplings --------------------------------------------------------------

    /** Whether the public pairs and couplings are ones the authentication distance ranges over (Section 3.2). */
    public static boolean validCoupling(Statement st) {
        Set<Long> seen = new HashSet<>();
        for (int p = 0; p < st.k(); p++) {
            int[] pr = st.pairs[p];
            if (pr == null || pr.length != 2 || pr[0] < 0 || pr[0] >= st.tx.length || pr[1] < 0 || pr[1] >= st.ty.length) return false;
            if (!seen.add(((long) pr[0] << 32) | pr[1])) return false;                  // pairwise distinct
            if (!validCoupling(st.series, st.tx[pr[0]], st.ty[pr[1]], st.couplings[p])) return false;
        }
        return true;
    }

    static boolean validCoupling(SeriesDistType series, int tx, int ty, int[][] c) {
        if (c == null || c.length == 0) return false;
        for (int[] step : c) if (step == null || step.length != 2) return false;
        if (series == SeriesDistType.SUM) {
            if (c.length != Math.min(tx, ty)) return false;
            for (int l = 0; l < c.length; l++) if (c[l][0] != l || c[l][1] != l) return false;
            return true;
        }
        if (c[0][0] != 0 || c[0][1] != 0) return false;                                // DTW, Frechet, ERD, TWED: warping paths
        if (c[c.length - 1][0] != tx - 1 || c[c.length - 1][1] != ty - 1) return false;
        for (int l = 1; l < c.length; l++) {
            int di = c[l][0] - c[l - 1][0], dj = c[l][1] - c[l - 1][1];
            if (!((di == 1 && dj == 0) || (di == 0 && dj == 1) || (di == 1 && dj == 1))) return false;
        }
        return true;
    }

    /** The prover's coupling for SUM or DTW. */
    public static int[][] coupling(SeriesDistType series, LocalDistType local, long[][] x, long[][] y) {
        return coupling(series, local, 0, x, y);
    }

    /**
     * The prover's coupling: the diagonal, or an optimal warping path for the series
     * distance, by its recurrence (as in SeriesDist) and a backtrack that prefers the
     * diagonal, then a step in x, then a step in y.
     */
    public static int[][] coupling(SeriesDistType series, LocalDistType local, long param, long[][] x, long[][] y) {
        if (series == SeriesDistType.SUM) {
            int n = Math.min(x.length, y.length);
            int[][] c = new int[n][];
            for (int l = 0; l < n; l++) c[l] = new int[] { l, l };
            return c;
        }
        boolean max = series == SeriesDistType.FRECHET;
        int n1 = x.length, n2 = y.length;
        long[][] D = new long[n1][n2];
        D[0][0] = stepCost(series, local, param, x, y, 0, 0, -1, -1);                  // 0, or d(x_1, y_1) for Frechet
        for (int i = 0; i < n1; i++)
            for (int j = 0; j < n2; j++) {
                if (i == 0 && j == 0) continue;
                long best = Long.MAX_VALUE;
                if (i > 0) best = Math.min(best, via(series, local, param, x, y, D, i, j, i - 1, j, max));
                if (j > 0) best = Math.min(best, via(series, local, param, x, y, D, i, j, i, j - 1, max));
                if (i > 0 && j > 0) best = Math.min(best, via(series, local, param, x, y, D, i, j, i - 1, j - 1, max));
                D[i][j] = best;
            }
        List<int[]> back = new ArrayList<>();                                         // back from the end, one valid step at a time
        int i = n1 - 1, j = n2 - 1;
        back.add(new int[] { i, j });
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && via(series, local, param, x, y, D, i, j, i - 1, j - 1, max) == D[i][j]) { i--; j--; }
            else if (i > 0 && via(series, local, param, x, y, D, i, j, i - 1, j, max) == D[i][j]) i--;
            else j--;
            back.add(new int[] { i, j });
        }
        int[][] c = new int[back.size()][];
        for (int l = 0; l < c.length; l++) c[l] = back.get(c.length - 1 - l);
        return c;
    }

    private static long via(SeriesDistType series, LocalDistType local, long param, long[][] x, long[][] y, long[][] D,
                            int i, int j, int pi, int pj, boolean max) {
        long a = stepCost(series, local, param, x, y, i, j, pi, pj);
        return max ? Math.max(D[pi][pj], a) : D[pi][pj] + a;
    }

    /** Step cost a_l of step (i, j) after (pi, pj), or of the first step if pi < 0, in plain integers. */
    static long stepCost(SeriesDistType series, LocalDistType local, long param, long[][] x, long[][] y,
                         int i, int j, int pi, int pj) {
        boolean first = pi < 0;
        switch (series) {
            case SUM:
            case FRECHET: return LocalDist.distance(x[i], y[j], local);
            case DTW: return first ? 0 : LocalDist.distance(x[i], y[j], local);
            case ERD: return first ? 0 : (LocalDist.distance(x[i], y[j], local) > param ? param : 0);
            default:                                                                  // TWED
                if (first) return 0;
                if (pj == j) return param + LocalDist.distance(x[i], x[pi], local);   // a step in x alone deletes x_i
                if (pi == i) return param + LocalDist.distance(y[j], y[pj], local);   // a step in y alone deletes y_j
                return LocalDist.distance(x[i], y[j], local) + LocalDist.distance(x[pi], y[pj], local);
        }
    }

    /** The cost of one pair's coupling, in plain integers. */
    static long pairCost(Statement st, long[][] x, long[][] y, int[][] c) {
        long acc = 0;
        for (int l = 0; l < c.length; l++) {
            long a = stepCost(st.series, st.local, st.param, x, y, c[l][0], c[l][1],
                    l == 0 ? -1 : c[l - 1][0], l == 0 ? -1 : c[l - 1][1]);
            acc = st.maxTotal() ? Math.max(acc, a) : acc + a;
        }
        return acc;
    }

    /** The cost of the couplings, in plain integers: what the circuit bounds. */
    public static long cost(Statement st, long[][][] xs, long[][][] ys) {
        long acc = 0;
        for (int p = 0; p < st.k(); p++) {
            long c = pairCost(st, xs[st.pairs[p][0]], ys[st.pairs[p][1]], st.couplings[p]);
            acc = st.aggregate == Aggregate.MAX ? Math.max(acc, c) : acc + c;
        }
        return acc;
    }

    public static long cost(Statement st, long[][] x, long[][] y) {
        return cost(st, new long[][][] { x }, new long[][][] { y });
    }

    // ---- the circuit --------------------------------------------------------------

    /** The builder for a statement: a prover's (with the readings) or a verifier's (x = y = null). */
    public static CircuitBuilder builder(Statement st, long[][] x, long[][] y) { return builder(st, x, y, false); }

    static CircuitBuilder builder(Statement st, long[][] x, long[][] y, boolean lenient) {
        return builderAll(st, x == null ? null : new long[][][] { x }, y == null ? null : new long[][][] { y }, lenient);
    }

    /** The builder for several base and new readings (k-NN); xs = ys = null for a verifier. */
    public static CircuitBuilder builderAll(Statement st, long[][][] xs, long[][][] ys) { return builderAll(st, xs, ys, false); }

    static CircuitBuilder builderAll(Statement st, long[][][] xs, long[][][] ys, boolean lenient) {
        Scalar[] values = null;
        if (xs != null) {
            values = new Scalar[st.inputs()];
            int v = 0;
            for (long[][][] rs : new long[][][][] { xs, ys })
                for (long[][] r : rs) for (long[] e : r) for (int j = 0; j < st.m; j++) values[v++] = Group.ofUnsigned(e[j]);
        }
        CircuitBuilder cb = new CircuitBuilder(st.inputs(), values);
        cb.lenient = lenient;
        CircuitBuilder.Lin[][][] X = new CircuitBuilder.Lin[st.tx.length][][], Y = new CircuitBuilder.Lin[st.ty.length][][];
        int v = 0;
        for (int g = 0; g < st.tx.length; g++) {
            X[g] = new CircuitBuilder.Lin[st.tx[g]][st.m];
            for (int t = 0; t < st.tx[g]; t++) for (int j = 0; j < st.m; j++) X[g][t][j] = cb.input(v++);
        }
        for (int h = 0; h < st.ty.length; h++) {
            Y[h] = new CircuitBuilder.Lin[st.ty[h]][st.m];
            for (int t = 0; t < st.ty[h]; t++) for (int j = 0; j < st.m; j++) Y[h][t][j] = cb.input(v++);
        }
        for (CircuitBuilder.Lin[][] r : Y) for (CircuitBuilder.Lin[] e : r) for (CircuitBuilder.Lin y : e) inBound(cb, y, st.bound);
        // Frechet under k = 1 or a k-NN max: every step below theta, with no maximum wire at all
        boolean stepwise = st.maxTotal() && (st.k() == 1 || st.aggregate == Aggregate.MAX);
        CircuitBuilder.Lin delta = new CircuitBuilder.Lin();
        for (int p = 0; p < st.k(); p++) {
            CircuitBuilder.Lin[][] x = X[st.pairs[p][0]], y = Y[st.pairs[p][1]];
            int[][] c = st.couplings[p];
            CircuitBuilder.Lin[] a = new CircuitBuilder.Lin[c.length];
            for (int l = 0; l < c.length; l++)
                a[l] = step(cb, st, x, y, c[l][0], c[l][1], l == 0 ? -1 : c[l - 1][0], l == 0 ? -1 : c[l - 1][1]);
            if (stepwise) {
                for (CircuitBuilder.Lin al : a) cb.range(CircuitBuilder.Lin.constant(st.theta - 1).sub(al), digitsFor(st.theta - 1));
                continue;
            }
            CircuitBuilder.Lin u = new CircuitBuilder.Lin();
            if (st.maxTotal()) u = upperMax(cb, a, digitsFor(localMax(st)));          // Frechet within a k-NN sum
            else for (CircuitBuilder.Lin al : a) u = u.add(al);
            if (st.aggregate == Aggregate.MAX) cb.range(CircuitBuilder.Lin.constant(st.theta - 1).sub(u), THRESHOLD_DIGITS);
            else delta = delta.add(u);
        }
        if (!stepwise && st.aggregate == Aggregate.SUM)
            cb.range(CircuitBuilder.Lin.constant(st.theta - 1).sub(delta), THRESHOLD_DIGITS);   // delta* < theta
        return cb;
    }

    /** The step cost a_l of step (i, j) after (pi, pj), or of the first step if pi < 0. */
    static CircuitBuilder.Lin step(CircuitBuilder cb, Statement st, CircuitBuilder.Lin[][] x, CircuitBuilder.Lin[][] y,
                                   int i, int j, int pi, int pj) {
        boolean first = pi < 0;
        switch (st.series) {
            case SUM:
            case FRECHET: return local(cb, st.local, x[i], y[j]);
            case DTW: return first ? new CircuitBuilder.Lin() : local(cb, st.local, x[i], y[j]);   // DTW: a_1 = 0
            case ERD: return first ? new CircuitBuilder.Lin() : edr(cb, local(cb, st.local, x[i], y[j]), st.param, localMax(st));
            default:                                                                   // TWED: by the public direction
                if (first) return new CircuitBuilder.Lin();
                if (pj == j) return local(cb, st.local, x[i], x[pi]).add(st.param);
                if (pi == i) return local(cb, st.local, y[j], y[pj]).add(st.param);
                return local(cb, st.local, x[i], y[j]).add(local(cb, st.local, x[pi], y[pj]));
        }
    }

    /** v in [0, B]: v and v + (2^16 - 1 - B) in [0, 2^16). */
    static void inBound(CircuitBuilder cb, CircuitBuilder.Lin v, long bound) {
        cb.range(v, READING_DIGITS);
        if (bound == READING_MAX) return;                                       // [0, 2^16 - 1]: the first statement is the whole bound
        cb.range(v.add(READING_MAX - bound), READING_DIGITS);
    }

    /** An upper bound on the local distance d(x, y) (exact for d_1 and d_2). */
    static CircuitBuilder.Lin local(CircuitBuilder cb, LocalDistType type, CircuitBuilder.Lin[] x, CircuitBuilder.Lin[] y) {
        int m = x.length;
        CircuitBuilder.Lin[] d = new CircuitBuilder.Lin[m];
        for (int j = 0; j < m; j++) d[j] = x[j].sub(y[j]);
        CircuitBuilder.Lin sum = new CircuitBuilder.Lin();
        if (type == LocalDistType.EUCLIDEAN) {
            for (int j = 0; j < m; j++) sum = sum.add(cb.mul(d[j], d[j]));
            return sum;
        }
        CircuitBuilder.Lin[] a = new CircuitBuilder.Lin[m];
        for (int j = 0; j < m; j++) a[j] = abs(cb, d[j], cb.isProver() ? Group.ofUnsigned(Math.abs(signed(cb.eval(d[j])))) : null);
        if (type == LocalDistType.MANHATTAN) {
            for (int j = 0; j < m; j++) sum = sum.add(a[j]);
            return sum;
        }
        return upperMax(cb, a, READING_DIGITS);
    }

    /** a = |d| from the claimed value: a in [0, 2^16) and (a - d)(a + d) = 0. */
    static CircuitBuilder.Lin abs(CircuitBuilder cb, CircuitBuilder.Lin d, Scalar claimed) {
        CircuitBuilder.Lin a = cb.free(claimed);
        cb.range(a, READING_DIGITS);
        cb.mulEq(a.sub(d), a.add(d), new CircuitBuilder.Lin());
        return a;
    }

    /** An upper bound z on max(a), the prover claiming the maximum. */
    static CircuitBuilder.Lin upperMax(CircuitBuilder cb, CircuitBuilder.Lin[] a, int digits) {
        Scalar z = null;
        if (cb.isProver()) {
            long best = 0;
            for (CircuitBuilder.Lin aj : a) best = Math.max(best, Group.toUnsignedLong(cb.eval(aj)));
            z = Group.ofUnsigned(best);
        }
        return upper(cb, a, z, digits);
    }

    /** z >= max(a) from the claimed value: z - a_j in [0, 256^digits) for all j. */
    static CircuitBuilder.Lin upper(CircuitBuilder cb, CircuitBuilder.Lin[] a, Scalar claimed, int digits) {
        if (a.length == 1) return a[0];
        CircuitBuilder.Lin z = cb.free(claimed);
        for (CircuitBuilder.Lin aj : a) cb.range(z.sub(aj), digits);
        return z;
    }

    /** The ERD step cost eps * b for the local distance d, the prover's bit b = [d > eps]. */
    static CircuitBuilder.Lin edr(CircuitBuilder cb, CircuitBuilder.Lin d, long eps, long dMax) {
        Scalar b = null;
        if (cb.isProver()) b = Group.toUnsignedLong(cb.eval(d)) > eps ? Group.one() : Group.zero();
        return edr(cb, d, eps, dMax, b);
    }

    /** eps * b from the claimed bit: b (1 - b) = 0 and eps - d + b dMax in [0, 256^r), so b = 0 only if d <= eps. */
    static CircuitBuilder.Lin edr(CircuitBuilder cb, CircuitBuilder.Lin d, long eps, long dMax, Scalar claimed) {
        CircuitBuilder.Lin b = cb.free(claimed);
        cb.mulEq(b, CircuitBuilder.Lin.constant(1).sub(b), new CircuitBuilder.Lin());
        cb.range(CircuitBuilder.Lin.constant(eps).sub(d).add(b.times(dMax)), digitsFor(eps + dMax));
        return b.times(eps);
    }

    /** The largest local distance of readings in [0, 2^16): what the bound wires can exceed it by. */
    static long localMax(Statement st) {
        switch (st.local) {
            case EUCLIDEAN: return st.m * READING_MAX * READING_MAX;
            case MANHATTAN: return st.m * READING_MAX;
            default: return READING_MAX;
        }
    }

    /** Whole base-256 digits for values up to max. */
    static int digitsFor(long max) {
        int bits = 64 - Long.numberOfLeadingZeros(Math.max(1, max));
        return Math.min(8, (bits + CircuitBuilder.LOG_BASE - 1) / CircuitBuilder.LOG_BASE);
    }

    /** A field element read as a signed integer of magnitude below 2^63. */
    static long signed(Scalar s) {
        Long u = Group.toUnsignedLong(s);
        if (u != null && u >= 0) return u;
        Long n = Group.toUnsignedLong(Group.neg(s));
        if (n != null && n >= 0) return -n;
        throw new IllegalArgumentException("not a small integer");
    }

    // ---- commit, prove and verify ----------------------------------------------------

    /** The generators the statement needs. */
    public static Generators generators(Statement st) { return Generators.create(builderAll(st, null, null).nm()); }

    /** ZK-Series commitments V = v B + s B_blind to X, then Y; the blindings s go in sOut. */
    public static List<ECPoint> commit(long[][] x, long[][] y, Scalar[] sOut, Random rng) {
        return commit(new long[][][] { x }, new long[][][] { y }, sOut, rng);
    }

    /** Commitments to each base reading, then each new reading. */
    public static List<ECPoint> commit(long[][][] xs, long[][][] ys, Scalar[] sOut, Random rng) {
        zk.bulletproofs.PedersenCommitment pc = Generators.pedersen();
        List<ECPoint> V = new ArrayList<>();
        for (long[][][] rs : new long[][][][] { xs, ys })
            for (long[][] r : rs)
                for (long[] e : r)
                    for (long v : e) {
                        Scalar s = Group.random(rng);
                        sOut[V.size()] = s;
                        V.add(pc.commit(Group.ofUnsigned(v), s).decompress());
                    }
        return V;
    }

    public static BpppProver.Proof prove(Generators g, Statement st, long[][] x, long[][] y, Scalar[] s,
                                         List<ECPoint> V, Random rng) {
        return prove(g, st, new long[][][] { x }, new long[][][] { y }, s, V, rng);
    }

    public static BpppProver.Proof prove(Generators g, Statement st, long[][][] xs, long[][][] ys, Scalar[] s,
                                         List<ECPoint> V, Random rng) {
        if (!validCoupling(st)) throw new IllegalArgumentException("not a valid coupling");
        return BpppProver.proveCircuit(g, builderAll(st, xs, ys), s, V, rng);
    }

    public static boolean verify(Generators g, Statement st, List<ECPoint> V, BpppProver.Proof pf) {
        if (!validCoupling(st) || V.size() != st.inputs()) return false;
        return BpppVerifier.verifyCircuit(g, builderAll(st, null, null), V, pf);
    }
}
