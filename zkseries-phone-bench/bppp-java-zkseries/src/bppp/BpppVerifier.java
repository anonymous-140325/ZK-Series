package bppp;

import crypto.Generators;
import crypto.Group;
import crypto.NormLinearArgument;
import crypto.Transcript;
import ec.ECPoint;
import ec.Scalar;

import java.util.ArrayList;
import java.util.List;

/**
 * The verifier of the Bulletproofs++ arithmetic circuit protocol (Section 5.3) inside
 * the Reciprocal Form Protocol (Section 6.3). Line references follow BpppProver.
 * Verification never throws: any malformed input is a rejection.
 */
public final class BpppVerifier {

    private BpppVerifier() {}

    /** V for the statement recorded in a verifier's CircuitBuilder. */
    public static boolean verifyCircuit(Generators g, CircuitBuilder cb, List<ECPoint> V, BpppProver.Proof pf) {
        try {
            if (V.size() != cb.inputs() || g.Gvec.length != cb.nm()) return false;
            Transcript tr = BpppProver.transcript(cb, V);
            BpppProver.sendLO(tr, pf.CL, pf.CO);                                       // [6.3 2] P -> V: C_L, C_O
            Scalar alpha = BpppProver.alpha(tr);                                       // [6.3 3] V -> P: alpha
            BpppProver.sendR(tr, pf.CR);                                               // [6.3 5] P -> V: C_R
            BpppCircuit c = cb.compile(alpha);                                         // [6.3 6] W'_l, W'_m, a'_m, a'_l

            Scalar[] ch = BpppProver.challenges(tr);                                   // [5.3 IAC 2] V -> P: rho, lambda, beta, delta
            Scalar rho = ch[0], lambda = ch[1], beta = ch[2], delta = ch[3];
            BpppProver.Step3 t = BpppProver.step3ForVerifier(c, rho, lambda, delta);  // [5.3 IAC 3] P, V compute
            BpppProver.sendS(tr, pf.CS);                                               // [5.3 IAC 5] P -> V: C_S
            Scalar tau = BpppProver.tau(tr);                                           // [5.3 IAC 6] V -> P: tau

            // C(tau) as a list of terms, so that it is evaluated inside the norm argument's
            // single multiplication (Eq. (26)) rather than as a separate point.
            List<Scalar> s = new ArrayList<>(); List<ECPoint> p = new ArrayList<>();
            Scalar[] pnTau = t.pn.eval(tau);
            Scalar psTau = Group.zero();
            for (int i = 0; i < c.nm; i++) psTau = psTau.add(pnTau[i].square().multiply(t.muVec[i]));
            psTau = psTau.add(t.psOffset.multiply(Group.pow(tau, 3)));                 // norm evaluated directly at tau
            s.add(tau.invert()); p.add(pf.CS);                                         // [5.3 IAC 8.4] C(T) := P(T) + T^-1 C_S
            s.add(delta); p.add(pf.CO);                                                // [5.3 IAC 8.4]        + delta C_O
            s.add(tau); p.add(pf.CL);                                                  // [5.3 IAC 8.4]        + T C_L
            s.add(tau.square()); p.add(pf.CR);                                         // [5.3 IAC 8.4]        + T^2 C_R
            Scalar twoTau3 = Group.of(2).multiply(Group.pow(tau, 3));
            for (int i = 0; i < c.k; i++) {                                            // [5.3 IAC 8.4]        + T^3 V^, with
                s.add(twoTau3.multiply(t.wIn[i])); p.add(V.get(i));                    // [5.3 IAC 3.7] V^ := 2 sum_i lambda^i V_i
            }
            Scalar[] cTau = BpppProver.cVector(beta, tau);                             // [5.3 IAC 8.2/8.3] c(tau)
            return NormLinearArgument.verify(tr, g.G, g.Gvec, g.H, cTau, rho, s, p,
                    psTau, pnTau, pf.nla);                                             // [5.3 IAC 9], exact coefficient merge
        } catch (RuntimeException e) {
            return false;
        }
    }
}
