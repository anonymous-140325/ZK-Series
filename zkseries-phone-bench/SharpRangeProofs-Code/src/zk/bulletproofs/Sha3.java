package zk.bulletproofs;

import com.github.aelstad.keccakj.fips202.SHA3_512;

import java.security.MessageDigest;

/**
 * Single source of SHA3-512 instances for the protocol.
 *
 * The tree used to call MessageDigest.getInstance("SHA3-512"), which works on a
 * desktop JVM but not on Android: ART's default security providers do not
 * reliably offer SHA3-512, and the resulting NoSuchAlgorithmException was being
 * swallowed in Transcript.challengeScalar, so the failure surfaced as a
 * NullPointerException deep inside the prover rather than as a missing
 * algorithm.
 *
 * keccakj is already a dependency (lib/keccakj-1.1.0.jar) and its SHA3_512 is a
 * MessageDigest subclass, so this is a drop-in that behaves identically on both
 * runtimes. Digest equivalence with the JCA implementation is asserted by
 * experiments.Sha3EquivalenceCheck.
 */
public final class Sha3 {

	private Sha3() {
	}

	public static MessageDigest sha3_512() {
		return new SHA3_512();
	}
}
