package ec;

import java.io.Serializable;

import com.weavechain.curve25519.*;

public class CompressedRistrettoPoint implements ECPoint, Serializable {

    private static final long serialVersionUID = 1L;
	private final CompressedRistretto point;
    
    public CompressedRistrettoPoint(CompressedRistretto point) {
        this.point = point;
    }

    public CompressedRistrettoPoint(byte[] data) {
        this(new CompressedRistretto(data));
    }

    @Override
    public byte[] toByteArray() {
        return point.toByteArray();
    }

    @Override
    public ECPoint compress() {
        return this;
    }

    @Override
    public ECPoint decompress() {
        try {
            return new RistrettoPoint(point.decompress());
        } catch (InvalidEncodingException e) {
            System.out.println("Failed decompression");
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public ECPoint add(ECPoint other) {
        return null;
    }

    @Override
    public ECPoint subtract(ECPoint other) {
        return null;
    }

    @Override
    public ECPoint multiply(Scalar scalar) {
        return null;
    }

    @Override
    public ECPoint negate() {
        return null;
    }

    @Override
    public ECPoint dbl() {
        return null;
    }

    @Override
    public String toString() {
        return point.toString();
    }
    
    @Override
    public boolean equals(ECPoint other) {
    	if(other instanceof CompressedRistrettoPoint) {
    		CompressedRistrettoPoint other2 = (CompressedRistrettoPoint) other;
    		return point.equals(other2.point);
    	}
    	return false;
    }
}
