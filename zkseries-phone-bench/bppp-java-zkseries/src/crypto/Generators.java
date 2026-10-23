package crypto;

import ec.ECPoint;
import zk.bulletproofs.PedersenCommitment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The generators of the common input: G in G, bold G in G^Nm, bold H in G^(Nv+7)
 * (Section 5.3, "Common input"), with N_v = 1, since every input is one scalar.
 *
 * The paper's setup chooses them uniformly at random (Section 4.3). Here G and H_0 are
 * fixed by ZK-Series's commitments V = v B + s B_blind (PedersenCommitment), so that
 * the proofs speak about the commitments ZK-Series publishes: V = Com(v; s) = v G + s H_0
 * (Section 2, "Commitments as Inputs") with
 *
 *   G   = B, the value generator (the Ristretto basepoint),
 *   H_0 = B_blind, the blinding generator.
 *
 * The others are hashed to the group from a tag and an index, so no discrete-log
 * relation among them is known.
 */
public final class Generators {

    private static final String TAG = "bppp-java-zkseries/v1/generator/";

    public final ECPoint G;
    public final ECPoint[] Gvec;
    public final ECPoint[] H;
    private volatile Group.PublicFoldTables publicFoldTables;

    private Generators(ECPoint G, ECPoint[] Gvec, ECPoint[] H) {
        this.G = G;
        this.Gvec = Gvec;
        this.H = H;
    }

    public static PedersenCommitment pedersen() {
        try {
            return PedersenCommitment.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA3-512 unavailable", e);
        }
    }

    /** Generators for N_m multiplication gates and N_v = 1. */
    public static Generators create(int nm) {
        PedersenCommitment pc = pedersen();
        ECPoint[] gvec = new ECPoint[nm];
        for (int i = 0; i < nm; i++) gvec[i] = hash("G", i);
        ECPoint[] h = new ECPoint[8];
        h[0] = pc.getBlinding().decompress();
        for (int i = 1; i < 8; i++) h[i] = hash("H", i);
        return new Generators(pc.getB().decompress(), gvec, h);
    }

    /** Per-instance, bounded by this generator vector; no global cache or witness material. */
    public Group.PublicFoldTables publicFoldTables() {
        if (Gvec.length < 2) return null;
        Group.PublicFoldTables tables = publicFoldTables;
        if (tables == null || !tables.matches(Gvec)) {
            synchronized (this) {
                tables = publicFoldTables;
                if (tables == null || !tables.matches(Gvec)) publicFoldTables = tables = Group.preparePublicFold(Gvec);
            }
        }
        return tables;
    }

    private static ECPoint hash(String which, int i) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            md.update((TAG + which + "/" + i).getBytes(StandardCharsets.UTF_8));
            return Group.factory().fromUniformBytes(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }
}
