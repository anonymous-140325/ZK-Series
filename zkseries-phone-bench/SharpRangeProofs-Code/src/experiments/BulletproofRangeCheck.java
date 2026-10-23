package experiments;

import zk.bulletproofs.BpProof;
import zk.bulletproofs.BulletProofGenerators;
import zk.bulletproofs.BulletProofs;
import zk.bulletproofs.PedersenCommitment;
import zk.bulletproofs.Utils;
import zk.bulletproofs.gadgets.NumberInRange;
import zk.bulletproofs.gadgets.NumberInRangeParams;

import java.math.BigInteger;

/**
 * Soundness and completeness smoke test for the vendored Weavechain range
 * gadget: in-range values must produce proofs that verify, and out-of-range
 * values must not produce a verifying proof.
 *
 *   java -cp "build;lib/*" experiments.BulletproofRangeCheck
 */
public class BulletproofRangeCheck {

	static final int BITSIZE = 16;
	static final long MIN = 0;
	static final long MAX = 8000;

	static BulletProofGenerators gens() throws Exception {
		return new BulletProofGenerators(BulletProofs.DEFAULT_GENERATORS_SIZE, 1);
	}

	public static void main(String[] args) throws Exception {
		NumberInRange gadget = new NumberInRange();
		NumberInRangeParams params = new NumberInRangeParams(MIN, MAX, BITSIZE);
		PedersenCommitment pc = PedersenCommitment.getDefault();

		int ok = 0, failed = 0;

		long[] inRange = { 0, 1, 42, 4000, 7999, 8000 };
		for (long v : inRange) {
			// A BulletProofGenerators instance carries state that proving
			// consumes, so prove and verify each need their own -- reusing one
			// makes every proof fail to verify, which looks like a broken gadget.
			BpProof proof = gadget.generate(BigInteger.valueOf(v), params,
					Utils.randomScalar(), pc, gens());
			boolean verified = proof != null && gadget.verify(params, proof, pc, gens());
			if (verified) {
				ok++;
			} else {
				failed++;
				System.out.println("  FAIL: in-range value " + v + " did not verify");
			}
		}

		// Out of range: the gadget should refuse to build a proof, or build one
		// that does not verify. Either is correct; silently verifying is not.
		long[] outOfRange = { -1, 8001, 100000 };
		for (long v : outOfRange) {
			boolean verified;
			try {
				BpProof proof = gadget.generate(BigInteger.valueOf(v), params,
						Utils.randomScalar(), pc, gens());
				verified = proof != null && gadget.verify(params, proof, pc, gens());
			} catch (Exception e) {
				verified = false;
			}
			if (!verified) {
				ok++;
			} else {
				failed++;
				System.out.println("  FAIL: out-of-range value " + v + " VERIFIED -- unsound");
			}
		}

		System.out.println("Bulletproof range gadget: passed " + ok + ", failed " + failed
				+ "  (bitsize=" + BITSIZE + ", range [" + MIN + "," + MAX + "])");
		if (failed > 0) System.exit(1);
	}
}
