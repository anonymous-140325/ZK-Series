package cryptoref;

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

    /** Sum of s_i * P_i; the empty sum is the identity. */
    public static ECPoint msm(List<Scalar> s, List<ECPoint> p) {
        if (s.size() != p.size()) throw new IllegalArgumentException("scalar and point counts differ");
        if (s.isEmpty()) return F.identity();
        List<ECPoint> rest = new ArrayList<>(p.size() - 1);
        for (ECPoint q : p.subList(1, p.size())) rest.add(q.decompress());
        return F.multiscalarMulOpt(s.get(0), s.subList(1, s.size()), null, p.get(0).decompress(), rest, null);
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
