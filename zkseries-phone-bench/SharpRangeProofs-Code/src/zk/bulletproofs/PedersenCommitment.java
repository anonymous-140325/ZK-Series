package zk.bulletproofs;

import ec.ECPoint;
import ec.Scalar;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class PedersenCommitment {

    private final ECPoint b;

    private final ECPoint blinding;
    
    private static final BigInteger GROUP_ORDER = new BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989");
    
    public PedersenCommitment(ECPoint b, ECPoint blinding) {
    	this.b = b;
    	this.blinding = blinding;
    }
    
    public ECPoint getB() {
    	return b;
    }
    
    public ECPoint getBlinding() {
    	return blinding;
    }

    public static PedersenCommitment getDefault() throws NoSuchAlgorithmException {
        MessageDigest md = Sha3.sha3_512();
        md.update(BulletProofs.getFactory().basepoint().compress().toByteArray());
        byte[] digest = md.digest();

        return new PedersenCommitment(
                BulletProofs.getFactory().basepoint(),
                BulletProofs.getFactory().fromUniformBytes(digest)
        );
    }

    public static PedersenCommitment getRandom() throws NoSuchAlgorithmException {
        MessageDigest md = Sha3.sha3_512();
        byte[] rndp = new byte[32];
        Utils.random().nextBytes(rndp);
        ECPoint committment = BulletProofs.getFactory().fromUniformBytes(rndp);
        md.update(committment.compress().toByteArray());
        byte[] digest = md.digest();

        return new PedersenCommitment(
                committment,
                BulletProofs.getFactory().fromUniformBytes(digest)
        );
    }

    public ECPoint commit(Scalar value, Scalar blinding) {
        return b.multiply(value).add(this.blinding.multiply(blinding)).compress();
    }
    
    public ECPoint commit(BigInteger value, BigInteger blinding) {
    	BigInteger modValue = value.mod(GROUP_ORDER);
    	BigInteger modBlinding = blinding.mod(GROUP_ORDER);
    	
        return commit(Utils.scalar(modValue), Utils.scalar(modBlinding));
    }
}
