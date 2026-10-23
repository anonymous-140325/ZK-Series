package zk.bulletproofs;

import ec.Scalar;

public class Term {

    private final Variable variable;

    private final Scalar scalar;
    
    public Term(Variable variable, Scalar scalar) {
    	this.variable = variable;
    	this.scalar = scalar;
    }
    
    public Variable getVariable() {
    	return variable;
    }
    
    public Scalar getScalar() {
    	return scalar;
    }
}
