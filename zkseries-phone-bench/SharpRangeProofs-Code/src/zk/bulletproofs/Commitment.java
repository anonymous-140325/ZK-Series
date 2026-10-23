package zk.bulletproofs;

import java.io.Serializable;

import ec.ECPoint;

public class Commitment implements Serializable {

    private static final long serialVersionUID = 1L;

	private final ECPoint commitment;

    private final Variable variable;
    
	
	public Commitment(ECPoint commitment, Variable variable) {
		this.commitment = commitment;
		this.variable = variable;
	}

	public ECPoint getCommitment() {
		return commitment;
	}
	
	public Variable getVariable() {
		return variable;
	}
	
	public boolean equals(Commitment c2) {
		return commitment.equals(c2.getCommitment()) && variable.equals(c2.getVariable());
	}
}
