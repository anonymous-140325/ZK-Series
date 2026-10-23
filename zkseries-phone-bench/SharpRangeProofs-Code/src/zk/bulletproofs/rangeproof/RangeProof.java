package zk.bulletproofs.rangeproof;

import ec.ECPoint;
import ec.Scalar;
import zk.bulletproofs.BulletProofGenerators;
import zk.bulletproofs.BulletProofs;
import zk.bulletproofs.InnerProductProof;
import zk.bulletproofs.PedersenCommitment;
import zk.bulletproofs.Transcript;
import zk.bulletproofs.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The dedicated aggregated range proof of Bulletproofs (Bunz et al., S&P 2018,
 * Sections 4.1-4.3), after dalek-cryptography's RangeProof.
 *
 * The rest of this package is a port of dalek's R1CS module, which is the
 * paper's arithmetic circuit protocol (Section 5). Weavechain ported that module
 * and not this one, so every range proof in the tree went through the circuit
 * protocol: three committed wires per bit, two linear constraints per bit, and
 * five commitments to t(X). This class is the protocol dalek's range proof
 * benchmarks actually measure: a_L is the bits, a_R = a_L - 1 is implied rather
 * than constrained, the constraint weights are the closed-form powers of y, z and
 * 2, and t(X) has degree two.
 *
 * <b>Where it differs from dalek.</b> The transcript is this tree's Transcript,
 * not Merlin, so proofs are not interchangeable with dalek's. And Transcript's
 * challengeScalar hashes the transcript without absorbing its label or its
 * output, so two challenges drawn back to back come out equal. This protocol draws
 * y and z back to back, as the vendored R1CS prover draws y, z and u, x;
 * {@link #challenge} absorbs the label before and the challenge after, so every
 * challenge here is domain separated and chained. The R1CS prover is left as
 * vendored.
 *
 * As in dalek, the bit width n must be 8, 16, 32 or 64 and the number of values m
 * a power of two, and the verifier rejects identity points.
 */
public final class RangeProof {

    private final ECPoint A;
    private final ECPoint S;
    private final ECPoint T1;
    private final ECPoint T2;
    private final Scalar tx;
    private final Scalar txBlinding;
    private final Scalar eBlinding;
    private final InnerProductProof ipp;

    public RangeProof(ECPoint A, ECPoint S, ECPoint T1, ECPoint T2, Scalar tx, Scalar txBlinding,
                      Scalar eBlinding, InnerProductProof ipp) {
        this.A = A;
        this.S = S;
        this.T1 = T1;
        this.T2 = T2;
        this.tx = tx;
        this.txBlinding = txBlinding;
        this.eBlinding = eBlinding;
        this.ipp = ipp;
    }

    public ECPoint getA() { return A; }
    public ECPoint getS() { return S; }
    public ECPoint getT1() { return T1; }
    public ECPoint getT2() { return T2; }
    public Scalar getTx() { return tx; }
    public Scalar getTxBlinding() { return txBlinding; }
    public Scalar getEBlinding() { return eBlinding; }
    public InnerProductProof getIpp() { return ipp; }

    /** Wire size: A, S, T1, T2, the 2 log2(nm) points L and R, and five scalars. */
    public int wireSize() {
        return 32 * (4 + ipp.getL().size() + ipp.getR().size() + 5);
    }

    /** Pedersen commitments to values under blindings, as the prover and verifier take them. */
    public static List<ECPoint> commitments(PedersenCommitment pc, long[] values, Scalar[] blindings) {
        List<ECPoint> out = new ArrayList<>(values.length);
        for (int j = 0; j < values.length; j++) {
            out.add(pc.commit(Utils.scalar(values[j]), blindings[j]));
        }
        return out;
    }

    /**
     * Proves that commitments.get(j) opens to values[j] under blindings[j], with
     * values[j] in [0, 2^n) read as unsigned.
     *
     * The commitments are taken rather than recomputed so a caller can derive
     * them (a shifted commitment is the original plus a multiple of B) and so
     * their cost stays out of any timing. They are absorbed as given: a
     * commitment that does not open to its value yields a proof that does not
     * verify, which is how the check exercises soundness.
     *
     * The generators are copied, not consumed: InnerProductProof.create folds the
     * lists it is handed in place, and the same generators serve every proof.
     */
    public static RangeProof prove(Transcript transcript, PedersenCommitment pc, BulletProofGenerators gens,
                                   long[] values, Scalar[] blindings, List<ECPoint> commitments, int n) {
        int m = values.length;
        if (!validBitsize(n)) {
            throw new IllegalArgumentException("bit width must be 8, 16, 32 or 64, got " + n);
        }
        if (m < 1 || Integer.bitCount(m) != 1) {
            throw new IllegalArgumentException("number of values must be a power of two, got " + m);
        }
        if (blindings.length != m || commitments.size() != m) {
            throw new IllegalArgumentException("need one blinding and one commitment per value");
        }
        if (gens.getCapacity() < n || gens.getG().size() < m) {
            throw new IllegalArgumentException("generators too small for n=" + n + ", m=" + m);
        }
        for (long v : values) {
            if (n < 64 && (v >>> n) != 0) {
                throw new IllegalArgumentException("value " + Long.toUnsignedString(v)
                        + " is not below 2^" + n);
            }
        }

        final Scalar one = BulletProofs.getFactory().one();
        final Scalar zero = BulletProofs.getFactory().zero();
        int nm = n * m;

        domainSeparate(transcript, n, m);
        for (ECPoint V : commitments) {
            transcript.append("V", V.compress());
        }

        List<ECPoint> G = generators(gens.getG(), n, m);
        List<ECPoint> H = generators(gens.getH(), n, m);
        ECPoint blindingBase = pc.getBlinding().decompress();

        // A = alpha B_blinding + <a_L, G> + <a_R, H>. With a_L in {0,1} and
        // a_R = a_L - 1 in {-1,0}, each position adds G_i or subtracts H_i --
        // additions only, as in dalek's party.
        Scalar alpha = random();
        ECPoint Apoint = blindingBase.multiply(alpha);
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                Apoint = ((values[j] >>> i) & 1L) == 1L ? Apoint.add(G.get(k)) : Apoint.subtract(H.get(k));
            }
        }

        Scalar rho = random();
        List<Scalar> sL = new ArrayList<>(nm);
        List<Scalar> sR = new ArrayList<>(nm);
        for (int k = 0; k < nm; k++) {
            sL.add(random());
            sR.add(random());
        }
        ECPoint Spoint = Utils.multiscalarMul(rho, sL, sR, blindingBase, G, H);

        ECPoint Ac = Apoint.compress();
        ECPoint Sc = Spoint.compress();
        transcript.append("A", Ac);
        transcript.append("S", Sc);

        Scalar y = challenge(transcript, "y");
        Scalar z = challenge(transcript, "z");
        Scalar zz = z.multiply(z);

        // l(X) = (a_L - z 1) + s_L X
        // r(X) = y^nm o (a_R + z 1) + z^2 (z^0 2^n || ... || z^(m-1) 2^n) + (y^nm o s_R) X
        Scalar minusZ = zero.subtract(z);
        Scalar oneMinusZ = one.subtract(z);
        Scalar zMinusOne = z.subtract(one);

        List<Scalar> l0 = new ArrayList<>(nm);
        List<Scalar> r0 = new ArrayList<>(nm);
        List<Scalar> r1 = new ArrayList<>(nm);
        Scalar expY = one;
        Scalar zzExpZ = zz;
        for (int j = 0; j < m; j++) {
            Scalar exp2 = one;
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                boolean bit = ((values[j] >>> i) & 1L) == 1L;
                l0.add(bit ? oneMinusZ : minusZ);
                r0.add(expY.multiply(bit ? z : zMinusOne).add(zzExpZ.multiply(exp2)));
                r1.add(expY.multiply(sR.get(k)));
                expY = expY.multiply(y);
                exp2 = exp2.add(exp2);
            }
            zzExpZ = zzExpZ.multiply(z);
        }
        List<Scalar> l1 = sL;

        // t(X) = <l(X), r(X)> = t0 + t1 X + t2 X^2, with t1 from one inner product
        // of the sums rather than two cross terms, as dalek's VecPoly1.
        Scalar t0 = Utils.innerProduct(l0, r0);
        Scalar t2 = Utils.innerProduct(l1, r1);
        List<Scalar> lSum = new ArrayList<>(nm);
        List<Scalar> rSum = new ArrayList<>(nm);
        for (int k = 0; k < nm; k++) {
            lSum.add(l0.get(k).add(l1.get(k)));
            rSum.add(r0.get(k).add(r1.get(k)));
        }
        Scalar t1 = Utils.innerProduct(lSum, rSum).subtract(t0).subtract(t2);

        Scalar tau1 = random();
        Scalar tau2 = random();
        ECPoint T1c = pc.commit(t1, tau1);
        ECPoint T2c = pc.commit(t2, tau2);
        transcript.append("T_1", T1c);
        transcript.append("T_2", T2c);

        Scalar x = challenge(transcript, "x");
        Scalar xx = x.multiply(x);

        Scalar txv = t0.add(t1.multiply(x)).add(t2.multiply(xx));
        Scalar txBlind = tau1.multiply(x).add(tau2.multiply(xx));
        zzExpZ = zz;
        for (int j = 0; j < m; j++) {
            txBlind = txBlind.add(zzExpZ.multiply(blindings[j].reduce()));
            zzExpZ = zzExpZ.multiply(z);
        }
        Scalar eBlind = alpha.add(rho.multiply(x));

        List<Scalar> l = new ArrayList<>(nm);
        List<Scalar> r = new ArrayList<>(nm);
        for (int k = 0; k < nm; k++) {
            l.add(l0.get(k).add(l1.get(k).multiply(x)));
            r.add(r0.get(k).add(r1.get(k).multiply(x)));
        }

        transcript.append("t_x", txv);
        transcript.append("t_x_blinding", txBlind);
        transcript.append("e_blinding", eBlind);

        Scalar w = challenge(transcript, "w");
        ECPoint Q = pc.getB().decompress().multiply(w);

        List<Scalar> gFactors = new ArrayList<>(Collections.nCopies(nm, one));
        List<Scalar> hFactors = new ArrayList<>(nm);
        Scalar yInv = y.invert();
        Scalar expYInv = one;
        for (int k = 0; k < nm; k++) {
            hFactors.add(expYInv);
            expYInv = expYInv.multiply(yInv);
        }

        InnerProductProof ippProof = InnerProductProof.create(transcript, Q, gFactors, hFactors,
                new ArrayList<>(G), new ArrayList<>(H), l, r);
        if (ippProof == null) {
            throw new IllegalStateException("inner product argument refused its input");
        }

        return new RangeProof(Ac, Sc, T1c, T2c, txv, txBlind, eBlind, ippProof);
    }

    /**
     * Verifies against the commitments, one per value, for bit width n.
     *
     * Both checks -- that t(x) is correct and that l(x), r(x) satisfy the inner
     * product relation -- are folded into a single multiexponentiation, the second
     * weighted by a random c, as dalek does. Never throws: any malformed input is
     * a rejection.
     */
    public boolean verify(Transcript transcript, PedersenCommitment pc, BulletProofGenerators gens,
                          List<ECPoint> commitments, int n) {
        try {
            final Scalar one = BulletProofs.getFactory().one();
            final Scalar zero = BulletProofs.getFactory().zero();
            int m = commitments.size();
            if (!validBitsize(n) || m < 1 || Integer.bitCount(m) != 1) {
                return false;
            }
            if (gens.getCapacity() < n || gens.getG().size() < m) {
                return false;
            }
            int nm = n * m;

            domainSeparate(transcript, n, m);
            for (ECPoint V : commitments) {
                if (!transcript.validateAndAppend("V", V.compress())) {
                    return false;
                }
            }
            if (!transcript.validateAndAppend("A", A) || !transcript.validateAndAppend("S", S)) {
                return false;
            }

            Scalar y = challenge(transcript, "y");
            Scalar z = challenge(transcript, "z");

            if (!transcript.validateAndAppend("T_1", T1) || !transcript.validateAndAppend("T_2", T2)) {
                return false;
            }

            Scalar x = challenge(transcript, "x");

            transcript.append("t_x", tx);
            transcript.append("t_x_blinding", txBlinding);
            transcript.append("e_blinding", eBlinding);

            Scalar w = challenge(transcript, "w");

            if (ipp.getL().size() != ipp.getR().size()) {
                return false;
            }
            InnerProductProof.IPPVer ippVer = ipp.scalarsVer(nm, transcript);
            if (ippVer == null) {
                return false;
            }

            Scalar c = random();
            Scalar a = ipp.getA();
            Scalar b = ipp.getB();
            Scalar zz = z.multiply(z);
            Scalar cx = c.multiply(x);
            List<Scalar> s = ippVer.getS();

            List<Scalar> scalars = new ArrayList<>(2 * nm + 2 * ipp.getL().size() + m + 5);
            List<ECPoint> points = new ArrayList<>(scalars.size());

            // A carries scalar 1 and goes in the first slot of the multiexponentiation.
            scalars.add(x);
            points.add(S.decompress());
            scalars.add(cx);
            points.add(T1.decompress());
            scalars.add(cx.multiply(x));
            points.add(T2.decompress());

            scalars.addAll(ippVer.getU_sq());
            for (ECPoint L : ipp.getL()) {
                points.add(L.decompress());
            }
            scalars.addAll(ippVer.getU_inv_sq());
            for (ECPoint R : ipp.getR()) {
                points.add(R.decompress());
            }

            scalars.add(zero.subtract(eBlinding).subtract(c.multiply(txBlinding)));
            points.add(pc.getBlinding().decompress());

            Scalar delta = delta(n, m, y, z);
            scalars.add(w.multiply(tx.subtract(a.multiply(b))).add(c.multiply(delta.subtract(tx))));
            points.add(pc.getB().decompress());

            // G_i: -z - a s_i.
            // H_i: z + y^-i (z^2 z^j 2^(i mod n) - b s_(nm-1-i)).
            Scalar minusZ = zero.subtract(z);
            Scalar yInv = y.invert();
            List<Scalar> hScalars = new ArrayList<>(nm);
            Scalar expYInv = one;
            Scalar zzExpZ = zz;
            for (int j = 0; j < m; j++) {
                Scalar exp2 = one;
                for (int i = 0; i < n; i++) {
                    int k = j * n + i;
                    scalars.add(minusZ.subtract(a.multiply(s.get(k))));
                    hScalars.add(z.add(expYInv.multiply(
                            zzExpZ.multiply(exp2).subtract(b.multiply(s.get(nm - 1 - k))))));
                    expYInv = expYInv.multiply(yInv);
                    exp2 = exp2.add(exp2);
                }
                zzExpZ = zzExpZ.multiply(z);
            }
            points.addAll(generators(gens.getG(), n, m));
            scalars.addAll(hScalars);
            points.addAll(generators(gens.getH(), n, m));

            // V_j: c z^2 z^j.
            Scalar czzExpZ = c.multiply(zz);
            for (int j = 0; j < m; j++) {
                scalars.add(czzExpZ);
                points.add(commitments.get(j).decompress());
                czzExpZ = czzExpZ.multiply(z);
            }

            ECPoint check = Utils.multiscalarMul(one, scalars, A.decompress(), points);
            return BulletProofs.getFactory().identity().equals(check);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * delta(y, z) = (z - z^2) <1^nm, y^nm> - z^3 <1^n, 2^n> sum_(j<m) z^j, the
     * part of t0 that does not depend on the witness.
     */
    static Scalar delta(int n, int m, Scalar y, Scalar z) {
        Scalar one = BulletProofs.getFactory().one();
        Scalar zz = z.multiply(z);
        Scalar sumY = sumOfPowers(y, n * m);
        Scalar sum2 = sumOfPowers(one.add(one), n);
        Scalar sumZ = sumOfPowers(z, m);
        return z.subtract(zz).multiply(sumY).subtract(zz.multiply(z).multiply(sum2).multiply(sumZ));
    }

    static Scalar sumOfPowers(Scalar base, int count) {
        Scalar sum = BulletProofs.getFactory().zero();
        Scalar power = BulletProofs.getFactory().one();
        for (int i = 0; i < count; i++) {
            sum = sum.add(power);
            power = power.multiply(base);
        }
        return sum;
    }

    /**
     * A challenge bound to its label and absorbed once drawn.
     *
     * Transcript.challengeScalar alone does neither, so without this the second of
     * two consecutive draws would repeat the first. Public so the check can show
     * the difference.
     */
    public static Scalar challenge(Transcript transcript, String label) {
        transcript.append("challenge", label);
        Scalar c = transcript.challengeScalar(label);
        transcript.append(label, c);
        return c;
    }

    static void domainSeparate(Transcript transcript, int n, int m) {
        transcript.append("dom-sep", "rangeproof v1");
        transcript.append("n", (long) n);
        transcript.append("m", (long) m);
    }

    static boolean validBitsize(int n) {
        return n == 8 || n == 16 || n == 32 || n == 64;
    }

    /**
     * A uniformly random scalar, reduced.
     *
     * Utils.randomScalar builds a scalar from 255 random bits without reducing it
     * mod the group order, so about seven in eight come out unreduced. Point
     * multiplication does not mind, but the curve library's subtract rejects an
     * unreduced operand outright, and a sum of unreduced blindings sent in the
     * proof made honest proofs fail to verify at random. Every random scalar and
     * every caller's blinding is therefore reduced before it enters arithmetic.
     */
    static Scalar random() {
        return Utils.randomScalar().reduce();
    }

    /** Party j's first n generators, for j = 0..m-1, concatenated -- dalek's G(n, m). */
    static List<ECPoint> generators(List<List<ECPoint>> perParty, int n, int m) {
        List<ECPoint> out = new ArrayList<>(n * m);
        for (int j = 0; j < m; j++) {
            out.addAll(perParty.get(j).subList(0, n));
        }
        return out;
    }
}
