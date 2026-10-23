package crypto;

import ec.Scalar;

/**
 * Laurent polynomials in the formal variable T, scalar-valued (F[T]) and
 * vector-valued (F^n[T]), as Section 5.2 uses them: p_n(T), p_s(T), n(T), l(T),
 * f(T) and so on. Degrees run from MIN to MAX; the protocol never leaves [-2, 6].
 */
public final class Poly {

    public static final int MIN = -4, MAX = 12;
    private static final int W = MAX - MIN + 1;

    /** A scalar polynomial: c[d - MIN] is the coefficient of T^d. */
    public static final class S {
        final Scalar[] c = Group.zeros(W);

        public Scalar coef(int d) { return c[d - MIN]; }

        public S set(int d, Scalar v) { c[d - MIN] = v; return this; }

        public S addTerm(int d, Scalar v) { c[d - MIN] = c[d - MIN].add(v); return this; }

        public S add(S o) { S r = new S(); for (int i = 0; i < W; i++) r.c[i] = c[i].add(o.c[i]); return r; }

        public S sub(S o) { S r = new S(); for (int i = 0; i < W; i++) r.c[i] = c[i].subtract(o.c[i]); return r; }

        public Scalar eval(Scalar t) {
            Scalar tInv = t.invert(), acc = Group.zero();
            for (int d = MIN; d <= MAX; d++) {
                Scalar tp = d >= 0 ? Group.pow(t, d) : Group.pow(tInv, -d);
                acc = acc.add(coef(d).multiply(tp));
            }
            return acc;
        }
    }

    /**
     * A vector polynomial: v[d - MIN] is the coefficient vector of T^d, length n. used[]
     * marks the degrees ever written, so products skip the rest without testing
     * n scalars for zero.
     */
    public static final class V {
        public final int n;
        final Scalar[][] v = new Scalar[W][];
        final boolean[] used = new boolean[W];

        public V(int n) {
            this.n = n;
            for (int i = 0; i < W; i++) v[i] = Group.zeros(n);
        }

        public Scalar[] coef(int d) { return v[d - MIN]; }

        /** Adds k * x to the T^d coefficient. */
        public V addTerm(int d, Scalar k, Scalar[] x) {
            if (x.length != n) throw new IllegalArgumentException("length " + x.length + " != " + n);
            Scalar[] t = v[d - MIN];
            for (int i = 0; i < n; i++) t[i] = t[i].add(k.multiply(x[i]));
            used[d - MIN] = true;
            return this;
        }

        public V add(V o) {
            V r = new V(n);
            for (int d = MIN; d <= MAX; d++) {
                if (used[d - MIN]) r.addTerm(d, Group.one(), coef(d));
                if (o.used[d - MIN]) r.addTerm(d, Group.one(), o.coef(d));
            }
            return r;
        }

        public Scalar[] eval(Scalar t) {
            Scalar tInv = t.invert();
            Scalar[] out = Group.zeros(n);
            for (int d = MIN; d <= MAX; d++) {
                if (!used[d - MIN]) continue;
                Scalar tp = d >= 0 ? Group.pow(t, d) : Group.pow(tInv, -d);
                Scalar[] c = coef(d);
                for (int i = 0; i < n; i++) out[i] = out[i].add(c[i].multiply(tp));
            }
            return out;
        }
    }

    private Poly() {}

    /** <a(T), b(T)> as a scalar polynomial; w, if not null, weights position i by w[i]. */
    public static S inner(V a, V b, Scalar[] w) {
        if (a.n != b.n) throw new IllegalArgumentException("length mismatch");
        S r = new S();
        for (int da = MIN; da <= MAX; da++) {
            if (!a.used[da - MIN]) continue;
            Scalar[] x = a.coef(da);
            for (int db = MIN; db <= MAX; db++) {
                if (!b.used[db - MIN]) continue;
                Scalar[] y = b.coef(db);
                int d = da + db;
                if (d < MIN || d > MAX) throw new IllegalStateException("degree " + d + " out of range");
                Scalar acc = Group.zero();
                for (int i = 0; i < a.n; i++) {
                    Scalar p = x[i].multiply(y[i]);
                    acc = acc.add(w == null ? p : p.multiply(w[i]));
                }
                r.addTerm(d, acc);
            }
        }
        return r;
    }

    /** |a(T)|^2_mu with weights mu[i] = mu^(i+1). */
    public static S normSq(V a, Scalar[] mu) {
        S r = new S();
        for (int da = MIN; da <= MAX; da++) {
            if (!a.used[da - MIN]) continue;
            Scalar[] x = a.coef(da);
            for (int db = da; db <= MAX; db++) {
                if (!a.used[db - MIN]) continue;
                int d = da + db;
                if (d < MIN || d > MAX) throw new IllegalStateException("degree " + d + " out of range");
                Scalar[] y = a.coef(db);
                Scalar acc = Group.zero();
                for (int i = 0; i < a.n; i++) acc = acc.add(x[i].multiply(y[i]).multiply(mu[i]));
                r.addTerm(d, da == db ? acc : acc.add(acc));
            }
        }
        return r;
    }
}
