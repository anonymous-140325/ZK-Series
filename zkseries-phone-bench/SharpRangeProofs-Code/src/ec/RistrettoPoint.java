package ec;

import com.weavechain.curve25519.RistrettoElement;

public class RistrettoPoint implements ECPoint {

    public static final RistrettoPoint BASEPOINT = new RistrettoPoint(RistrettoElement.BASEPOINT);

    public static final RistrettoPoint IDENTITY = new RistrettoPoint(RistrettoElement.IDENTITY);

    private final RistrettoElement point;
    
    public RistrettoPoint(RistrettoElement point) {
    	this.point = point;
    }
    
    public RistrettoElement getPoint() {
    	return point;
    }

    public byte[] toByteArray() {
        return point.compress().toByteArray();
    }

    public ECPoint compress() {
        return new CompressedRistrettoPoint(point.compress());
    }

    public ECPoint decompress() {
        return this;
    }

    public ECPoint add(ECPoint other) {
        return new RistrettoPoint(point.add(((RistrettoPoint)other).getPoint()));
    }

    public ECPoint subtract(ECPoint other) {
        return new RistrettoPoint(point.subtract(((RistrettoPoint)other).getPoint()));
    }

    public ECPoint multiply(Scalar scalar) {
        return new RistrettoPoint(point.multiply(((RScalar)scalar).getScalar()));
    }

    public ECPoint negate() {
        return new RistrettoPoint(point.negate());
    }

    public ECPoint dbl() {
        return new RistrettoPoint(point.dbl());
    }

    public String toString() {
        return point.toString();
    }
    
    @Override
    public boolean equals(ECPoint other) {
    	if(other instanceof RistrettoPoint) {
    		RistrettoPoint other2 = (RistrettoPoint) other;
    		return point.equals(other2.point);
    	}
    	return false;
    }
}
