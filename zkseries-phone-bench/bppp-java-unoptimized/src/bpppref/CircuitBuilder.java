package bpppref;

import cryptoref.Group;
import cryptoref.Transcript;
import ec.Scalar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A reciprocal-form circuit (Section 6.3) built from three kinds of constraint:
 *
 *   range(e, n)     e in [0, 256^n), as n base-256 digits (Sections 6.4, 6.4.2)
 *   mul(a, b)       a new wire o with a * b = o;  mulEq(a, b, e): a * b = e
 *   zero(e)         e = 0
 *
 * over affine expressions (Lin) in the committed inputs and in wires the circuit
 * creates. Every range shares one table {0..255} and one set of multiplicities
 * (Eq. (75)), so a statement pays for its digits, not for its ranges.
 *
 * <b>Layout.</b> Gates 0..Np-1 hold the digits (w_L) and their reciprocals (w_R), with
 * the Hadamard rows of Eq. (67); gates Np.. are the multiplications, their operands in
 * w_L and w_R. w_O holds the 255 multiplicities, then the free wires: a copy of each
 * input, and each wire the statement creates. F places all of w_O in n_O. Linear rows,
 * in the (P30) convention of BpppCircuit: input i's copy in row i, then the
 * constraints in the order they were added, then the set-membership row of Eq. (75).
 *
 * <b>Two modes.</b> The same statement code runs for prover and verifier: built with
 * values, every wire carries its witness value and range() computes digits; built
 * without, only the structure is recorded.
 */
public final class CircuitBuilder {

    /** The digit base b. A 16-bit range costs two digits; the table costs 255 entries of w_O. */
    public static final int BASE = 256, LOG_BASE = 8;

    /** Wire kinds: a free wire of w_O, the left or right operand of multiplication gate g, digit p. */
    static final int FREE = 0, GL = 1, GR = 2, DIGIT = 3;

    static int key(int kind, int idx) { return idx * 4 + kind; }

    /** An affine expression: sum of coefficient * wire, plus a constant. */
    public static final class Lin {
        final Map<Integer, Scalar> terms = new LinkedHashMap<>();
        Scalar constant = Group.zero();

        public static Lin constant(long c) { Lin l = new Lin(); l.constant = Group.of(c); return l; }

        static Lin wire(int kind, int idx) { Lin l = new Lin(); l.terms.put(key(kind, idx), Group.one()); return l; }

        public Lin add(Lin o) { return combine(o, Group.one()); }

        public Lin sub(Lin o) { return combine(o, Group.neg(Group.one())); }

        public Lin add(long c) { Lin r = copy(); r.constant = r.constant.add(Group.of(c)); return r; }

        /** c * this. */
        public Lin times(long c) { return new Lin().combine(this, Group.of(c)); }

        Lin copy() { Lin r = new Lin(); r.terms.putAll(terms); r.constant = constant; return r; }

        private Lin combine(Lin o, Scalar k) {
            Lin r = copy();
            for (Map.Entry<Integer, Scalar> e : o.terms.entrySet()) r.terms.merge(e.getKey(), e.getValue().multiply(k), Scalar::add);
            r.constant = r.constant.add(o.constant.multiply(k));
            return r;
        }
    }

    private final boolean prover;
    private final int k;
    private final Scalar[] inputs;
    private final List<Scalar> freeVal = new ArrayList<>(), glVal = new ArrayList<>(), grVal = new ArrayList<>(), digitVal = new ArrayList<>();
    private final List<Lin> linear = new ArrayList<>();                     // Lin = 0, in order
    private final List<Lin> gateE = new ArrayList<>();                      // gate g: w_L,g * w_R,g = gateE[g]
    /** For the checks: a range whose value does not fit gets its low digits instead of a refusal. */
    boolean lenient = false;

    /** A builder for k scalar inputs; values = the inputs' values for a prover, null for a verifier. */
    public CircuitBuilder(int k, Scalar[] values) {
        if (values != null && values.length != k) throw new IllegalArgumentException("one value per input");
        this.k = k;
        this.prover = values != null;
        this.inputs = values;
        for (int i = 0; i < k; i++) free(prover ? values[i] : null);   // input copies are free wires 0..k-1
    }

    public boolean isProver() { return prover; }

    /** Input i, the value committed in V_i. */
    public Lin input(int i) {
        if (i < 0 || i >= k) throw new IndexOutOfBoundsException("input " + i);
        return Lin.wire(FREE, i);
    }

    /** A new free wire carrying value (ignored, and may be null, for a verifier). */
    public Lin free(Scalar value) {
        if (prover && value == null) throw new IllegalArgumentException("a prover's wire needs a value");
        freeVal.add(prover ? value : null);
        return Lin.wire(FREE, freeVal.size() - 1);
    }

    /** o := a * b, as a new free wire. */
    public Lin mul(Lin a, Lin b) {
        Lin o = free(prover ? eval(a).multiply(eval(b)) : null);
        mulEq(a, b, o);
        return o;
    }

    /** a * b = e. */
    public void mulEq(Lin a, Lin b, Lin e) {
        int g = gateE.size();
        gateE.add(e);
        glVal.add(prover ? eval(a) : null);
        grVal.add(prover ? eval(b) : null);
        zero(Lin.wire(GL, g).sub(a));
        zero(Lin.wire(GR, g).sub(b));
    }

    /** e = 0. */
    public void zero(Lin e) { linear.add(e); }

    /**
     * e in [0, 256^n): n digits d_i with sum 256^i d_i = e, each in {0..255} by the
     * reciprocal argument. A prover whose e is not such an integer is refused here.
     */
    public void range(Lin e, int n) {
        if (n < 1 || n * LOG_BASE > 64) throw new IllegalArgumentException("1 to 64 bits of digits");
        long x = 0;
        if (prover) {
            Long u = Group.toUnsignedLong(eval(e));
            boolean fits = u != null && (n * LOG_BASE == 64 || Long.compareUnsigned(u, 1L << (LOG_BASE * n)) < 0);
            if (!fits && !lenient) throw new IllegalArgumentException("value out of range for " + n + " digits");
            x = Group.lowBits(eval(e));
        }
        Lin sum = new Lin();
        Scalar pw = Group.one();
        for (int i = 0; i < n; i++) {
            digitVal.add(prover ? Group.of((x >>> (LOG_BASE * i)) & (BASE - 1)) : null);
            sum.terms.put(key(DIGIT, digitVal.size() - 1), pw);
            pw = pw.multiply(Group.of(BASE));
        }
        zero(sum.sub(e));
    }

    /** The prover's value of an expression. */
    public Scalar eval(Lin e) {
        if (!prover) throw new IllegalStateException("no values in a verifier's builder");
        Scalar acc = e.constant;
        for (Map.Entry<Integer, Scalar> t : e.terms.entrySet()) acc = acc.add(t.getValue().multiply(value(t.getKey())));
        return acc;
    }

    private Scalar value(int key) {
        int kind = key & 3, idx = key >> 2;
        switch (kind) {
            case FREE: return freeVal.get(idx);
            case GL: return glVal.get(idx);
            case GR: return grVal.get(idx);
            default: return digitVal.get(idx);
        }
    }

    // ---- the compiled circuit ------------------------------------------------

    public int inputs() { return k; }
    public int digits() { return digitVal.size(); }
    public int gates() { return gateE.size(); }
    int no() { return (BASE - 1) + freeVal.size(); }
    public int nm() { return BpppCircuit.nextPow2(Math.max(Math.max(1, digits() + gates()), no())); }
    int nl() { return k + linear.size() + 1; }

    /** Digits for a range of this many bits. */
    public static int digitsForBits(int bits) {
        if (bits % LOG_BASE != 0) throw new IllegalArgumentException(bits + " bits are not whole base-256 digits");
        return bits / LOG_BASE;
    }

    private int col(BpppCircuit c, int key) {
        int kind = key & 3, idx = key >> 2;
        switch (kind) {
            case FREE: return c.colO(BASE - 1 + idx);
            case GL: return BpppCircuit.colL(digits() + idx);
            case GR: return c.colR(digits() + idx);
            default: return BpppCircuit.colL(idx);
        }
    }

    /**
     * The compiled circuit C' of Section 6.3, step 6, at the reciprocal challenge alpha;
     * alpha = null gives the template the transcript binds (the alpha-dependent entries
     * left out, which alpha and Np determine).
     */
    public BpppCircuit compile(Scalar alpha) {
        BpppCircuit c = new BpppCircuit(nm(), no(), nl(), k);
        Scalar two = Group.of(2);
        int np = digits();
        for (int i = 0; i < k; i++) c.addWl(i, col(c, key(FREE, i)), Group.one());   // row i: copy_i - v_i = 0
        int row = k;
        for (Lin e : linear) {                                                    // e = 0: W_l = coefficients, a_l = -2 const
            for (Map.Entry<Integer, Scalar> t : e.terms.entrySet()) c.addWl(row, col(c, t.getKey()), t.getValue());
            c.al[row] = Group.neg(two.multiply(e.constant));
            row++;
        }
        int member = row;                                                         // Eq. (75) at X = alpha
        for (int p = 0; p < np; p++) {
            if (alpha != null) c.addWm(p, c.colR(p), alpha);                      // Eq. (67): d r = 1 - alpha r
            c.am[p] = two;
        }
        for (int g = 0; g < gates(); g++) {                                       // (P31): w_L w_R = -W_m w + a_m/2 = e
            Lin e = gateE.get(g);
            for (Map.Entry<Integer, Scalar> t : e.terms.entrySet()) c.addWm(np + g, col(c, t.getKey()), Group.neg(t.getValue()));
            c.am[np + g] = two.multiply(e.constant);
        }
        if (alpha != null) {
            Scalar alphaInv = alpha.invert();
            for (int p = 0; p < np; p++) c.addWl(member, c.colR(p), Group.one());
            for (int t = 1; t < BASE; t++)
                c.addWl(member, c.colO(t - 1), Group.neg(Group.of(t).add(alpha).invert().subtract(alphaInv)));
            c.al[member] = two.multiply(Group.of(np)).multiply(alphaInv);
        }
        return c;
    }

    /** Binds the statement: digit and gate counts and the template, in which every constraint appears. */
    public void absorb(Transcript tr) {
        tr.append("builder.digits", digits());
        tr.append("builder.gates", gates());
        compile(null).absorb(tr);
    }

    /** The prover's witness: w_L (digits, then gate operands), w_O (multiplicities, then free wires), v. */
    final class Witness {
        final Scalar[] wL, wO, v;

        Witness() {
            if (!prover) throw new IllegalStateException("no witness in a verifier's builder");
            wL = Group.zeros(nm());
            int[] counts = new int[BASE];
            for (int p = 0; p < digits(); p++) {
                wL[p] = digitVal.get(p);
                counts[(int) Group.lowBits(digitVal.get(p))]++;
            }
            for (int g = 0; g < gates(); g++) wL[digits() + g] = glVal.get(g);
            wO = new Scalar[no()];
            for (int t = 1; t < BASE; t++) wO[t - 1] = Group.of(counts[t]);
            for (int j = 0; j < freeVal.size(); j++) wO[BASE - 1 + j] = freeVal.get(j);
            v = inputs.clone();
        }

        /** w_R: the reciprocals 1/(alpha + d_p) [6.3 4], then the gates' right operands. */
        Scalar[] wR(Scalar alpha) {
            Scalar[] r = Group.zeros(nm());
            for (int p = 0; p < digits(); p++) {
                Scalar den = digitVal.get(p).add(alpha);
                if (Group.isZero(den)) throw new IllegalStateException("alpha + w_D,i = 0");   // [6.3 4] fail
                r[p] = den.invert();
            }
            for (int g = 0; g < gates(); g++) r[digits() + g] = grVal.get(g);
            return r;
        }
    }

    Witness witness() { return new Witness(); }
}
