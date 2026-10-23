package crypto;

import ec.ECPoint;
import ec.Scalar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The Fiat-Shamir transcript that turns the paper's interactive protocols into
 * non-interactive ones (Section 2.2, footnote 4: every verifier message becomes a
 * hash of the entire transcript so far).
 *
 * Everything absorbed is labeled and length-prefixed. A challenge absorbs its own
 * label first and its value after, so challenges drawn back to back differ and each
 * depends on every earlier one. Digests are SHA-512, reduced wide into the scalar
 * field. A challenge finishes a clone of the running digest, which equals hashing
 * the whole transcript so far.
 */
public final class Transcript {

    private final MessageDigest state;

    public Transcript(String domain) {
        try {
            state = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
        append("domain", domain.getBytes(StandardCharsets.UTF_8));
    }

    public void append(String label, byte[] data) {
        byte[] l = label.getBytes(StandardCharsets.UTF_8);
        writeLen(l.length);
        state.update(l);
        writeLen(data.length);
        state.update(data);
    }

    private void writeLen(int n) {
        for (int i = 0; i < 4; i++) state.update((byte) (n >>> (8 * i)));
    }

    public void append(String label, long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (8 * i));
        append(label, b);
    }

    public void append(String label, Scalar s) { append(label, s.reduce().toByteArray()); }

    public void append(String label, ECPoint p) { append(label, p.compress().toByteArray()); }

    /** A challenge bound to its label and absorbed once drawn. */
    public Scalar challenge(String label) {
        append("challenge", label.getBytes(StandardCharsets.UTF_8));
        byte[] digest;
        try {
            digest = ((MessageDigest) state.clone()).digest();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("SHA-512 digest cannot be cloned", e);
        }
        Scalar c = Group.factory().fromBytesModOrderWide(digest);
        append(label, c);
        return c;
    }
}
