package crypto;

import ec.Curve25519Factory;
import ec.ECPoint;
import ec.ECPointFactory;
import ec.Scalar;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The group G and field F of the paper (Section 2): Ristretto255 through ZK-Series's
 * ec interfaces, so every point here is a ZK-Series point.
 */
public final class Group {

    private static final ECPointFactory F = new Curve25519Factory();
    private static final Random DEFAULT_RNG = new SecureRandom();
    private Group() {}

    /**
     * The thread cap for proof arithmetic, the calling thread included (1..8; default 1).
     * It governs, through one shared budget (ParallelTasks): the windows of large
     * multiscalar multiplications, the norm argument's X computed alongside R, and the
     * generator folds. Call only when no proof is running. Proofs are identical for every cap.
     */
    public static synchronized void configureThreads(int cap) {
        ParallelTasks.configure(cap);
        ParallelMsm.configure(cap > 1);
    }

    public static int threads() { return ParallelTasks.workers(); }

    public static ECPointFactory factory() { return F; }

    public static Scalar zero() { return F.zero(); }

    public static Scalar one() { return F.one(); }

    public static ECPoint identity() { return F.identity(); }

    /** A signed integer as a field element; a negative v maps to p - |v|. */
    public static Scalar of(long v) {
        Scalar s = ofUnsigned(Math.abs(v));
        return v < 0 ? neg(s) : s;
    }

    /** An unsigned 64-bit integer as a field element. */
    public static Scalar ofUnsigned(long v) {
        byte[] wide = new byte[64];
        for (int i = 0; i < 8; i++) wide[i] = (byte) (v >>> (8 * i));
        return F.fromBytesModOrderWide(wide);
    }

    public static Scalar neg(Scalar s) { return F.zero().subtract(s); }

    /** s as an unsigned 64-bit integer if s < 2^64, else null (the encoding is little-endian). */
    public static Long toUnsignedLong(Scalar s) {
        byte[] b = s.reduce().toByteArray();
        for (int i = 8; i < b.length; i++) if (b[i] != 0) return null;
        return lowBits(s);
    }

    /** The low 64 bits of s. */
    public static long lowBits(Scalar s) {
        byte[] b = s.reduce().toByteArray();
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[i] & 0xFF);
        return v;
    }

    /** A uniformly random field element: 64 random bytes reduced wide. */
    public static Scalar random(Random rng) {
        byte[] b = new byte[64];
        (rng == null ? DEFAULT_RNG : rng).nextBytes(b);
        return F.fromBytesModOrderWide(b);
    }

    public static Scalar[] random(Random rng, int n) {
        Scalar[] out = new Scalar[n];
        for (int i = 0; i < n; i++) out[i] = random(rng);
        return out;
    }

    public static Scalar[] zeros(int n) {
        Scalar[] out = new Scalar[n];
        Arrays.fill(out, F.zero());
        return out;
    }

    public static boolean eq(Scalar a, Scalar b) {
        return Arrays.equals(a.reduce().toByteArray(), b.reduce().toByteArray());
    }

    public static boolean isZero(Scalar a) { return eq(a, F.zero()); }

    public static boolean eq(ECPoint a, ECPoint b) {
        return Arrays.equals(a.compress().toByteArray(), b.compress().toByteArray());
    }

    /**
     * Sum of s_i * P_i; the empty sum is the identity.
     *
     * The same library algorithms as Curve25519Factory.multiscalarMulOpt, called directly to
     * avoid its list copies: MulUtils.multiscalarMulOpt uses Straus below 30 points beyond the
     * first and Pippenger from there, i.e. Pippenger for count >= 31, as here. Pippenger goes
     * through ParallelMsm.evaluate, which spreads its windows over the thread budget.
     */
    public static ECPoint msm(List<Scalar> s, List<ECPoint> p) {
        if (s.size() != p.size()) throw new IllegalArgumentException("scalar and point counts differ");
        if (s.isEmpty()) return F.identity();
        int count = s.size();
        if (count == 1) return new ec.RistrettoPoint(((ec.RistrettoPoint)p.get(0).decompress()).getPoint()
                .multiply(((ec.RScalar)s.get(0)).getScalar()));
        List<com.weavechain.curve25519.Scalar> scalars = new ArrayList<>(count);
        List<com.weavechain.curve25519.RistrettoElement> points = new ArrayList<>(count);
        for (int i=0;i<count;i++) {
            scalars.add(((ec.RScalar)s.get(i)).getScalar());
            points.add(((ec.RistrettoPoint)p.get(i).decompress()).getPoint());
        }
        return new ec.RistrettoPoint(count >= 31
                ? ParallelMsm.evaluate(scalars, points)
                : com.weavechain.curve25519.MulUtils.mulStraus(scalars, points));
    }

    /** Read-only first-round tables. The caller's input array is never retained. */
    public static final class PublicFoldTables {
        private final com.weavechain.curve25519.PublicPointFolding.PreparedPairs prepared;
        private PublicFoldTables(ECPoint[] points) {
            prepared = new com.weavechain.curve25519.PublicPointFolding.PreparedPairs(backendPoints(points, points.length));
        }
        public boolean matches(ECPoint[] points) {
            return prepared.matches(backendPoints(points, points.length), points.length);
        }
    }

    public static PublicFoldTables preparePublicFold(ECPoint[] points) { return new PublicFoldTables(points); }

    /**
     * points[i] := points[2i] + k points[2i+1] for 2i < length, in place: the generator fold
     * of the norm argument. For PUBLIC points and a PUBLIC scalar k only (variable time);
     * see com.weavechain.curve25519.PublicPointFolding.
     */
    public static void foldPublicPairs(ECPoint[] points, int length, Scalar publicScalar, PublicFoldTables tables) {
        if (length < 2 || length > points.length || (length & 1) != 0) throw new IllegalArgumentException("fold length");
        com.weavechain.curve25519.PublicPointFolding.Plan plan =
                new com.weavechain.curve25519.PublicPointFolding.Plan(((ec.RScalar)publicScalar).getScalar());
        com.weavechain.curve25519.RistrettoElement[] out = com.weavechain.curve25519.PublicPointFolding.foldScheduled(
                backendPoints(points, length), length, plan, tables == null ? null : tables.prepared, threads(),
                job -> ParallelTasks.start(() -> { job.run(); return null; }));
        for (int i = 0; i < out.length; i++) points[i] = new ec.RistrettoPoint(out[i]);
    }

    private static com.weavechain.curve25519.RistrettoElement[] backendPoints(ECPoint[] points, int length) {
        com.weavechain.curve25519.RistrettoElement[] out = new com.weavechain.curve25519.RistrettoElement[length];
        for (int i = 0; i < length; i++) out[i] = ((ec.RistrettoPoint)points[i].decompress()).getPoint();
        return out;
    }

    /** <a, b> over F. */
    public static Scalar ip(Scalar[] a, Scalar[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("length mismatch");
        Scalar acc = F.zero();
        for (int i = 0; i < a.length; i++) acc = acc.add(a[i].multiply(b[i]));
        return acc;
    }

    /** e_n(q) = (1, q, ..., q^(n-1)). */
    public static Scalar[] powersFrom0(Scalar q, int n) {
        Scalar[] out = new Scalar[n];
        Scalar p = F.one();
        for (int i = 0; i < n; i++) { out[i] = p; p = p.multiply(q); }
        return out;
    }

    /** (q, q^2, ..., q^n): the paper's q e_n(q). */
    public static Scalar[] powersFrom1(Scalar q, int n) {
        Scalar[] out = new Scalar[n];
        Scalar p = q;
        for (int i = 0; i < n; i++) { out[i] = p; p = p.multiply(q); }
        return out;
    }

    public static Scalar pow(Scalar q, int e) {
        Scalar r = F.one();
        for (int i = 0; i < e; i++) r = r.multiply(q);
        return r;
    }
}
