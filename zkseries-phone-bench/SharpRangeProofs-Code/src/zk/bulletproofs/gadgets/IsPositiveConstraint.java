package zk.bulletproofs.gadgets;

import ec.Scalar;
import zk.bulletproofs.Allocated;
import zk.bulletproofs.BulletProofs;
import zk.bulletproofs.ConstraintSystem;
import zk.bulletproofs.LRO;
import zk.bulletproofs.LinearCombination;
import zk.bulletproofs.Term;
import zk.bulletproofs.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Bit-decomposition constraint: the allocated value lies in [0, 2^bitsize).
 *
 * Vendored from Weavechain's bulletproofs-gadgets
 * (https://github.com/weavechain/bulletproofs-gadgets), MIT licensed --
 * see gadgets/LICENSE-weavechain.txt. Adapted only to this tree's package and
 * class names (Prover/Verifier/Proof are BpProver/BpVerifier/BpProof here) and
 * to drop the lombok, slf4j and gson dependencies, which this tree does not
 * carry. The constraint system logic is unchanged.
 */
public class IsPositiveConstraint {

	public static boolean verify(ConstraintSystem cs, Allocated variable, int bitsize) {
		List<Term> constraints = new ArrayList<Term>();

		constraints.add(new Term(variable.getVariable(), BulletProofs.getFactory().minus_one()));

		Scalar exp2 = BulletProofs.getFactory().one();
		for (int i = 0; i < bitsize; i++) {
			long bit = variable.getAssignment() != null && variable.getAssignment().testBit(i) ? 1L : 0L;
			LRO lro = cs.allocateMultiplier(Utils.scalar(1 - bit), Utils.scalar(bit));

			// Enforce a * b = 0, so one of (a,b) is zero
			cs.constrain(LinearCombination.from(lro.getOutput()));

			// Enforce that a = 1 - b, so they both are 1 or 0
			cs.constrain(LinearCombination.from(lro.getLeft())
					.add(LinearCombination.from(lro.getRight())
							.sub(BulletProofs.getFactory().one_lc())));

			constraints.add(new Term(lro.getRight(), exp2));
			exp2 = exp2.add(exp2);
		}

		// Enforce that -v + Sum(b_i * 2^i, i = 0..n-1) = 0
		LinearCombination lc = null;
		for (Term t : constraints) {
			lc = (lc == null) ? LinearCombination.from(t) : lc.add(LinearCombination.from(t));
		}
		cs.constrain(lc);

		return true;
	}
}
