package zk.bulletproofs;

public class LRO {

    private final Variable left;

    private final Variable right;

    private final Variable output;
    
    public LRO(Variable left, Variable right, Variable output) {
    	this.left = left;
    	this.right = right;
    	this.output = output;
    }
    
    public Variable getLeft() {
    	return left;
    }
    
    public Variable getRight() {
    	return right;
    }
    
    public Variable getOutput() {
    	return output;
    }
}