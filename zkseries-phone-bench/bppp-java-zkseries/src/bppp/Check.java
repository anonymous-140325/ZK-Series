package bppp;

import crypto.Generators;
import crypto.Group;
import crypto.NormLinearArgument;
import crypto.Transcript;
import ec.ECPoint;
import ec.Scalar;
import series.LocalDist;
import series.LocalDist.LocalDistType;
import series.SeriesDist;
import series.SeriesDist.SeriesDistType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Completeness and soundness checks. Honest proofs must verify; proofs of false
 * statements (built with a lenient builder and the prover's witness check off) and
 * tampered proofs must be rejected.
 *
 *   java ... bppp.Check
 */
public final class Check {

    static final Random rnd = new Random(7);
    static final long B = 8000;
    static int passed = 0, failed = 0;

    static void check(String what, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("FAILED: " + what); }
    }

    static boolean throwsAny(Runnable r) {
        try { r.run(); return false; } catch (RuntimeException e) { return true; }
    }

    public static void main(String[] args) {
        passed = 0; failed = 0;                                                      // the app runs this in-process, repeatedly
        Group.configureThreads(Integer.getInteger("bppp.threads", 1));               // checks must pass at every cap
        normArgument();
        ranges();
        gadgets();
        tampering();
        authentication();
        System.out.println("bppp-java-zkseries: passed " + passed + ", failed " + failed);
        if (failed > 0) throw new IllegalStateException("checks failed: " + failed);   // not System.exit: the app runs this in-process
    }

    // ---- Section 4.3: the norm linear argument ---------------------------------

    static void normArgument() {
        for (int nn = 8; nn <= 64; nn *= 2) {
            Generators g = Generators.create(nn);
            Scalar rho = Group.random(rnd), mu = rho.square();
            Scalar[] c = Group.random(rnd, 8), l = Group.random(rnd, 8), n = Group.random(rnd, nn);
            ECPoint C = NormLinearArgument.commit(g.G, g.Gvec, g.H, c, mu, l, n);
            NormLinearArgument.Proof pf = NormLinearArgument.prove(new Transcript("nla"), g.G, g.Gvec, g.H, c, rho, l, n);
            check("norm argument |n| = " + nn + ": log2 |n| rounds", pf.X.size() == NormLinearArgument.rounds(nn));
            check("norm argument |n| = " + nn + ": honest proof verifies", nlaVerify(g, c, rho, C, pf));
            check("norm argument |n| = " + nn + ": another commitment is rejected", !nlaVerify(g, c, rho, C.add(g.G), pf));
            pf.n = pf.n.add(Group.one());
            check("norm argument |n| = " + nn + ": a tampered n is rejected", !nlaVerify(g, c, rho, C, pf));
        }
        // Large enough for every parallel path: windowed Pippenger (>= 1024 points), X alongside R
        // (>= 512) and scheduled folds (>= 512). The norm argument draws no randomness, so 1 and 8
        // threads must give the same proof.
        int saved = Group.threads(), big = 2048;
        Generators gb = Generators.create(big);
        Scalar rho = Group.random(rnd), mu = rho.square();
        Scalar[] c = Group.random(rnd, 8), l = Group.random(rnd, 8), n = Group.random(rnd, big);
        ECPoint C = NormLinearArgument.commit(gb.G, gb.Gvec, gb.H, c, mu, l, n);
        NormLinearArgument.Proof[] pfs = new NormLinearArgument.Proof[2];
        int[] caps = { 1, 8 };
        for (int i = 0; i < 2; i++) {
            Group.configureThreads(caps[i]);
            pfs[i] = NormLinearArgument.prove(new Transcript("nla"), gb.G, gb.Gvec, gb.H, c, rho, l, n, gb.publicFoldTables());
        }
        Group.configureThreads(saved);
        boolean same = Group.eq(pfs[0].n, pfs[1].n) && Group.eq(pfs[0].l, pfs[1].l);
        for (int k = 0; k < pfs[0].X.size(); k++)
            same &= Group.eq(pfs[0].X.get(k), pfs[1].X.get(k)) && Group.eq(pfs[0].R.get(k), pfs[1].R.get(k));
        check("norm argument |n| = 2048: 1 and 8 threads give the same proof", same);
        check("norm argument |n| = 2048: the proof verifies", nlaVerify(gb, c, rho, C, pfs[1]));
        Generators g4 = Generators.create(4);
        check("norm argument: |n| < |l| is refused", throwsAny(() -> NormLinearArgument.prove(new Transcript("nla"),
                g4.G, g4.Gvec, g4.H, Group.random(rnd, 8), Group.random(rnd), Group.random(rnd, 8), Group.random(rnd, 4))));
    }

    static boolean nlaVerify(Generators g, Scalar[] c, Scalar rho, ECPoint C, NormLinearArgument.Proof pf) {
        List<Scalar> s = new ArrayList<>(); List<ECPoint> p = new ArrayList<>();
        s.add(Group.one()); p.add(C);
        return NormLinearArgument.verify(new Transcript("nla"), g.G, g.Gvec, g.H, c, rho, s, p, pf);
    }

    // ---- statements over committed inputs, through CircuitBuilder -------------

    /** Inputs with commitments, and a statement over them proven and verified by the same code. */
    static final class Inputs {
        final long[] vals;
        final Scalar[] s;
        final List<ECPoint> V;

        Inputs(long... vals) {
            this.vals = vals;
            s = new Scalar[vals.length];
            V = ZkSeriesAuth.commit(new long[][] { vals }, new long[0][], s, rnd);
        }

        Scalar[] values() {
            Scalar[] v = new Scalar[vals.length];
            for (int i = 0; i < v.length; i++) v[i] = Group.ofUnsigned(vals[i]);
            return v;
        }

        BpppProver.Proof prove(Consumer<CircuitBuilder> stmt, boolean dishonest) {
            CircuitBuilder p = new CircuitBuilder(vals.length, values());
            p.lenient = dishonest;
            stmt.accept(p);
            return BpppProver.proveCircuit(Generators.create(p.nm()), p, s, V, rnd, !dishonest);
        }

        boolean verify(Consumer<CircuitBuilder> stmt, BpppProver.Proof pf) {
            CircuitBuilder v = new CircuitBuilder(vals.length, null);
            stmt.accept(v);
            return BpppVerifier.verifyCircuit(Generators.create(v.nm()), v, V, pf);
        }

        /** Proves honestly or, if dishonest, without the witness check; then verifies. */
        boolean run(Consumer<CircuitBuilder> stmt, boolean dishonest) { return verify(stmt, prove(stmt, dishonest)); }
    }

    static void ranges() {
        Consumer<CircuitBuilder> inB = cb -> { for (int i = 0; i < cb.inputs(); i++) ZkSeriesAuth.inBound(cb, cb.input(i), B); };
        check("range: 0, 1, 7999, 8000 in [0, B] verify", new Inputs(0, 1, 7999, 8000).run(inB, false));
        check("range: 8001 is refused by the prover", throwsAny(() -> new Inputs(5, 8001).prove(inB, false)));
        check("range: 8001 proven anyway is rejected", !new Inputs(5, 8001).run(inB, true));
        check("range: 2^16 + 5 proven anyway is rejected", !new Inputs(5, (1 << 16) + 5).run(inB, true));
        Consumer<CircuitBuilder> in16 = cb -> { for (int i = 0; i < cb.inputs(); i++) ZkSeriesAuth.inBound(cb, cb.input(i), (1L << 16) - 1); };
        check("range: 0, 1, 65534, 65535 in [0, 2^16 - 1] verify", new Inputs(0, 1, 65534, 65535).run(in16, false));
        check("range: [0, 2^16 - 1] is one statement per value", digitsOf(in16, 3) == 3 * ZkSeriesAuth.READING_DIGITS);
        check("range: 65536 proven anyway is rejected", !new Inputs(5, 65536).run(in16, true));
        check("range: -1 proven anyway is rejected", !new Inputs(5, -1L).run(in16, true));
        Consumer<CircuitBuilder> wide = cb -> cb.range(cb.input(0), 8);
        check("range: 2^64 - 1 in 8 digits verifies", new Inputs(-1L).run(wide, false));
        Inputs one = new Inputs(42);
        BpppProver.Proof pf = one.prove(cb -> cb.range(cb.input(0), 1), false);
        check("range: the proof is bound to its statement", !one.verify(cb -> cb.range(cb.input(0).add(1), 1), pf));
        check("range: and to its commitments", !new Inputs(42).verify(cb -> cb.range(cb.input(0), 1), pf));
    }

    /** Digits the statement allocates for n inputs. */
    static int digitsOf(Consumer<CircuitBuilder> stmt, int n) {
        CircuitBuilder cb = new CircuitBuilder(n, null);
        stmt.accept(cb);
        return cb.digits();
    }

    static void gadgets() {
        long x = 100, y = 350;                                   // |x - y| = 250
        for (long claim : new long[] { 250, 251, -250 }) {
            Scalar a = Group.of(claim);
            boolean ok = new Inputs(x, y).run(cb -> ZkSeriesAuth.abs(cb, cb.input(0).sub(cb.input(1)), cb.isProver() ? a : null), claim != 250);
            check("|x - y| claimed as " + claim + (claim == 250 ? " verifies" : " is rejected"), ok == (claim == 250));
        }
        for (long claim : new long[] { 30, 29, 31 }) {           // max(10, 30, 20) = 30
            Scalar z = Group.of(claim);
            boolean ok = new Inputs(10, 30, 20).run(cb -> ZkSeriesAuth.max(cb,
                    new CircuitBuilder.Lin[] { cb.input(0), cb.input(1), cb.input(2) }, cb.isProver() ? z : null), claim != 30);
            check("max claimed as " + claim + (claim == 30 ? " verifies" : " is rejected"), ok == (claim == 30));
        }
        check("product: 6 * 7 = 42 verifies", new Inputs(6, 7, 42).run(cb -> cb.mulEq(cb.input(0), cb.input(1), cb.input(2)), false));
        check("product: 6 * 7 = 43 is rejected", !new Inputs(6, 7, 43).run(cb -> cb.mulEq(cb.input(0), cb.input(1), cb.input(2)), true));
    }

    static void tampering() {
        Inputs in = new Inputs(1, 2, 3);
        Consumer<CircuitBuilder> stmt = cb -> { for (int i = 0; i < 3; i++) ZkSeriesAuth.inBound(cb, cb.input(i), B); };
        String[] parts = { "C_L", "C_O", "C_R", "C_S", "first X", "last R", "l", "n" };
        for (String part : parts) {
            BpppProver.Proof pf = in.prove(stmt, false);
            check("tampering: honest proof verifies", in.verify(stmt, pf));
            ECPoint G = Generators.create(1).G;
            NormLinearArgument.Proof nla = pf.nla;
            switch (part) {
                case "C_L": pf.CL = pf.CL.add(G); break;
                case "C_O": pf.CO = pf.CO.add(G); break;
                case "C_R": pf.CR = pf.CR.add(G); break;
                case "C_S": pf.CS = pf.CS.add(G); break;
                case "first X": nla.X.set(0, nla.X.get(0).add(G)); break;
                case "last R": nla.R.set(nla.R.size() - 1, nla.R.get(nla.R.size() - 1).add(G)); break;
                case "l": nla.l = nla.l.add(Group.one()); break;
                default: nla.n = nla.n.add(Group.one()); break;
            }
            check("tampering: a changed " + part + " is rejected", !in.verify(stmt, pf));
        }
    }

    // ---- the ZK-Series authentication proof -----------------------------------

    static void authentication() {
        for (int m : new int[] { 1, 3 })
            for (SeriesDistType s : new SeriesDistType[] { SeriesDistType.SUM, SeriesDistType.DTW })
                for (LocalDistType d : LocalDistType.values()) authCase(m, s, d);
    }

    static long[][] reading(int T, int m) {
        long[][] r = new long[T][m];
        for (long[] e : r) for (int j = 0; j < m; j++) e[j] = rnd.nextInt((int) B + 1);
        return r;
    }

    static void authCase(int m, SeriesDistType s, LocalDistType d) {
        String n = "auth " + s + " " + d + " m=" + m + ": ";
        int tx = 5, ty = s == SeriesDistType.SUM ? 5 : 4;
        long[][] x = reading(tx, m), y = reading(ty, m);
        int[][] c = ZkSeriesAuth.coupling(s, d, x, y);
        long cost = ZkSeriesAuth.cost(new ZkSeriesAuth.Statement(m, tx, ty, d, s, B, 1, c), x, y);
        long dist = SeriesDist.distance(x, y, s, 0, (a, b) -> LocalDist.distance(a, b, d)).distance;
        check(n + "the coupling is valid", ZkSeriesAuth.validCoupling(new ZkSeriesAuth.Statement(m, tx, ty, d, s, B, 1, c)));
        check(n + "its cost equals SeriesDist's distance", cost == dist);

        ZkSeriesAuth.Statement st = new ZkSeriesAuth.Statement(m, tx, ty, d, s, B, cost + 1, c);
        ZkSeriesAuth.Statement tight = new ZkSeriesAuth.Statement(m, tx, ty, d, s, B, cost, c);
        Scalar[] sV = new Scalar[st.inputs()];
        List<ECPoint> V = ZkSeriesAuth.commit(x, y, sV, rnd);
        Generators g = ZkSeriesAuth.generators(st);
        BpppProver.Proof pf = ZkSeriesAuth.prove(g, st, x, y, sV, V, rnd);
        check(n + "delta < delta + 1 verifies", ZkSeriesAuth.verify(g, st, V, pf));
        check(n + "the proof does not verify for theta = delta", !ZkSeriesAuth.verify(ZkSeriesAuth.generators(tight), tight, V, pf));
        check(n + "theta = delta is refused by the prover", throwsAny(() -> ZkSeriesAuth.prove(ZkSeriesAuth.generators(tight), tight, x, y, sV, V, rnd)));
        Generators gt = ZkSeriesAuth.generators(tight);
        BpppProver.Proof forced = BpppProver.proveCircuit(gt, ZkSeriesAuth.builder(tight, x, y, true), sV, V, rnd, false);
        check(n + "theta = delta proven anyway is rejected", !ZkSeriesAuth.verify(gt, tight, V, forced));
        List<ECPoint> V2 = new ArrayList<>(V);
        V2.set(V2.size() - 1, V2.get(V2.size() - 1).add(Generators.create(1).G));
        check(n + "another new reading is rejected", !ZkSeriesAuth.verify(g, st, V2, pf));
        if (s == SeriesDistType.DTW) {
            int[][] skip = { { 0, 0 }, { 2, 1 }, { 3, 2 }, { 4, 3 } };                 // skips (1, *)
            check(n + "a coupling that skips a cell is not valid",
                    !ZkSeriesAuth.validCoupling(new ZkSeriesAuth.Statement(m, tx, ty, d, s, B, 1L << 40, skip)));
        }
    }
}
