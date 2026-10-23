package zk.bulletproofs.gadgets;

import ec.ECPoint;
import ec.Scalar;
import zk.bulletproofs.Allocated;
import zk.bulletproofs.BpProof;
import zk.bulletproofs.BpProver;
import zk.bulletproofs.BpVerifier;
import zk.bulletproofs.BulletProofGenerators;
import zk.bulletproofs.Commitment;
import zk.bulletproofs.ConstraintSystem;
import zk.bulletproofs.Gadget;
import zk.bulletproofs.GadgetParams;
import zk.bulletproofs.GadgetType;
import zk.bulletproofs.LinearCombination;
import zk.bulletproofs.PedersenCommitment;
import zk.bulletproofs.Transcript;
import zk.bulletproofs.Utils;
import zk.bulletproofs.Variable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Range proof: proves a committed v satisfies min &lt;= v &lt;= max, by
 * committing a = v - min and b = max - v and proving both are non-negative
 * through their bit decompositions.
 *
 * Vendored from Weavechain's bulletproofs-gadgets
 * (https://github.com/weavechain/bulletproofs-gadgets), MIT licensed -- see
 * gadgets/LICENSE-weavechain.txt. This is the implementation the ZK-Series
 * paper's readme names as the Bulletproofs baseline; the published artifact
 * shipped the Gadget interface without any implementation, so no Bulletproof
 * range proof could be constructed and the Bulletproofs rows of Table 7 had no
 * code behind them.
 *
 * Adapted only in the mechanical respects: package and class names
 * (Prover/Verifier/Proof are BpProver/BpVerifier/BpProof in this tree), lombok
 * getters written out, the slf4j logger replaced with a stderr line, and
 * unpackParams left unsupported because this tree carries no gson and the
 * benchmark builds its parameters programmatically. The constraint system logic
 * is unchanged.
 */
public class NumberInRange implements Gadget<NumberInRangeParams> {

	private final GadgetType type = Gadgets.number_in_range;

	private final boolean batchProof = false;

	private final boolean numericInput = true;

	private final boolean multiColumn = false;

	@Override
	public GadgetType getType() {
		return type;
	}

	@Override
	public boolean isBatchProof() {
		return batchProof;
	}

	@Override
	public boolean isNumericInput() {
		return numericInput;
	}

	@Override
	public boolean isMultiColumn() {
		return multiColumn;
	}

	@Override
	public GadgetParams unpackParams(String params, Object value) {
		throw new UnsupportedOperationException(
				"JSON parameter unpacking needs gson, which this tree does not carry; "
				+ "construct NumberInRangeParams directly");
	}

	@Override
	public BpProof generate(Object value, NumberInRangeParams params, Scalar rnd,
			PedersenCommitment pedersenCommitment, BulletProofGenerators generators) {
		BigInteger v = toBigInteger(value);
		Integer bitsize = params.getBitsize();

		BigInteger a = v.subtract(params.getMin());
		BigInteger b = params.getMax().subtract(v);

		List<ECPoint> commitments = new ArrayList<ECPoint>();

		Transcript transcript = new Transcript();
		BpProver prover = new BpProver(transcript, pedersenCommitment);

		Commitment vComm = prover.commit(Utils.scalar(v), rnd != null ? rnd : Utils.randomScalar());
		Allocated av = new Allocated(vComm.getVariable(), v);
		commitments.add(vComm.getCommitment());

		Commitment aComm = prover.commit(Utils.scalar(a), Utils.randomScalar());
		Allocated aa = new Allocated(aComm.getVariable(), a);
		commitments.add(aComm.getCommitment());

		Commitment bComm = prover.commit(Utils.scalar(b), Utils.randomScalar());
		Allocated ab = new Allocated(bComm.getVariable(), b);
		commitments.add(bComm.getCommitment());

		if (checkBound(prover, av, aa, ab, params.getMin(), params.getMax(), bitsize)) {
			return new BpProof(prover.prove(generators), commitments);
		} else {
			System.err.println("NumberInRange: failed statement check");
			return null;
		}
	}

	@Override
	public boolean verify(NumberInRangeParams params, BpProof proof,
			PedersenCommitment pedersenCommitment, BulletProofGenerators generators) {
		Transcript transcript = new Transcript();
		BpVerifier verifier = new BpVerifier(transcript);

		Variable v = verifier.commit(proof.getCommitment(0));
		Allocated av = new Allocated(v, null);

		Variable a = verifier.commit(proof.getCommitment(1));
		Allocated aa = new Allocated(a, null);

		Variable b = verifier.commit(proof.getCommitment(2));
		Allocated ab = new Allocated(b, null);

		if (checkBound(verifier, av, aa, ab, params.getMin(), params.getMax(), params.getBitsize())) {
			return verifier.verify(proof, pedersenCommitment, generators);
		} else {
			return false;
		}
	}

	public boolean checkBound(ConstraintSystem cs, Allocated v, Allocated a, Allocated b,
			BigInteger min, BigInteger max, Integer bitsize) {
		cs.constrain(LinearCombination.from(v.getVariable())
				.sub(LinearCombination.from(Utils.scalar(min)))
				.sub(LinearCombination.from(a.getVariable())));
		cs.constrain(LinearCombination.from(Utils.scalar(max))
				.sub(LinearCombination.from(v.getVariable()))
				.sub(LinearCombination.from(b.getVariable())));

		cs.constrainLCWithScalar(
				LinearCombination.from(a.getVariable()).add(LinearCombination.from(b.getVariable())),
				Utils.scalar(max.subtract(min)));

		return IsPositiveConstraint.verify(cs, a, bitsize)
				&& IsPositiveConstraint.verify(cs, b, bitsize);
	}

	/** Replaces Weavechain's ConvertUtils, which pulls in gson. */
	private static BigInteger toBigInteger(Object value) {
		if (value instanceof BigInteger) return (BigInteger) value;
		if (value instanceof Number) return BigInteger.valueOf(((Number) value).longValue());
		if (value instanceof String) return new BigInteger((String) value);
		throw new IllegalArgumentException("cannot convert to BigInteger: " + value);
	}
}
