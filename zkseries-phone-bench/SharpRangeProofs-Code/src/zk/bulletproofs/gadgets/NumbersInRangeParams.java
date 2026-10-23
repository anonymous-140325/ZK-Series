package zk.bulletproofs.gadgets;

import zk.bulletproofs.GadgetParams;

import java.math.BigInteger;

/**
 * Parameters for {@link NumbersInRange}: how many values the batch carries, the
 * closed interval they must all lie in, and the bit width of the positivity
 * proofs.
 *
 * The count is part of the parameters because the verifier needs it to walk the
 * proof's commitment list, which holds three entries (v, v-min, max-v) per
 * value in batch order.
 *
 * Vendored from Weavechain's bulletproofs-gadgets (MIT) -- see
 * LICENSE-weavechain.txt. Lombok annotations replaced with explicit members,
 * and the gson-backed GadgetParamsSerialized base class replaced with the plain
 * GadgetParams interface, as for the single-value gadget.
 */
public class NumbersInRangeParams implements GadgetParams {

	private final Integer count;

	private final BigInteger min;

	private final BigInteger max;

	private final Integer bitsize;

	public NumbersInRangeParams(Integer count, BigInteger min, BigInteger max, Integer bitsize) {
		this.count = count;
		this.min = min;
		this.max = max;
		this.bitsize = bitsize;
	}

	public NumbersInRangeParams(Integer count, long min, long max, Integer bitsize) {
		this(count, BigInteger.valueOf(min), BigInteger.valueOf(max), bitsize);
	}

	public Integer getCount() {
		return count;
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

	@Override
	public String serialize() {
		return "{\"count\":" + count + ",\"min\":" + min + ",\"max\":" + max
				+ ",\"bitsize\":" + bitsize + "}";
	}

	@Override
	public String serializeNoValue() {
		return serialize();
	}
}
