package zk.bulletproofs;

import java.math.BigInteger;

public class Allocated {

    private final Variable variable;

    private final BigInteger assignment;
    
    public Allocated(Variable variable, BigInteger assignment) {
    	this.variable = variable;
    	this.assignment = assignment;
    }
    
    public Variable getVariable() {
    	return variable;
    }
    
    public BigInteger getAssignment() {
    	return assignment;
    }
}
