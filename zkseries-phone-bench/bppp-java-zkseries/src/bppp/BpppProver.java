package bppp;

import crypto.Generators;
import crypto.Group;
import crypto.NormLinearArgument;
import crypto.Poly;
import crypto.Transcript;
import ec.ECPoint;
import ec.Scalar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The prover of the Bulletproofs++ arithmetic circuit protocol, Section 5.3 of Eagen,
 * Kanjalkar, Ruffing and Nick (ePrint 2022/510, revision of 17 July 2023), written
 * from the protocol boxes, inside the reciprocal-form wrapper of Section 6.3.
 *
 * <b>Line references.</b> Every line cites the box it implements:
 * <pre>
 *   [5.3 OL k]    CommitOL subroutine, line k counted top to bottom in the box:
 *                   1 r'_O, r'_L sampled   2 r_O :=   3 r_L :=   4 n_L :=
 *                   5 n_O (via F)           6 l_X (via F)          7 C_X :=   8 return
 *   [5.3 R k]     CommitR subroutine, line k:
 *                   1 r'_R sampled   2 r_R :=   3 n_R :=   4 l_R (via F)   5 C_R :=   6 return
 *   [5.3 IAC s.k] Inner Arithmetic Circuit Protocol, step s, line k of that step:
 *                   step 3: 1 M_{a,n,L}  2 M_{a,n,R}  3 W_{a,O}  4 M_{a,n,O}  5 M_{a,l,X}
 *                           6 mu  7 V^  8 lambda  9 mu (vector)  10 c_{n,X}  11 c_{l,X}
 *                           12 p_n(T)  13 p_s(T)
 *                   step 4: 1 l_S  2 n_S  3 v^  4 c^_l(T)  5 l^(T)  6 n^(T)  7 n(T)  8 f^(T)
 *                           9 f^ in F^8  10 r_V  11 s_r  12 r_S  13 C_S
 *                   step 7: 1 r(T)  2 v(T)  3 l(T)
 *                   step 8: 1 P(T)  2 c^_r(T)  3 c(T)  4 C(T)
 *   [6.3 k]       Reciprocal Form Protocol (Section 6.3), step k
 * </pre>
 *
 * <b>The circuit shape</b> is the one of BpppCircuit: N_v = 1, f_l = 1, f_m = 0, and F
 * places every w_O entry in n_O. Under it, the l-parts the box routes through F
 * (l_O, l_L, l_R, c_{l,X}) are zero, and so is c^_l(T); those lines are kept below,
 * marked "zero here", so that every box line still has its place.
 *
 * <b>One sign correction.</b> The box defines v(T) := p_s(T) + v^ T^3 + r_0(T)
 * [5.3 IAC 7.2], forced by the commitments carrying +r_X,0 G, and c(T) :=
 * c^_r,1:(T) || c^_l(T) [5.3 IAC 8.3]. Then the norm linear relation's defect is
 *
 *   v - <c, l> - |n|^2_mu = f^(T) + r_0(T) - <c^_r,1:(T), r_1:(T)>,
 *
 * which equals f^(T) - <c^_r(T), r(T)> -- what s_r and r_S are built to cancel
 * (Eqs. (58)-(60)) -- only if c^_r,0 = -1, not the printed 1. c^_r,0 never reaches
 * the verifier (c(T) drops it), so only the prover's r_S changes: s_r,0, the three
 * beta^-1 r_X,0 terms of s_r and f^_0 in r_S change sign. With the printed signs
 * every honest proof fails; the C reference implementation makes the same correction.
 *
 * <b>DEVIATION (prover speed): f^ by its expanded coefficients.</b> [5.3 IAC 4.8] defines
 * f^(T) := p_s(T) + v^ T^3 - <c^_l(T), l^(T)> - |n(T)|^2_mu, and [5.3 IAC 4.9] uses all its
 * coefficients except T^3. Here c^_l = 0 and p_s(T) = |p(T)|^2_mu + (const) T^3, so, apart from
 * T^3, f^(T) = |p(T)|^2_mu - |n(T)|^2_mu. With p(T) = p_1 T + p_2 T^2 + p_3 T^3 [5.3 IAC 3.12] and
 * n(T) = p(T) + a T^-1 + b + L T + R T^2 [5.3 IAC 4.6/4.7] (a = n_S, b = delta n_O, L = n_L,
 * R = n_R), write c = p_1 + L, d = p_2 + R, e = p_3 for the coefficients of n. Then, with every
 * inner product mu-weighted, the p-only terms cancel and
 *
 *   T^-2: -<a,a>                         T^2: -(<L,L> + 2<p_1,L> + 2<b,d> + 2<a,e>)
 *   T^-1: -2<a,b>                        T^4: -(<R,R> + 2<p_2,R> + 2<L,e>)
 *   T^0 : -(<b,b> + 2<a,c>)              T^5: -2<R,e>
 *   T^1 : -2(<a,d> + <b,c>)              T^6: 0
 *
 * blindingCoefficients() computes exactly these, instead of both full polynomial norms. v^
 * and the constant of p_s enter only T^3, the value term, which [5.3 IAC 4.9] leaves out, so
 * they are not computed here. They still reach the verifier through C(T) [5.3 IAC 8.1/8.4],
 * where the value term must vanish; the prover checks up front that the circuit is satisfied.
 * Mutating any coefficient makes honest proofs fail (Check).
 */
public final class BpppProver {

    /** The proof: the four commitments and the norm linear argument. */
    public static final class Proof {
        public ECPoint CL, CO, CR, CS;
        public NormLinearArgument.Proof nla;

        /** Wire size in bytes: 32 per point and per scalar. */
        public int wireSize() { return 32 * 4 + nla.wireSize(); }
    }

    private BpppProver() {}

    // ======================================================================
    // Section 6.3: the Reciprocal Form Protocol, for a CircuitBuilder statement
    // ======================================================================

    /** Proves the statement recorded in a prover's CircuitBuilder, over inputs V_i = v_i G + s_i H_0. */
    public static Proof proveCircuit(Generators g, CircuitBuilder cb, Scalar[] sV, List<ECPoint> V, Random rng) {
        return proveCircuit(g, cb, sV, V, rng, true);
    }

    /** As above; checkWitness = false lets the checks build dishonest proofs. */
    static Proof proveCircuit(Generators g, CircuitBuilder cb, Scalar[] sV, List<ECPoint> V, Random rng,
                              boolean checkWitness) {
        CircuitBuilder.Witness w = cb.witness();
        Transcript tr = transcript(cb, V);
        int nm = cb.nm();
        int active = cb.digits() + cb.gates();
        OL ol = commitOL(g, nm, w.wO, w.wL, rng, active);                               // [6.3 1], omit only public padding
        sendLO(tr, ol.CL, ol.CO);                                                      // [6.3 2] P -> V: C_L, C_O
        Scalar alpha = alpha(tr);                                                      // [6.3 3] V -> P: alpha
        Scalar[] wR = w.wR(alpha);                                                     // [6.3 4] w'_R := w'_P || w_R, w'_P,i = (alpha + w_D,i)^-1
        R r = commitR(g, wR, rng, active);                                             // [6.3 4]
        sendR(tr, r.CR);                                                               // [6.3 5] P -> V: C_R
        BpppCircuit c = cb.compile(alpha);                                             // [6.3 6] W'_l, W'_m, a'_m, a'_l
        if (checkWitness && !c.satisfiedBy(w.wL, wR, w.wO, w.v)) throw new IllegalArgumentException("witness does not satisfy the circuit");
        return inner(tr, g, c, ol, r, w.v, sV, rng);                                   // [6.3 7] run <P_iac, V_iac>
    }

    // ---- the Fiat-Shamir schedule, shared by prover and verifier ----
    // Each verifier message is a hash of everything sent before it. These helpers are the
    // one place that says what "before" means, so prover and verifier cannot drift apart.

    static Transcript transcript(CircuitBuilder cb, List<ECPoint> V) {
        Transcript tr = new Transcript("bppp-java-zkseries/reciprocal-circuit/v2");
        cb.absorb(tr);
        tr.append("V.count", V.size());
        for (ECPoint p : V) tr.append("V", p);
        return tr;
    }

    static void sendLO(Transcript tr, ECPoint CL, ECPoint CO) { tr.append("C_L", CL); tr.append("C_O", CO); }

    static Scalar alpha(Transcript tr) { return tr.challenge("alpha"); }

    static void sendR(Transcript tr, ECPoint CR) { tr.append("C_R", CR); }

    /** rho, lambda, beta, delta, drawn after C_L, C_R, C_O ([5.3 IAC 2]). */
    static Scalar[] challenges(Transcript tr) {
        return new Scalar[] { tr.challenge("rho"), tr.challenge("lambda"), tr.challenge("beta"), tr.challenge("delta") };
    }

    static void sendS(Transcript tr, ECPoint CS) { tr.append("C_S", CS); }

    static Scalar tau(Transcript tr) { return tr.challenge("tau"); }

    // ---- CommitOL -----------------------------------------------------------

    static final class OL {
        Scalar[] rO, rL, nO, nL;
        ECPoint CO, CL;
    }

    /** active = the gates in use; n_L is zero beyond them (padding gates, public). */
    static OL commitOL(Generators g, int nm, Scalar[] wO, Scalar[] wL, Random rng, int active) {
        OL o = new OL();
        Scalar[] rOp = Group.random(rng, 6), rLp = Group.random(rng, 5);                // [5.3 OL 1] r'_O <-$ F^6, r'_L <-$ F^5
        Scalar z = Group.zero();
        o.rO = new Scalar[] { rOp[0], rOp[1], rOp[2], rOp[3], z, rOp[4], rOp[5], z };  // [5.3 OL 2] r_O := (r'_O,0..3, 0, r'_O,4, r'_O,5, 0)
        o.rL = new Scalar[] { rLp[0], rLp[1], rLp[2], z, rLp[3], rLp[4], z, z };       // [5.3 OL 3] r_L := (r'_L,0..2, 0, r'_L,3, r'_L,4, 0, 0)
        o.nL = wL.clone();                                                             // [5.3 OL 4] n_L := w_L
        o.nO = Group.zeros(nm);                                                        // [5.3 OL 5] n_O,j := w_O,i if F^-1(n_O, j) = i, else 0
        System.arraycopy(wO, 0, o.nO, 0, wO.length);                                   //   (F places w_O,i at n_O,i)
        Scalar[] l0 = Group.zeros(1);                                                  // [5.3 OL 6] l_O, l_L: zero here
        o.CO = vectorCommit(g, o.rO, l0, o.nO, wO.length);                             // [5.3 OL 7] C_O := r_O,0 G + <r_O,1:||l_O, H> + <n_O, G>
        o.CL = vectorCommit(g, o.rL, l0, o.nL, active);                                // [5.3 OL 7] C_L := r_L,0 G + <r_L,1:||l_L, H> + <n_L, G>
        return o;                                                                      // [5.3 OL 8] return r_O, r_L, n_O, n_L, l_O, l_L, C_O, C_L
    }

    // ---- CommitR ------------------------------------------------------------

    static final class R {
        Scalar[] rR, nR;
        ECPoint CR;
    }

    /** active = the gates in use; n_R is zero beyond them (padding gates, public). */
    static R commitR(Generators g, Scalar[] wR, Random rng, int active) {
        R o = new R();
        Scalar[] rRp = Group.random(rng, 4);                                           // [5.3 R 1] r'_R <-$ F^4
        Scalar z = Group.zero();
        o.rR = new Scalar[] { rRp[0], rRp[1], z, rRp[2], rRp[3], z, z, z };            // [5.3 R 2] r_R := (r'_R,0, r'_R,1, 0, r'_R,2, r'_R,3, 0, 0, 0)
        o.nR = wR.clone();                                                             // [5.3 R 3] n_R := w_R
        Scalar[] lR = Group.zeros(1);                                                  // [5.3 R 4] l_R: zero here
        o.CR = vectorCommit(g, o.rR, lR, o.nR, active);                                // [5.3 R 5] C_R := r_R,0 G + <r_R,1:||l_R, H> + <n_R, G>
        return o;                                                                      // [5.3 R 6] return r_R, n_R, l_R, C_R
    }

    /** r_0 G + <r_1: || l, H> + <n, bold G>: the commitment form of [5.3 OL 7], [5.3 R 5], [5.3 IAC 4.13]. */
    static ECPoint vectorCommit(Generators g, Scalar[] r, Scalar[] l, Scalar[] n) {
        return vectorCommit(g, r, l, n, n.length);
    }

    /**
     * As above, with bold G multiplied only for the first count entries of n. The rest must be
     * zero (checked), so the point is the same: they are padding gates and unused w_O slots,
     * whose positions are public and never depend on the witness.
     */
    private static ECPoint vectorCommit(Generators g, Scalar[] r, Scalar[] l, Scalar[] n, int count) {
        if (count < 0 || count > n.length || count > g.Gvec.length) throw new IllegalArgumentException("commitment prefix");
        for (int i = count; i < n.length; i++)
            if (!Group.isZero(n[i])) throw new IllegalStateException("nonzero entry " + i + " in the public zero padding");
        List<Scalar> s = new ArrayList<>(count + 9); List<ECPoint> p = new ArrayList<>(count + 9);
        s.add(r[0]); p.add(g.G);
        for (int i = 1; i < 8; i++) { s.add(r[i]); p.add(g.H[i - 1]); }
        s.add(l[0]); p.add(g.H[7]);
        for (int i = 0; i < count; i++) { s.add(n[i]); p.add(g.Gvec[i]); }
        return Group.msm(s, p);
    }

    // ---- Step 3 of the inner protocol: computed by P and V alike -------------

    /** Everything [5.3 IAC 3] computes. */
    static final class Step3 {
        Scalar mu;
        Scalar[] wIn;                   // lambda^i: the weight of input i (f_l = 1, f_m = 0, N_v = 1)
        Scalar[] lambdaVec, muVec;
        Scalar[] cnL, cnR, cnO;
        Poly.V pn;
        Poly.S ps;
        Scalar psOffset;                // coefficient of T^3 outside |p_n(T)|^2_mu
    }

    static Step3 step3(BpppCircuit c, Scalar rho, Scalar lambda, Scalar delta) {
        return step3(c, rho, lambda, delta, true);
    }

    /** The verifier needs the norm at tau, not the entire norm polynomial. */
    static Step3 step3ForVerifier(BpppCircuit c, Scalar rho, Scalar lambda, Scalar delta) {
        return step3(c, rho, lambda, delta, false);
    }

    private static Step3 step3(BpppCircuit c, Scalar rho, Scalar lambda, Scalar delta, boolean polynomialNorm) {
        Step3 t = new Step3();
        // [5.3 IAC 3.1-3.5] M_{a,n,L}, M_{a,n,R}, W_{a,O}, M_{a,n,O}, M_{a,l,X} are the column
        // blocks of W_a routed by F; they are applied in the loop below (M_{a,l,X} is zero here).
        t.mu = rho.square();                                                           // [5.3 IAC 3.6] mu := rho^2
        t.wIn = Group.powersFrom0(lambda, c.k);                                        // [5.3 IAC 3.7] the weights of V^ (V^ itself: BpppVerifier)
        t.lambdaVec = Group.powersFrom0(lambda, c.nl);                                 // [5.3 IAC 3.8] lambda := e_Nl(lambda)  (f_l f_m = 0)
        t.muVec = Group.powersFrom1(t.mu, c.nm);                                       // [5.3 IAC 3.9] mu := mu e_Nm(mu)
        t.cnL = Group.zeros(c.nm); t.cnR = Group.zeros(c.nm); t.cnO = Group.zeros(c.nm);
        for (int row = 0; row < c.nl; row++) route(c, t, c.wl.get(row), t.lambdaVec[row]);  // [5.3 IAC 3.10] lambda^T M_{l,n,X}
        for (int row = 0; row < c.nm; row++) route(c, t, c.wm.get(row), t.muVec[row]);      // [5.3 IAC 3.10] + mu^T M_{m,n,X}
        Scalar[] muInv = Group.powersFrom1(t.mu.invert(), c.nm);
        for (int j = 0; j < c.nm; j++) {                                               // [5.3 IAC 3.10] ... diag(mu)^-1
            t.cnL[j] = t.cnL[j].multiply(muInv[j]); t.cnR[j] = t.cnR[j].multiply(muInv[j]); t.cnO[j] = t.cnO[j].multiply(muInv[j]);
        }
                                                                                       // [5.3 IAC 3.11] c_{l,X}: zero here
        t.pn = new Poly.V(c.nm)                                                        // [5.3 IAC 3.12] p_n(T) := delta^-1 T^3 c_{n,O} + T^2 c_{n,L} + T c_{n,R}
                .addTerm(3, delta.invert(), t.cnO).addTerm(2, Group.one(), t.cnL).addTerm(1, Group.one(), t.cnR);
        t.psOffset = Group.ip(t.lambdaVec, c.al).add(Group.ip(t.muVec, c.am));
        if (polynomialNorm) t.ps = Poly.normSq(t.pn, t.muVec)                           // [5.3 IAC 3.13]
                .addTerm(3, t.psOffset);
        return t;
    }

    /** Adds weight * W_{a,row} into the c_n vectors; F sends w_O entry i to n_O position i. */
    private static void route(BpppCircuit c, Step3 t, Map<Integer, Scalar> row, Scalar weight) {
        for (Map.Entry<Integer, Scalar> e : row.entrySet()) {
            int col = e.getKey();
            Scalar v = weight.multiply(e.getValue());
            if (col < c.nm) t.cnL[col] = t.cnL[col].add(v);                            // [5.3 IAC 3.1] M_{a,n,L}
            else if (col < 2 * c.nm) t.cnR[col - c.nm] = t.cnR[col - c.nm].add(v);     // [5.3 IAC 3.2] M_{a,n,R}
            else t.cnO[col - 2 * c.nm] = t.cnO[col - 2 * c.nm].add(v);                 // [5.3 IAC 3.3/3.4] W_{a,O} = M_{a,n,O}
        }
    }

    // ---- The Inner Arithmetic Circuit Protocol <P_iac, V_iac> -----------------

    static Proof inner(Transcript tr, Generators g, BpppCircuit c, OL ol, R r, Scalar[] v, Scalar[] sV, Random rng) {
        Proof pf = new Proof();
        pf.CL = ol.CL; pf.CO = ol.CO; pf.CR = r.CR;                                    // [5.3 IAC 1] P -> V: C_L, C_R, C_O (already absorbed)
        Scalar[] ch = challenges(tr);                                                  // [5.3 IAC 2] V -> P: rho, lambda, beta, delta
        Scalar rho = ch[0], beta = ch[2], delta = ch[3];
        Step3 t = step3ForVerifier(c, rho, ch[1], delta);                              // [5.3 IAC 3], norm coefficients canceled below
        Scalar two = Group.of(2), betaInv = beta.invert();

        Scalar[] lS = Group.random(rng, 1);                                            // [5.3 IAC 4.1] l_S <-$ F^Nv
        Scalar[] nS = Group.random(rng, c.nm);                                         // [5.3 IAC 4.2] n_S <-$ F^Nm
        // [5.3 IAC 4.3] v^ contributes only to T^3, which is omitted from f^ [4.9].
        // That coefficient remains enforced by the circuit and norm verification equation.
                                                                                       // [5.3 IAC 4.4] c^_l(T): zero here
        Poly.V lHat = new Poly.V(1).addTerm(-1, Group.one(), lS);                      // [5.3 IAC 4.5] l^(T) := T^-1 l_S  (l_O, l_L, l_R zero here)
        Poly.V nHat = new Poly.V(c.nm)                                                 // [5.3 IAC 4.6] n^(T) := T^-1 n_S + delta n_O + T n_L + T^2 n_R
                .addTerm(-1, Group.one(), nS).addTerm(0, delta, ol.nO)
                .addTerm(1, Group.one(), ol.nL).addTerm(2, Group.one(), r.nR);
        Poly.V n = t.pn.add(nHat);                                                     // [5.3 IAC 4.7] n(T) := p_n(T) + n^(T)
        Scalar[] f = blindingCoefficients(t.pn, n, t.muVec, ol.nL, r.nR);              // [5.3 IAC 4.8/4.9] f^; DEVIATION: expanded (class comment)
        Scalar[] rV = Group.zeros(8);
        rV[1] = two.multiply(Group.ip(t.wIn, sV));                                     // [5.3 IAC 4.10] r_V := (0, 2 sum_i lambda^i s_V,i, 0, ..., 0)
        Scalar[] rO = ol.rO, rL = ol.rL, rR = r.rR;
        // [5.3 IAC 4.11/4.12] with c^_r,0 = -1 (SIGN CORRECTION, see the class comment): the
        // terms carrying c^_r,0 -- s_r,0, the three beta^-1 r_X,0 terms, and f^_0 in r_S -- flip.
        Scalar[] sr = new Scalar[] {                                                   // [5.3 IAC 4.11] s_r :=
                Group.neg(beta.multiply(delta).multiply(rO[1])),                       //   -beta delta r_O,1              (printed: +)
                Group.zero(),                                                          //   0
                Group.neg(betaInv.multiply(delta).multiply(rO[0])).add(rL[1]),         //   -beta^-1 delta r_O,0 + r_L,1   (printed: +beta^-1 ...)
                delta.multiply(rO[2]).subtract(betaInv.multiply(rL[0])).add(rR[1]),    //   delta r_O,2 - beta^-1 r_L,0 + r_R,1
                delta.multiply(rO[3]).add(rL[2]).add(rV[1]).subtract(betaInv.multiply(rR[0])), // delta r_O,3 + r_L,2 + r_V,1 - beta^-1 r_R,0
                rL[4].add(rR[3]),                                                      //   r_L,4 + r_R,3
                delta.multiply(rO[5]).add(rR[4]),                                      //   delta r_O,5 + r_R,4
                delta.multiply(rO[6]).add(rL[5]) };                                    //   delta r_O,6 + r_L,5
        Scalar[] rS = new Scalar[8];
        rS[0] = Group.neg(f[0]).subtract(sr[0]);                                       // [5.3 IAC 4.12] r_S := (-f^_0 || beta^-1 f^_1:) - s_r   (printed: +f^_0)
        for (int i = 1; i < 8; i++) rS[i] = betaInv.multiply(f[i]).subtract(sr[i]);    // [5.3 IAC 4.12]
        pf.CS = vectorCommit(g, rS, lS, nS);                                           // [5.3 IAC 4.13] C_S := r_S,0 G + <r_S,1:||l_S, H> + <n_S, G>
        sendS(tr, pf.CS);                                                              // [5.3 IAC 5] P -> V: C_S
        Scalar tau = tau(tr);                                                          // [5.3 IAC 6] V -> P: tau

        Poly.V rT = new Poly.V(8)                                                      // [5.3 IAC 7.1] r(T) := T^-1 r_S + delta r_O + T r_L + T^2 r_R + T^3 r_V
                .addTerm(-1, Group.one(), rS).addTerm(0, delta, rO).addTerm(1, Group.one(), rL)
                .addTerm(2, Group.one(), rR).addTerm(3, Group.one(), rV);
        Scalar[] rTau = rT.eval(tau);
        Scalar[] lTau = new Scalar[8];                                                 // [5.3 IAC 7.3] l(T) := r_1:(T) || l^(T)
        System.arraycopy(rTau, 1, lTau, 0, 7);
        lTau[7] = lHat.eval(tau)[0];
        Scalar[] nTau = n.eval(tau);
        Scalar[] cTau = cVector(beta, tau);                                            // [5.3 IAC 8.2/8.3] c(tau)
        pf.nla = NormLinearArgument.prove(tr, g.G, g.Gvec, g.H, cTau, rho, lTau, nTau,
                g.publicFoldTables());                                                       // [5.3 IAC 9], cached public first round
        return pf;
    }

    /** c(T) := c^_r,1:(T) || c^_l(T) at T = tau, with c^_r(T) := (1, beta T^-1, beta T, beta T^2, beta T^3, beta T^5, beta T^6, beta T^7). */
    static Scalar[] cVector(Scalar beta, Scalar tau) {
        return new Scalar[] {                                                          // [5.3 IAC 8.2] c^_r,1:(tau)
                beta.multiply(tau.invert()), beta.multiply(tau), beta.multiply(Group.pow(tau, 2)),
                beta.multiply(Group.pow(tau, 3)), beta.multiply(Group.pow(tau, 5)),
                beta.multiply(Group.pow(tau, 6)), beta.multiply(Group.pow(tau, 7)),
                Group.zero() };                                                        // [5.3 IAC 8.3] || c^_l(tau), zero here
    }

    /** Coefficients of |p|^2_mu - |n|^2_mu used in f^, with n=p+a/T+b+L*T+R*T^2.
     * The cubic constant (psOffset+vHat) only affects the deliberately omitted T^3 term.
     * Cancel common p terms before multiplying. Four weighted vectors are shared across
     * fourteen dot products, instead of computing two complete polynomial norms.
     */
    static Scalar[] blindingCoefficients(Poly.V p, Poly.V n, Scalar[] mu, Scalar[] left, Scalar[] right) {
        int length=p.n;
        if(n.n!=length||mu.length!=length||left.length!=length||right.length!=length)
            throw new IllegalArgumentException("coefficient lengths");
        Scalar[] a=n.coef(-1),b=n.coef(0),c=n.coef(1),d=n.coef(2),e=p.coef(3);
        Scalar[] aw=new Scalar[length],bw=new Scalar[length],lw=new Scalar[length],rw=new Scalar[length];
        for(int i=0;i<length;i++) {
            aw[i]=a[i].multiply(mu[i]);bw[i]=b[i].multiply(mu[i]);
            lw[i]=left[i].multiply(mu[i]);rw[i]=right[i].multiply(mu[i]);
        }
        Scalar fMinus2=Group.neg(Group.ip(aw,a));
        Scalar fMinus1=Group.neg(twice(Group.ip(aw,b)));
        Scalar f0=Group.neg(Group.ip(bw,b).add(twice(Group.ip(aw,c))));
        Scalar f1=Group.neg(twice(Group.ip(aw,d).add(Group.ip(bw,c))));
        Scalar f2=Group.neg(Group.ip(lw,left).add(twice(Group.ip(lw,p.coef(1))
                .add(Group.ip(bw,d)).add(Group.ip(aw,e)))));
        Scalar f4=Group.neg(Group.ip(rw,right).add(twice(Group.ip(rw,p.coef(2)).add(Group.ip(lw,e)))));
        Scalar f5=Group.neg(twice(Group.ip(rw,e)));
        // T^6 cancels exactly: the top coefficient of n and p is the same e.
        return new Scalar[]{fMinus1,fMinus2,f0,f1,f2,f4,f5,Group.zero()};
    }

    private static Scalar twice(Scalar x) { return x.add(x); }
}
