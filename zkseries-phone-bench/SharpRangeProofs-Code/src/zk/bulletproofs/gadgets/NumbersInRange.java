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
 * Batched range proof: proves that every value in a list lies in
 * [min, max], as a SINGLE proof.
 *
 * This is the aggregated counterpart of {@link NumberInRange}. Every value's
 * constraints are allocated into one constraint system and discharged by one
 * call to the prover, so the result is one transcript, one inner product
 * argument and one proof carrying 3n commitments -- not n proofs. Because the
 * inner product argument is logarithmic in circuit size, the saving in proof
 * size is much larger than the saving in proving time, which stays close to
 * linear in n since the bit decompositions dominate.
 *
 * This matters for \name{}: an authentication proof ranges over m x T committed
 * values, and proving them one at a time is the pessimal way to do it. The
 * paper's Table 7 reports the unbatched figures, and a commented-out line in
 * sec/efficiency.tex asserts that aggregation "is not part of the weavechain
 * implementation" -- this class is that implementation, so the assertion does
 * not hold.
 *
 * Vendored from Weavechain's bulletproofs-gadgets
 * (https://github.com/weavechain/bulletproofs-gadgets), MIT licensed -- see
 * LICENSE-weavechain.txt. Adapted only in the mechanical respects described in
 * {@link NumberInRange}. The constraint system logic is unchanged.
 */
public class NumbersInRange implements Gadget<NumbersInRangeParams> {

	private final GadgetType type = Gadgets.numbers_in_range;

	private final boolean batchProof = true;

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
				+ "construct NumbersInRangeParams directly");
	}

	@Override
	public BpProof generate(Object value, NumbersInRangeParams params, Scalar rnd,
			PedersenCommitment pedersenCommitment, BulletProofGenerators generators) {
		List<BigInteger> values = toBigIntegerList(value);
		Integer bitsize = params.getBitsize();

		List<ECPoint> commitments = new ArrayList<ECPoint>();
		Transcript transcript = new Transcript();
		BpProver prover = new BpProver(transcript, pedersenCommitment);

		for (BigInteger v : values) {
			BigInteger a = v.subtract(params.getMin());
			BigInteger b = params.getMax().subtract(v);

			Commitment vComm = prover.commit(Utils.scalar(v),
					rnd != null ? rnd : Utils.randomScalar());
			Allocated av = new Allocated(vComm.getVariable(), v);
			commitments.add(vComm.getCommitment());

			Commitment aComm = prover.commit(Utils.scalar(a), Utils.randomScalar());
			Allocated aa = new Allocated(aComm.getVariable(), a);
			commitments.add(aComm.getCommitment());

			Commitment bComm = prover.commit(Utils.scalar(b), Utils.randomScalar());
			Allocated ab = new Allocated(bComm.getVariable(), b);
			commitments.add(bComm.getCommitment());

			if (!checkBound(prover, av, aa, ab, params.getMin(), params.getMax(), bitsize)) {
				System.err.println("NumbersInRange: failed statement check");
				return null;
			}
		}

		return new BpProof(prover.prove(generators), commitments);
	}

	@Override
	public boolean verify(NumbersInRangeParams params, BpProof proof,
			PedersenCommitment pedersenCommitment, BulletProofGenerators generators) {
		Transcript transcript = new Transcript();
		BpVerifier verifier = new BpVerifier(transcript);

		for (int i = 0; i < params.getCount(); i++) {
			Variable v = verifier.commit(proof.getCommitment(i * 3));
			Allocated av = new Allocated(v, null);

			Variable a = verifier.commit(proof.getCommitment(i * 3 + 1));
			Allocated aa = new Allocated(a, null);

			Variable b = verifier.commit(proof.getCommitment(i * 3 + 2));
			Allocated ab = new Allocated(b, null);

			if (!checkBound(verifier, av, aa, ab, params.getMin(), params.getMax(),
					params.getBitsize())) {
				return false;
			}
		}

		return verifier.verify(proof, pedersenCommitment, generators);
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
	@SuppressWarnings("unchecked")
	private static List<BigInteger> toBigIntegerList(Object value) {
		List<BigInteger> out = new ArrayList<BigInteger>();
		if (value instanceof BigInteger[]) {
			for (BigInteger v : (BigInteger[]) value) out.add(v);
			return out;
		}
		if (value instanceof long[]) {
			for (long v : (long[]) value) out.add(BigInteger.valueOf(v));
			return out;
		}
		if (value instanceof Iterable) {
			for (Object o : (Iterable<Object>) value) {
				if (o instanceof BigInteger) out.add((BigInteger) o);
				else if (o instanceof Number) out.add(BigInteger.valueOf(((Number) o).longValue()));
				else if (o instanceof String) out.add(new BigInteger((String) o));
				else throw new IllegalArgumentException("cannot convert element: " + o);
			}
			return out;
		}
		throw new IllegalArgumentException("cannot convert to a list of BigInteger: " + value);
	}
}
