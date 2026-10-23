package bppp;

import crypto.Generators;
import crypto.Group;
import ec.ECPoint;
import ec.Scalar;
import series.LocalDist;
import series.LocalDist.LocalDistType;
import series.SeriesDist.SeriesDistType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The ZK-Series authentication proof (Section 3.2 of the ZK-Series paper) as one
 * Bulletproofs++ reciprocal-form circuit, for k = 1.
 *
 * Statement: base reading X (T_x elements of dimension m) and new reading Y (T_y
 * elements), committed value by value as V = v B + s B_blind; a public coupling
 * C = ((i_1, j_1), ..., (i_L, j_L)); a public threshold theta. The proof shows
 *
 *   sum over the coupling's steps of d(x_i, y_j)  <  theta,
 *
 * with d = d_1 (Manhattan), d_2 (squared Euclidean) or d_inf (Chebyshev), and the
 * coupling the diagonal (series distance "sum") or a warping path (DTW, whose first
 * step costs nothing, as in SeriesDist.dtw and the paper). Every value of Y is proven
 * in [0, B]; X was committed at registration and is not re-ranged.
 *
 * <b>Why a bound suffices</b> (the paper's "Upper Bounds Suffice"): the series distance
 * is a minimum over couplings, so the cost of one valid coupling below theta implies
 * delta* < theta. The proof reveals the coupling, as item 4 of the paper's proof
 * structure does, and the verifier checks that it is valid for the series distance.
 *
 * <b>The pieces</b>, each in CircuitBuilder terms (base 256):
 * <ul>
 * <li>v in [0, B]: v and v + (2^16 - 1 - B) in [0, 2^16); 4 digits.</li>
 * <li>d_2: (x - y)^2 as one product per coordinate.</li>
 * <li>|x - y|: a new wire a with a in [0, 2^16) and (a - d)(a + d) = 0, so a = +-d and
 *     the range rules out -d (|d| <= B < 2^16); 2 digits, 1 gate.</li>
 * <li>max(a_1..a_m): z with z - a_j in [0, 2^16) for all j, and prod_j (z - a_j) = 0,
 *     as m - 1 products; so z >= every a_j and equals one of them.</li>
 * <li>delta < theta: theta - 1 - delta in [0, 2^64); 8 digits. With every reading in
 *     [0, B], delta is an integer far below the group order, so this cannot wrap.</li>
 * </ul>
 */
public final class ZkSeriesAuth {

    static final int READING_DIGITS = CircuitBuilder.digitsForBits(16), THRESHOLD_DIGITS = CircuitBuilder.digitsForBits(64);

    public static final class Statement {
        public final int m, tx, ty;
        public final LocalDistType local;
        public final SeriesDistType series;
        public final long bound, theta;
        public final int[][] coupling;          // (i, j): x index, y index

        public Statement(int m, int tx, int ty, LocalDistType local, SeriesDistType series, long bound, long theta,
                         int[][] coupling) {
            if (m < 1 || tx < 1 || ty < 1) throw new IllegalArgumentException("empty reading");
            if (bound < 0 || bound > (1 << 16) - 1) throw new IllegalArgumentException("B must be at most 2^16 - 1"); // so |x - y| < 2^16
            if (theta < 1) throw new IllegalArgumentException("theta must be at least 1");
            if (series != SeriesDistType.SUM && series != SeriesDistType.DTW) throw new IllegalArgumentException("SUM or DTW");
            this.m = m; this.tx = tx; this.ty = ty; this.local = local; this.series = series;
            this.bound = bound; this.theta = theta; this.coupling = coupling;
        }

        /** Commitments in the order the circuit reads them: X row by row, then Y. */
        public int inputs() { return m * (tx + ty); }
    }

    private ZkSeriesAuth() {}

    // ---- couplings --------------------------------------------------------------

    /** Whether the public coupling is one the series distance ranges over (Section 3.2). */
    public static boolean validCoupling(Statement st) {
        int[][] c = st.coupling;
        if (c == null || c.length == 0) return false;
        for (int[] step : c) if (step.length != 2) return false;
        if (st.series == SeriesDistType.SUM) {
            if (c.length != Math.min(st.tx, st.ty)) return false;
            for (int l = 0; l < c.length; l++) if (c[l][0] != l || c[l][1] != l) return false;
            return true;
        }
        if (c[0][0] != 0 || c[0][1] != 0) return false;
        if (c[c.length - 1][0] != st.tx - 1 || c[c.length - 1][1] != st.ty - 1) return false;
        for (int l = 1; l < c.length; l++) {
            int di = c[l][0] - c[l - 1][0], dj = c[l][1] - c[l - 1][1];
            if (!((di == 1 && dj == 0) || (di == 0 && dj == 1) || (di == 1 && dj == 1))) return false;
        }
        return true;
    }

    /** The prover's coupling: the diagonal, or an optimal warping path of the DTW of SeriesDist.dtw. */
    public static int[][] coupling(SeriesDistType series, LocalDistType local, long[][] x, long[][] y) {
        if (series == SeriesDistType.SUM) {
            int n = Math.min(x.length, y.length);
            int[][] c = new int[n][];
            for (int l = 0; l < n; l++) c[l] = new int[] { l, l };
            return c;
        }
        int n1 = x.length, n2 = y.length;
        long[][] D = new long[n1][n2];
        for (int i = 0; i < n1; i++)
            for (int j = 0; j < n2; j++) {
                if (i == 0 && j == 0) continue;                                        // D[0][0] = 0, as in SeriesDist.dtw
                long best = Long.MAX_VALUE;
                if (i > 0) best = Math.min(best, D[i - 1][j]);
                if (j > 0) best = Math.min(best, D[i][j - 1]);
                if (i > 0 && j > 0) best = Math.min(best, D[i - 1][j - 1]);
                D[i][j] = LocalDist.distance(x[i], y[j], local) + best;
            }
        List<int[]> back = new ArrayList<>();                                         // back from the end, one valid step at a time
        int i = n1 - 1, j = n2 - 1;
        back.add(new int[] { i, j });
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && D[i - 1][j - 1] <= Math.min(D[i - 1][j], D[i][j - 1])) { i--; j--; }
            else if (i > 0 && (j == 0 || D[i - 1][j] <= D[i][j - 1])) i--;
            else j--;
            back.add(new int[] { i, j });
        }
        int[][] c = new int[back.size()][];
        for (int l = 0; l < c.length; l++) c[l] = back.get(c.length - 1 - l);
        return c;
    }

    /** The cost of the coupling, in plain integers: what the circuit bounds. */
    public static long cost(Statement st, long[][] x, long[][] y) {
        long sum = 0;
        for (int l = 0; l < st.coupling.length; l++) {
            if (st.series == SeriesDistType.DTW && l == 0) continue;                  // DTW: a_1 = 0
            sum += LocalDist.distance(x[st.coupling[l][0]], y[st.coupling[l][1]], st.local);
        }
        return sum;
    }

    // ---- the circuit --------------------------------------------------------------

    /** The builder for a statement: a prover's (with the readings) or a verifier's (x = y = null). */
    public static CircuitBuilder builder(Statement st, long[][] x, long[][] y) { return builder(st, x, y, false); }

    static CircuitBuilder builder(Statement st, long[][] x, long[][] y, boolean lenient) {
        Scalar[] values = null;
        if (x != null) {
            values = new Scalar[st.inputs()];
            for (int t = 0; t < st.tx; t++) for (int j = 0; j < st.m; j++) values[t * st.m + j] = Group.ofUnsigned(x[t][j]);
            for (int t = 0; t < st.ty; t++) for (int j = 0; j < st.m; j++) values[(st.tx + t) * st.m + j] = Group.ofUnsigned(y[t][j]);
        }
        CircuitBuilder cb = new CircuitBuilder(st.inputs(), values);
        cb.lenient = lenient;
        CircuitBuilder.Lin[][] X = new CircuitBuilder.Lin[st.tx][st.m], Y = new CircuitBuilder.Lin[st.ty][st.m];
        for (int t = 0; t < st.tx; t++) for (int j = 0; j < st.m; j++) X[t][j] = cb.input(t * st.m + j);
        for (int t = 0; t < st.ty; t++) for (int j = 0; j < st.m; j++) Y[t][j] = cb.input((st.tx + t) * st.m + j);
        for (CircuitBuilder.Lin[] e : Y) for (CircuitBuilder.Lin v : e) inBound(cb, v, st.bound);
        CircuitBuilder.Lin delta = new CircuitBuilder.Lin();
        for (int l = 0; l < st.coupling.length; l++) {
            if (st.series == SeriesDistType.DTW && l == 0) continue;                  // DTW: a_1 = 0
            delta = delta.add(local(cb, st.local, X[st.coupling[l][0]], Y[st.coupling[l][1]]));
        }
        cb.range(CircuitBuilder.Lin.constant(st.theta - 1).sub(delta), THRESHOLD_DIGITS);   // delta < theta
        return cb;
    }

    /** v in [0, B]: v and v + (2^16 - 1 - B) in [0, 2^16). */
    static void inBound(CircuitBuilder cb, CircuitBuilder.Lin v, long bound) {
        cb.range(v, READING_DIGITS);
        if (bound == (1L << 16) - 1) return;                                    // [0, 2^16 - 1]: the first statement is the whole bound
        cb.range(v.add((1L << 16) - 1 - bound), READING_DIGITS);
    }

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
        Scalar z = null;
        if (cb.isProver()) {
            long best = 0;
            for (int j = 0; j < m; j++) best = Math.max(best, Group.toUnsignedLong(cb.eval(a[j])));
            z = Group.ofUnsigned(best);
        }
        return max(cb, a, z);
    }

    /** a = |d| from the claimed value: a in [0, 2^16) and (a - d)(a + d) = 0. */
    static CircuitBuilder.Lin abs(CircuitBuilder cb, CircuitBuilder.Lin d, Scalar claimed) {
        CircuitBuilder.Lin a = cb.free(claimed);
        cb.range(a, READING_DIGITS);
        cb.mulEq(a.sub(d), a.add(d), new CircuitBuilder.Lin());
        return a;
    }

    /** z = max(a) from the claimed value: z - a_j in [0, 2^16) for all j, and prod_j (z - a_j) = 0. */
    static CircuitBuilder.Lin max(CircuitBuilder cb, CircuitBuilder.Lin[] a, Scalar claimed) {
        if (a.length == 1) return a[0];
        CircuitBuilder.Lin z = cb.free(claimed);
        for (CircuitBuilder.Lin aj : a) cb.range(z.sub(aj), READING_DIGITS);
        CircuitBuilder.Lin acc = z.sub(a[0]);
        for (int j = 1; j < a.length - 1; j++) acc = cb.mul(acc, z.sub(a[j]));
        cb.mulEq(acc, z.sub(a[a.length - 1]), new CircuitBuilder.Lin());
        return z;
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
    public static Generators generators(Statement st) { return Generators.create(builder(st, null, null).nm()); }

    /** ZK-Series commitments V = v B + s B_blind to X, then Y; the blindings s go in sOut. */
    public static List<ECPoint> commit(long[][] x, long[][] y, Scalar[] sOut, Random rng) {
        zk.bulletproofs.PedersenCommitment pc = Generators.pedersen();
        List<ECPoint> V = new ArrayList<>();
        for (long[][] r : new long[][][] { x, y })
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
        if (!validCoupling(st)) throw new IllegalArgumentException("not a valid coupling");
        return BpppProver.proveCircuit(g, builder(st, x, y), s, V, rng);
    }

    public static boolean verify(Generators g, Statement st, List<ECPoint> V, BpppProver.Proof pf) {
        if (!validCoupling(st) || V.size() != st.inputs()) return false;
        return BpppVerifier.verifyCircuit(g, builder(st, null, null), V, pf);
    }
}
