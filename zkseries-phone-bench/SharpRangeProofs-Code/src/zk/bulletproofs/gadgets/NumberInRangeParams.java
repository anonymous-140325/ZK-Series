package zk.bulletproofs.gadgets;

import zk.bulletproofs.GadgetParams;

import java.math.BigInteger;

/**
 * Parameters for {@link NumberInRange}: the closed interval and the bit width
 * used for the two positivity proofs.
 *
 * Vendored from Weavechain's bulletproofs-gadgets (MIT) -- see
 * gadgets/LICENSE-weavechain.txt. Lombok annotations replaced with explicit
 * members, and GadgetParamsSerialized (a gson-backed base class this tree does
 * not carry) replaced with the plain GadgetParams interface.
 */
public class NumberInRangeParams implements GadgetParams {

	private final BigInteger min;

	private final BigInteger max;

	private final Integer bitsize;

	public NumberInRangeParams(BigInteger min, BigInteger max, Integer bitsize) {
		this.min = min;
		this.max = max;
		this.bitsize = bitsize;
	}

	public NumberInRangeParams(long min, long max, Integer bitsize) {
		this(BigInteger.valueOf(min), BigInteger.valueOf(max), bitsize);
	}

	public BigInteger getMin() {
		return min;
	}

	public BigInteger getMax() {
		return max;
	}

	public Integer getBitsize() {
		return bitsize;
	}

	/*
	 * Weavechain's GadgetParamsSerialized implements these two through gson.
	 * This tree carries no gson, and nothing in the benchmark path serialises
	 * parameters -- they are built programmatically -- so these emit a plain
	 * readable form rather than pulling in a JSON dependency for it.
	 */

	@Override
	public String serialize() {
		return "{\"min\":" + min + ",\"max\":" + max + ",\"bitsize\":" + bitsize + "}";
	}

	@Override
	public String serializeNoValue() {
		return serialize();
	}
}
