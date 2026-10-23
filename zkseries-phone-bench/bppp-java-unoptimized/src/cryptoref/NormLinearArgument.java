package cryptoref;

import ec.ECPoint;
import ec.Scalar;

import java.util.ArrayList;
import java.util.List;

/**
 * The weighted norm linear argument of Section 4.3, for the relation of Eq. (15):
 *
 *   C = vG + <n, bold G> + <l, bold H>,   v = |n|^2_mu + <c, l>,   |n|^2_mu = sum_i mu^(i+1) n_i^2,
 *
 * with mu = rho^2.
 *
 * <b>Translated from vetted code.</b> This class is a line-by-line translation of
 * src/modules/bppp/bppp_norm_product_impl.h of BlockstreamResearch/secp256k1-zkp at
 * commit 8e1f96c20e16bf960be6caec7f3c94acb360cdf2 (a copy is in ../../reference/). That
 * code was merged in PR #205 ("Bulletproofs++: Norm argument", Kanjalkar), reviewed and
 * merged by Nick in February 2023. Comments cite it as [C k] for line k, and the
 * paper's box as [4.3 k] and by equation number where the two meet.
 *
 * <b>Differences from the C, and why.</b>
 * <ul>
 * <li>Group and transcript. Ristretto255 instead of secp256k1. Each round absorbs X and R
 *     into this project's Transcript (SHA-512, labeled) instead of SHA-256 over the 65-byte
 *     serialized pair [C 320-321]; the challenge is drawn at the same point.</li>
 * <li>Schedule. The C runs while g_len > 1 || h_len > 1 [C 276], i.e. max(log2|n|, log2|l|)
 *     rounds. If n reached length 1 before l, l would keep folding against a single
 *     n-generator, and a prover could hide a term b * bold G_0 in X, absorbing any defect
 *     that is mu times a square. This class therefore requires |n| >= |l| (always true
 *     here: |n| = N_m >= 256, |l| = 8), under which the C's loop is exactly "while
 *     |n| > 1" and the gap cannot arise. The paper's box stops earlier, while
 *     len(l) + len(n) >= 6; the C's schedule is followed instead.</li>
 * <li>Verifier input. verify() takes the commitment as a linear combination of points
 *     rather than one point [C 375-381], so that the circuit verifier can merge its
 *     computation of C into the same multiscalar multiplication; and it checks
 *     res1 - res2 = 0 in one multiplication instead of res1 = res2 in two [C 525-551].</li>
 * <li><i>Removed in this unoptimized copy:</i> the optimized version keeps bold G as sigma * G~
 *     and folds with one point multiplication per pair. Here bold G is folded as in the C,
 *     G' = rho G_0 + gamma G_1 [C 331-336], with two. The rest of this item describes the
 *     optimized version. The C folds G' = rho G_0 + gamma G_1
 *     with two point multiplications per pair [C 331-336]. The prover here keeps bold G
 *     as sigma * G~ for a scalar sigma (initially 1) and a vector G~, and folds
 *       G~' = G~_0 + (gamma / rho) G~_1,   sigma' = sigma * rho,
 *     one point multiplication per pair. This is the same G', since
 *       sigma' G~' = sigma rho G~_0 + sigma gamma G~_1 = rho G_0 + gamma G_1.
 *     Every point it is used in carries sigma on its scalar instead: X and R multiply
 *     each scalar on G~ by sigma. Bold G is used nowhere else, and the verifier is
 *     unaffected. The three lines involved are marked DEVIATION below. It makes the
 *     prover roughly 1.4-2x faster at the sizes of the paper.</li>
 * </ul>
 */
public final class NormLinearArgument {

    /** The messages: one (X, R) pair per round [C 316], then the final n and l [C 362-363]. */
    public static final class Proof {
        public final List<ECPoint> X = new ArrayList<>(), R = new ArrayList<>();
        public Scalar n, l;

        /** Wire size in bytes: 32 per point and per scalar. */
        public int wireSize() { return 32 * (X.size() + R.size() + 2); }
    }

    private NormLinearArgument() {}

    /** C = vG + <n, bold G> + <l, bold H> with v = |n|^2_mu + <c, l>: secp256k1_bppp_commit [C 105-151]. */
    public static ECPoint commit(ECPoint G, ECPoint[] Gv, ECPoint[] H, Scalar[] c, Scalar mu, Scalar[] l, Scalar[] n) {
        Scalar v = wip(n, 0, n, 0, 1, n.length, mu).add(ip(l, 0, c, 0, 1, l.length));    // [C 133-135]
        List<Scalar> s = new ArrayList<>(); List<ECPoint> p = new ArrayList<>();
        s.add(v); p.add(G);
        for (int i = 0; i < n.length; i++) { s.add(n[i]); p.add(Gv[i]); }
        for (int i = 0; i < l.length; i++) { s.add(l[i]); p.add(H[i]); }
        return Group.msm(s, p);                                                               // [C 145]
    }

    static void checkShape(int gLen, int hLen) {
        if (Integer.bitCount(gLen) != 1 || Integer.bitCount(hLen) != 1)                       // [C 129-130], [C 459]
            throw new IllegalArgumentException("|n| and |l| must be powers of two");
        if (gLen < hLen) throw new IllegalArgumentException("|n| >= |l| is required (see the class comment)");
    }

    /** The number of rounds: log2 |n|, given |n| >= |l| [C 451-453]. */
    public static int rounds(int gLen) { return Integer.numberOfTrailingZeros(gLen); }

    /** secp256k1_bppp_rangeproof_norm_product_prove [C 223-367]. */
    public static Proof prove(Transcript tr, ECPoint G, ECPoint[] Gv, ECPoint[] H, Scalar[] c,
                              Scalar rho, Scalar[] l, Scalar[] n) {
        int gLen = n.length, hLen = l.length;
        checkShape(gLen, hLen);
        if (Gv.length != gLen || H.length != hLen || c.length != hLen) throw new IllegalArgumentException("lengths");
        Gv = Gv.clone(); H = H.clone(); c = c.clone(); l = l.clone(); n = n.clone();           // the C works in place
        Proof pf = new Proof();
        Scalar rhoF = rho, muF = rho.square();                                                // [C 273]
        while (gLen > 1 || hLen > 1) {                                                        // [C 276]
            Scalar rhoInv = rhoF.invert();                                                    // [C 283]
            Scalar muSq = muF.square();                                                       // [C 284]

            // X = x_v G + <rho n_1, G_0> + <rho_inv n_0, G_1> + <l_1, H_0> + <l_0, H_1>   [C 286], Eqs. (19), (21)
            Scalar c0l1 = ip(c, 0, l, 1, 2, hLen / 2);                                         // [C 287]
            Scalar c1l0 = ip(c, 1, l, 0, 2, hLen / 2);                                         // [C 288]
            Scalar xv = wip(n, 0, n, 1, 2, gLen / 2, muSq).multiply(rhoInv);                  // [C 289-290]
            xv = xv.add(xv).add(c0l1).add(c1l0);                                              // [C 291-293]
            List<Scalar> xs = new ArrayList<>(); List<ECPoint> xp = new ArrayList<>();
            xs.add(xv); xp.add(G);
            if (gLen >= 2)                                                                    // [C 297], ecmult_x_cb [C 163-184]
                for (int i = 0; i < gLen; i++) {
                    xs.add(i % 2 == 0 ? n[i + 1].multiply(rhoF) : n[i - 1].multiply(rhoInv)); xp.add(Gv[i]);
                }
            if (hLen >= 2)                                                                    // [C 298]
                for (int i = 0; i < hLen; i++) { xs.add(i % 2 == 0 ? l[i + 1] : l[i - 1]); xp.add(H[i]); }
            ECPoint X = Group.msm(xs, xp);                                                    // [C 300]

            // R = r_v G + <n_1, G_1> + <l_1, H_1>                                             Eqs. (20), (22)
            Scalar rv = wip(n, 1, n, 1, 2, gLen / 2, muSq).add(ip(c, 1, l, 1, 2, hLen / 2));    // [C 304-306]
            List<Scalar> rs = new ArrayList<>(); List<ECPoint> rp = new ArrayList<>();
            rs.add(rv); rp.add(G);
            for (int i = 0; i < gLen / 2; i++) { rs.add(n[2 * i + 1]); rp.add(Gv[2 * i + 1]); } // ecmult_r_cb [C 194-205]
            for (int i = 0; i < hLen / 2; i++) { rs.add(l[2 * i + 1]); rp.add(H[2 * i + 1]); }
            ECPoint R = Group.msm(rs, rp);                                                    // [C 310]

            pf.X.add(X); pf.R.add(R);                                                         // [C 316], [4.3 2.1] P -> V: X, R
            Scalar gamma = roundChallenge(tr, X, R);                                          // [C 320-321], [4.3 2.2] V -> P: gamma

            if (gLen > 1) {                                                                   // [C 323]
                for (int i = 0; i < gLen; i += 2) {
                    n[i / 2] = n[i].multiply(rhoInv).add(n[i + 1].multiply(gamma));           // [C 327-329] n' = rho_inv n_0 + gamma n_1
                    Gv[i / 2] = Gv[i].multiply(rhoF).add(Gv[i + 1].multiply(gamma));          // [C 331-336] G' = rho G_0 + gamma G_1
                }
            }
            if (hLen > 1)                                                                     // [C 340]
                for (int i = 0; i < hLen; i += 2) {
                    c[i / 2] = c[i].add(c[i + 1].multiply(gamma));                            // [C 344-345] c' = c_0 + gamma c_1
                    l[i / 2] = l[i].add(l[i + 1].multiply(gamma));                            // [C 347-348] l' = l_0 + gamma l_1
                    H[i / 2] = H[i].add(H[i + 1].multiply(gamma));                            // [C 350-353] H' = H_0 + gamma H_1
                }
            gLen /= 2;                                                                        // [C 356]
            hLen /= 2;                                                                        // [C 357]
            rhoF = muF;                                                                       // [C 358] rho' := mu
            muF = muSq;                                                                       // [C 359] mu' := mu^2
        }
        pf.n = n[0];                                                                          // [C 362]
        pf.l = l[0];                                                                          // [C 363]
        return pf;
    }

    /**
     * secp256k1_bppp_rangeproof_norm_product_verify [C 425-552], for a commitment given as the
     * linear combination C = sum_i cs_i cp_i. Never throws.
     */
    public static boolean verify(Transcript tr, ECPoint G, ECPoint[] Gv, ECPoint[] H, Scalar[] c,
                                 Scalar rho, List<Scalar> cs, List<ECPoint> cp, Proof pf) {
        try {
            int gLen = Gv.length, hLen = c.length;
            checkShape(gLen, hLen);
            if (H.length != hLen) return false;
            int logG = rounds(gLen), nRounds = logG;                                          // [C 451-453]
            if (pf.X.size() != nRounds || pf.R.size() != nRounds) return false;               // [C 455]
            if (Group.isZero(rho)) return false;                                              // [C 467]

            Scalar rhoInv = rho.invert();                                                     // [C 481]
            Scalar[] rhoInvPows = new Scalar[logG];                                           // [C 482] rho_inv^(2^k)
            for (int k = 0; k < logG; k++) rhoInvPows[k] = k == 0 ? rhoInv : rhoInvPows[k - 1].square();
            Scalar rhoF = rho;                                                                // [C 485-488] rho_f = rho^(2^logG)
            for (int k = 0; k < logG; k++) rhoF = rhoF.square();
            Scalar[] gammas = new Scalar[nRounds];
            for (int k = 0; k < nRounds; k++) gammas[k] = roundChallenge(tr, pf.X.get(k), pf.R.get(k));  // [C 490-495]

            Scalar[] sG = new Scalar[gLen];
            sG[0] = pf.n.multiply(rhoF).multiply(rhoInv);                                     // [C 499-500]
            for (int i = 1; i < gLen; i++) {                                                  // [C 501-510]
                int logI = 31 - Integer.numberOfLeadingZeros(i);
                sG[i] = sG[i - (1 << logI)].multiply(gammas[logI]).multiply(rhoInvPows[logI]);
            }
            Scalar[] sH = new Scalar[hLen];
            sH[0] = pf.l;                                                                     // [C 511]
            for (int i = 1; i < hLen; i++) {                                                  // [C 513-517]
                int logI = 31 - Integer.numberOfLeadingZeros(i);
                sH[i] = sH[i - (1 << logI)].multiply(gammas[logI]);
            }
            Scalar hc = ip(c, 0, sH, 0, 1, hLen);                                             // [C 518]
            Scalar muF = rhoF.square();                                                       // [C 520]
            Scalar v = pf.n.multiply(pf.n).multiply(muF).add(hc);                             // [C 521-523]

            // res1 = C + sum_k gamma_k X_k + (gamma_k^2 - 1) R_k        [C 525-535], ec_mult_verify_cb1 [C 375-402]
            // res2 = v G + <s_g, bold G> + <s_h, bold H>                [C 536-547], ec_mult_verify_cb2 [C 411-420]
            // accept iff res1 - res2 = 0                                [C 551]
            List<Scalar> s = new ArrayList<>(cs); List<ECPoint> p = new ArrayList<>(cp);
            for (int k = 0; k < nRounds; k++) {
                s.add(gammas[k]); p.add(pf.X.get(k));
                s.add(gammas[k].square().subtract(Group.one())); p.add(pf.R.get(k));
            }
            s.add(Group.neg(v)); p.add(G);
            for (int i = 0; i < gLen; i++) { s.add(Group.neg(sG[i])); p.add(Gv[i]); }
            for (int i = 0; i < hLen; i++) { s.add(Group.neg(sH[i])); p.add(H[i]); }
            return Group.eq(Group.msm(s, p), Group.identity());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** One round of the schedule: gamma is drawn after X and R ([C 320-321], [4.3 2.1/2.2]). */
    static Scalar roundChallenge(Transcript tr, ECPoint X, ECPoint R) {
        tr.append("nla.X", X);
        tr.append("nla.R", R);
        return tr.challenge("nla.gamma");
    }

    /** secp256k1_scalar_inner_product [C 24-41]: sum_i a[aOff + step i] b[bOff + step i]. */
    static Scalar ip(Scalar[] a, int aOff, Scalar[] b, int bOff, int step, int len) {
        Scalar acc = Group.zero();
        for (int i = 0; i < len; i++) acc = acc.add(a[aOff + step * i].multiply(b[bOff + step * i]));
        return acc;
    }

    /** secp256k1_weighted_scalar_inner_product [C 47-69]: sum_i a[..] b[..] mu^(i+1). */
    static Scalar wip(Scalar[] a, int aOff, Scalar[] b, int bOff, int step, int len, Scalar mu) {
        Scalar acc = Group.zero(), w = mu;
        for (int i = 0; i < len; i++) { acc = acc.add(a[aOff + step * i].multiply(b[bOff + step * i]).multiply(w)); w = w.multiply(mu); }
        return acc;
    }
}
