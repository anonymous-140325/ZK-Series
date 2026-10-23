package bpppref;

import cryptoref.Group;
import cryptoref.Transcript;
import ec.Scalar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An arithmetic circuit C = (W_l, W_m, a_l, a_m, f_l, f_m) of Section 5 (Eq. (29)), in
 * the one shape this project uses:
 *
 *   - k scalar inputs (N_v = 1), V_i = v_i G + s_i H_0, with f_l = 1 and f_m = 0, so
 *     input i enters linear row i;
 *   - the layout F of Section 5.2.1 places every w_O entry i at position i of n_O, so
 *     l_O, l_L and l_R are always zero.
 *
 * <b>Wire order.</b> w = w_L || w_R || w_O (Eq. (28)): columns [0, Nm) are w_L,
 * [Nm, 2Nm) are w_R, [2Nm, 2Nm + NO) are w_O. Rows are sparse.
 *
 * <b>The convention the protocol box proves.</b> Section 5.2 states the relation as
 * 0 = W_l w + f_l w_V + a_l (Eq. (30)) and w_L o w_R = W_m w + f_m w_V + a_m
 * (Eq. (31)). Expanding the value term (the T^3 coefficient of f^(T), Section 5.3
 * step 4) with the definitions exactly as printed in the protocol box gives
 *
 *   f^_3 = -2 lambda^T (W_l w - f_l w_V - a_l/2) - 2 mu^T (w_L o w_R + W_m w - f_m w_V - a_m/2),
 *
 * so the printed box proves
 *
 *   (P30)  W_l w - f_l w_V - a_l/2 = 0,
 *   (P31)  w_L o w_R = -W_m w + f_m w_V + a_m/2,
 *
 * not (30) and (31) as printed: the sign of W_m, the sign of the inputs and a factor
 * 1/2 on the constants differ. This is an invertible change of encoding; circuits
 * here are encoded in (P30)/(P31), and BpppProver follows the box verbatim.
 */
public final class BpppCircuit {

    public final int nm, no, nl, k;
    public final List<Map<Integer, Scalar>> wl = new ArrayList<>(), wm = new ArrayList<>();
    public final Scalar[] al, am;

    public BpppCircuit(int nm, int no, int nl, int k) {
        if (nm < 1 || Integer.bitCount(nm) != 1) throw new IllegalArgumentException("N_m must be a power of two (the norm argument halves n)");
        if (no > nm) throw new IllegalArgumentException("F places w_O in n_O, so N_O <= N_m");
        if (k > nl) throw new IllegalArgumentException("input i enters row i, so k <= N_l");
        this.nm = nm; this.no = no; this.nl = nl; this.k = k;
        for (int i = 0; i < nl; i++) wl.add(new LinkedHashMap<>());
        for (int i = 0; i < nm; i++) wm.add(new LinkedHashMap<>());
        al = Group.zeros(nl);
        am = Group.zeros(nm);
    }

    public static int colL(int i) { return i; }
    public int colR(int i) { return nm + i; }
    public int colO(int i) { return 2 * nm + i; }

    void addWl(int row, int col, Scalar v) { wl.get(row).merge(col, v, Scalar::add); }
    void addWm(int row, int col, Scalar v) { wm.get(row).merge(col, v, Scalar::add); }

    /** (P30) and (P31) for a full witness; v holds the k inputs. */
    public boolean satisfiedBy(Scalar[] wL, Scalar[] wR, Scalar[] wO, Scalar[] v) {
        Scalar[] w = new Scalar[2 * nm + no];
        System.arraycopy(wL, 0, w, 0, nm);
        System.arraycopy(wR, 0, w, nm, nm);
        System.arraycopy(wO, 0, w, 2 * nm, no);
        Scalar half = Group.of(2).invert();
        for (int r = 0; r < nl; r++) {                                   // (P30), f_l = 1
            Scalar lhs = rowDot(wl.get(r), w).subtract(half.multiply(al[r]));
            if (r < k) lhs = lhs.subtract(v[r]);
            if (!Group.isZero(lhs)) return false;
        }
        for (int r = 0; r < nm; r++) {                                   // (P31), f_m = 0
            Scalar rhs = Group.neg(rowDot(wm.get(r), w)).add(half.multiply(am[r]));
            if (!Group.eq(wL[r].multiply(wR[r]), rhs)) return false;
        }
        return true;
    }

    /** Binds the whole circuit into the transcript: shape and every entry. */
    public void absorb(Transcript tr) {
        tr.append("Nm", nm); tr.append("NO", no); tr.append("Nl", nl); tr.append("k", k);
        absorbRows(tr, "Wl", wl);
        absorbRows(tr, "Wm", wm);
        for (Scalar a : al) tr.append("al", a);
        for (Scalar a : am) tr.append("am", a);
    }

    private static void absorbRows(Transcript tr, String name, List<Map<Integer, Scalar>> rows) {
        for (Map<Integer, Scalar> row : rows) {
            tr.append(name + ".row", row.size());
            for (Map.Entry<Integer, Scalar> e : row.entrySet()) { tr.append(name + ".col", e.getKey()); tr.append(name + ".v", e.getValue()); }
        }
    }

    static Scalar rowDot(Map<Integer, Scalar> row, Scalar[] w) {
        Scalar acc = Group.zero();
        for (Map.Entry<Integer, Scalar> e : row.entrySet()) acc = acc.add(e.getValue().multiply(w[e.getKey()]));
        return acc;
    }

    static int nextPow2(int x) {
        int p = 1;
        while (p < x) p <<= 1;
        return p;
    }
}
