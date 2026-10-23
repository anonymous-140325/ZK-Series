
package zk.bulletproofs;

import ec.ECPoint;

import java.util.List;

public class BulletProofGenShare {

    private BulletProofGenerators generators;

    private int share;
    
    public BulletProofGenShare(BulletProofGenerators generators, int share) {
    	this.generators = generators;
    	this.share = share;
    }
    
    public BulletProofGenerators getBulletProofGenerators() {
    	return generators;
    }
    
    public int getShare() {
    	return share;
    }

    public List<ECPoint> getG(int size) {
        List<ECPoint> result = generators.getG().get(share);
        return result.size() > size ? result.subList(0, size) : result;
    }

    public List<ECPoint> getH(int size) {
        List<ECPoint> result = generators.getH().get(share);
        return result.size() > size ? result.subList(0, size) : result;
    }
}
