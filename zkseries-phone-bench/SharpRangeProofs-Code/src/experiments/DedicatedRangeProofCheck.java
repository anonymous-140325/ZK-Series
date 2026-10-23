package experiments;

import ec.ECPoint;
import ec.Scalar;
import zk.bulletproofs.BulletProofGenerators;
import zk.bulletproofs.BulletProofs;
import zk.bulletproofs.InnerProductProof;
import zk.bulletproofs.PedersenCommitment;
import zk.bulletproofs.Transcript;
import zk.bulletproofs.Utils;
import zk.bulletproofs.rangeproof.RangeProof;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Gates for the dedicated Bulletproofs range proof, zk.bulletproofs.rangeproof.
 *
 * There is no oracle to diff against -- the transcript is this tree's, not
 * dalek's Merlin, so dalek's proofs and ours do not interchange -- which puts the
 * weight on rejection. Accepting honest proofs shows the algebra closes; it says
 * nothing about whether the verifier checks anything. So most of what follows is
 * proofs that must fail: commitments to values past the range, commitments in the
 * wrong order, the wrong bit width, every proof field tampered with in turn, a
 * transcript that diverges, identity points, and shapes the protocol does not
 * support.
 *
 *   java -cp "build;lib/*" experiments.DedicatedRangeProofCheck
 */
public class DedicatedRangeProofCheck {

	static int pass = 0, fail = 0;
	static final Random rnd = new Random(20260915L);
	static PedersenCommitment pc;

	static void check(String what, boolean ok) {
		if (ok) {
			pass++;
		} else {
			fail++;
			System.out.println("  FAILED: " + what);
		}
	}

	static Scalar one() {
		return BulletProofs.getFactory().one();
	}

	/** A random value below 2^n, read as unsigned. */
	static long randomValue(int n) {
		long v = rnd.nextLong();
		return n == 64 ? v : v >>> (64 - n);
	}

	static final class Stmt {
		final int n;
		final long[] values;
		final Scalar[] blindings;
		List<ECPoint> commits;

		Stmt(int n, int m) {
			this.n = n;
			values = new long[m];
			blindings = new Scalar[m];
			for (int j = 0; j < m; j++) {
				values[j] = randomValue(n);
				blindings[j] = Utils.randomScalar();
			}
			// Both edges of the range, whenever there is room for them.
			if (m >= 2) {
				values[0] = 0;
				values[m - 1] = n == 64 ? -1L : (1L << n) - 1;
			}
			commits = RangeProof.commitments(pc, values, blindings);
		}

		RangeProof prove(BulletProofGenerators gens) {
			return RangeProof.prove(new Transcript(), pc, gens, values, blindings, commits, n);
		}
	}

	static BulletProofGenerators gens(int n, int m) throws Exception {
		return new BulletProofGenerators(n, m);
	}

	static boolean verifies(RangeProof p, BulletProofGenerators g, List<ECPoint> commits, int n) {
		return p.verify(new Transcript(), pc, g, commits, n);
	}

	static boolean throwsIllegalArgument(Runnable r) {
		try {
			r.run();
			return false;
		} catch (IllegalArgumentException expected) {
			return true;
		}
	}

	public static void main(String[] args) throws Exception {
		pass = 0; fail = 0; rnd.setSeed(20260915L);                     // the app runs this in-process, repeatedly
		pc = PedersenCommitment.getDefault();

		/* ---- completeness, over every supported bit width ---- */
		for (int n : new int[] { 8, 16, 32, 64 }) {
			for (int m : new int[] { 1, 2, 4, 8 }) {
				Stmt s = new Stmt(n, m);
				// Proving and verifying from separately built generators: they
				// must be derived identically, and nothing may be consumed.
				BulletProofGenerators g = gens(n, m);
				RangeProof p = s.prove(g);
				check("n=" + n + " m=" + m + " verifies", verifies(p, gens(n, m), s.commits, n));
				check("n=" + n + " m=" + m + " verifies again with the prover's generators",
						verifies(p, g, s.commits, n));
				int logNm = Integer.numberOfTrailingZeros(n * m);
				check("n=" + n + " m=" + m + " wire size", p.wireSize() == 32 * (4 + 2 * logNm + 5));
			}
		}
		for (int trial = 0; trial < 24; trial++) {
			int n = new int[] { 8, 16, 32, 64 }[rnd.nextInt(4)];
			int m = 1 << rnd.nextInt(5);
			Stmt s = new Stmt(n, m);
			check("random n=" + n + " m=" + m + " verifies", verifies(s.prove(gens(n, m)), gens(n, m), s.commits, n));
		}

		/* ---- a commitment to a value past the range ---- */
		// The prover decomposes values[1], which is in range, but the verifier
		// holds a commitment to values[1] + 2^n under the same blinding. Nothing
		// in the proof can reconcile the two.
		for (int n : new int[] { 8, 16, 32, 64 }) {
			Stmt s = new Stmt(n, 2);
			BigInteger past = new BigInteger(Long.toUnsignedString(s.values[1])).add(BigInteger.ONE.shiftLeft(n));
			List<ECPoint> bad = new ArrayList<>(s.commits);
			bad.set(1, pc.commit(Utils.scalar(past), s.blindings[1]));
			BulletProofGenerators g = gens(n, 2);
			RangeProof p = RangeProof.prove(new Transcript(), pc, g, s.values, s.blindings, bad, n);
			check("n=" + n + ": commitment to v + 2^n is rejected", !verifies(p, g, bad, n));
		}

		/* ---- the prover refuses what it cannot prove ---- */
		check("prover refuses 256 at n=8", throwsIllegalArgument(() -> {
			try {
				RangeProof.prove(new Transcript(), pc, gens(8, 1), new long[] { 256 },
						new Scalar[] { Utils.randomScalar() },
						RangeProof.commitments(pc, new long[] { 256 }, new Scalar[] { one() }), 8);
			} catch (IllegalArgumentException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}));
		check("prover refuses 65536 at n=16", throwsIllegalArgument(() -> {
			try {
				RangeProof.prove(new Transcript(), pc, gens(16, 1), new long[] { 65536 },
						new Scalar[] { one() },
						RangeProof.commitments(pc, new long[] { 65536 }, new Scalar[] { one() }), 16);
			} catch (IllegalArgumentException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}));
		check("prover refuses three values", throwsIllegalArgument(() -> {
			long[] v = { 1, 2, 3 };
			Scalar[] b = { one(), one(), one() };
			try {
				RangeProof.prove(new Transcript(), pc, gens(8, 4), v, b, RangeProof.commitments(pc, v, b), 8);
			} catch (IllegalArgumentException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}));
		check("prover refuses a 12-bit width", throwsIllegalArgument(() -> {
			long[] v = { 1 };
			Scalar[] b = { one() };
			try {
				RangeProof.prove(new Transcript(), pc, gens(16, 1), v, b, RangeProof.commitments(pc, v, b), 12);
			} catch (IllegalArgumentException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}));

		/* ---- one honest proof, then everything that must not verify against it ---- */
		int n = 16, m = 4;
		Stmt s = new Stmt(n, m);
		s.values[1] = s.values[2] ^ 0x5a5a;       // distinct, so a swap changes the statement
		s.commits = RangeProof.commitments(pc, s.values, s.blindings);
		BulletProofGenerators g = gens(32, m);     // room to try a wider width too
		RangeProof p = s.prove(g);
		check("reference proof verifies", verifies(p, g, s.commits, n));

		List<ECPoint> swapped = new ArrayList<>(s.commits);
		swapped.set(1, s.commits.get(2));
		swapped.set(2, s.commits.get(1));
		check("swapped commitments are rejected", !verifies(p, g, swapped, n));

		List<ECPoint> reversed = new ArrayList<>(s.commits);
		java.util.Collections.reverse(reversed);
		check("reversed commitments are rejected", !verifies(p, g, reversed, n));

		check("verifying at n=8 is rejected", !verifies(p, g, s.commits, 8));
		check("verifying at n=32 is rejected", !verifies(p, g, s.commits, 32));
		check("verifying with two of the four commitments is rejected",
				!verifies(p, g, s.commits.subList(0, 2), n));
		check("verifying with three commitments is rejected", !verifies(p, g, s.commits.subList(0, 3), n));
		check("verifying with too few generators is rejected",
				!verifies(p, gens(8, m), s.commits, n));

		ECPoint other = pc.getB().compress();
		Scalar bump = one();
		InnerProductProof ipp = p.getIpp();
		List<RangeProof> tampered = new ArrayList<>();
		List<String> what = new ArrayList<>();
		tampered.add(new RangeProof(other, p.getS(), p.getT1(), p.getT2(), p.getTx(), p.getTxBlinding(), p.getEBlinding(), ipp));
		what.add("A");
		tampered.add(new RangeProof(p.getA(), other, p.getT1(), p.getT2(), p.getTx(), p.getTxBlinding(), p.getEBlinding(), ipp));
		what.add("S");
		tampered.add(new RangeProof(p.getA(), p.getS(), other, p.getT2(), p.getTx(), p.getTxBlinding(), p.getEBlinding(), ipp));
		what.add("T1");
		tampered.add(new RangeProof(p.getA(), p.getS(), p.getT1(), other, p.getTx(), p.getTxBlinding(), p.getEBlinding(), ipp));
		what.add("T2");
		tampered.add(new RangeProof(p.getA(), p.getS(), p.getT1(), p.getT2(), p.getTx().add(bump), p.getTxBlinding(), p.getEBlinding(), ipp));
		what.add("t_x");
		tampered.add(new RangeProof(p.getA(), p.getS(), p.getT1(), p.getT2(), p.getTx(), p.getTxBlinding().add(bump), p.getEBlinding(), ipp));
		what.add("t_x blinding");
		tampered.add(new RangeProof(p.getA(), p.getS(), p.getT1(), p.getT2(), p.getTx(), p.getTxBlinding(), p.getEBlinding().add(bump), ipp));
		what.add("e blinding");
		tampered.add(new RangeProof(p.getA(), p.getS(), p.getT1(), p.getT2(), p.getTx(), p.getTxBlinding(), p.getEBlinding(),
				new InnerProductProof(ipp.getL(), ipp.getR(), ipp.getA().add(bump), ipp.getB())));
		what.add("inner product a");
		tampered.add(new RangeProof(p.getA(), p.getS(), p.getT1(), p.getT2(), p.getTx(), p.getTxBlinding(), p.getEBlinding(),
				new InnerProductProof(ipp.getL(), ipp.getR(), ipp.getA(), ipp.getB().add(bump))));
		what.add("inner product b");
		List<ECPoint> badL = new ArrayList<>(ipp.getL());
		badL.set(0, other);
		tampered.add(new RangeProof(p.getA(), p.getS(), p.getT1(), p.getT2(), p.getTx(), p.getTxBlinding(), p.getEBlinding(),
				new InnerProductProof(badL, ipp.getR(), ipp.getA(), ipp.getB())));
		what.add("first L");
		List<ECPoint> badR = new ArrayList<>(ipp.getR());
		badR.set(badR.size() - 1, other);
		tampered.add(new RangeProof(p.getA(), p.getS(), p.getT1(), p.getT2(), p.getTx(), p.getTxBlinding(), p.getEBlinding(),
				new InnerProductProof(ipp.getL(), badR, ipp.getA(), ipp.getB())));
		what.add("last R");
		List<ECPoint> shortL = new ArrayList<>(ipp.getL().subList(0, ipp.getL().size() - 1));
		List<ECPoint> shortR = new ArrayList<>(ipp.getR().subList(0, ipp.getR().size() - 1));
		tampered.add(new RangeProof(p.getA(), p.getS(), p.getT1(), p.getT2(), p.getTx(), p.getTxBlinding(), p.getEBlinding(),
				new InnerProductProof(shortL, shortR, ipp.getA(), ipp.getB())));
		what.add("one inner product round dropped");
		for (int i = 0; i < tampered.size(); i++) {
			check("tampered " + what.get(i) + " is rejected", !verifies(tampered.get(i), g, s.commits, n));
		}

		// The same proof, untouched, still verifies: the rejections above were
		// about the tampering, not about the proof or the generators.
		check("reference proof still verifies after the tampering runs", verifies(p, g, s.commits, n));

		/* ---- the transcript binds ---- */
		Transcript extra = new Transcript();
		extra.append("context", "not the prover's");
		check("a verifier transcript with extra context is rejected", !p.verify(extra, pc, g, s.commits, n));
		Transcript proverExtra = new Transcript();
		proverExtra.append("context", "the prover's");
		RangeProof pe = RangeProof.prove(proverExtra, pc, g, s.values, s.blindings, s.commits, n);
		check("a prover transcript with extra context is rejected by a plain verifier", !verifies(pe, g, s.commits, n));
		Transcript sameExtra = new Transcript();
		sameExtra.append("context", "the prover's");
		check("the same extra context on both sides verifies", pe.verify(sameExtra, pc, g, s.commits, n));

		/* ---- identity points ---- */
		long[] zeroValue = { 0 };
		Scalar[] zeroBlinding = { BulletProofs.getFactory().zero() };
		List<ECPoint> identityCommit = RangeProof.commitments(pc, zeroValue, zeroBlinding);
		BulletProofGenerators g1 = gens(8, 1);
		RangeProof pi = RangeProof.prove(new Transcript(), pc, g1, zeroValue, zeroBlinding, identityCommit, 8);
		check("a proof about the identity commitment is rejected", !verifies(pi, g1, identityCommit, 8));

		/* ---- challenges ---- */
		// Transcript.challengeScalar hashes the transcript without absorbing its
		// label or its output: two draws in a row repeat. RangeProof.challenge is
		// what this protocol uses instead. The vendored R1CS prover and verifier
		// draw y, z and u, x back to back with the raw call; that is reported, not
		// gated, since it is not this class's code.
		Transcript raw = new Transcript();
		raw.append("V", pc.getB().compress());
		boolean rawCollide = Arrays.equals(raw.challengeScalar("y").toByteArray(), raw.challengeScalar("z").toByteArray());
		Transcript chained = new Transcript();
		chained.append("V", pc.getB().compress());
		Scalar cy = RangeProof.challenge(chained, "y");
		Scalar cz = RangeProof.challenge(chained, "z");
		check("consecutive RangeProof.challenge draws differ", !Arrays.equals(cy.toByteArray(), cz.toByteArray()));
		System.out.println("  note: vendored Transcript.challengeScalar(\"y\") then (\"z\") "
				+ (rawCollide ? "RETURN THE SAME SCALAR" : "differ"));

		System.out.println("Dedicated Bulletproofs range proof: passed " + pass + ", failed " + fail);
		if (fail > 0) {
			throw new IllegalStateException("checks failed: " + fail);   // not System.exit: the app runs this in-process
		}
	}
}
