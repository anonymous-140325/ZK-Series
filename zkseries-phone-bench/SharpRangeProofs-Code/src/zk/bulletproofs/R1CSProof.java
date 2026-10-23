package zk.bulletproofs;

import ec.ECPoint;
import ec.Scalar;

public class R1CSProof {

    // Commitment to the values of input wires in the first phase.
    private final ECPoint A_I1;

    // Commitment to the values of output wires in the first phase.
    private final ECPoint A_O1;

    // Commitment to the blinding factors in the first phase.
    private final ECPoint S1;

    // Commitment to the values of input wires in the second phase.
    private final ECPoint A_I2;

    // Commitment to the values of output wires in the second phase.
    private final ECPoint A_O2;

    // Commitment to the blinding factors in the second phase.
    private final ECPoint S2;

    // Commitment to the t_1 coefficient of t(x)
    private final ECPoint T1;

    // Commitment to the t4 coefficient of t(x)
    private final ECPoint T3;

    // Commitment to the t_4 coefficient of t(x)
    private final ECPoint T4;

    // Commitment to the t_5 coefficient of t(x)
    private final ECPoint T5;

    // Commitment to the t_6 coefficient of t(x)
    private final ECPoint T6;

    // Evaluation of the polynomial t(x) at the challenge point x
    private final Scalar tx;

    // Blinding factor for the synthetic commitment to t(x)
    private final Scalar txBlinding;

    // Blinding factor for the synthetic commitment to the inner-product arguments
    private final Scalar eBlinding;

    /// Proof data for the inner-product argument.
    private final InnerProductProof ippProof;
    
    public R1CSProof(ECPoint A_I1, ECPoint A_O1, ECPoint S1, ECPoint A_I2, ECPoint A_O2, ECPoint S2, ECPoint T1, ECPoint T3, ECPoint T4, ECPoint T5, ECPoint T6, Scalar tx, Scalar txBlinding, Scalar eBlinding, InnerProductProof ippProof) {
    	this.A_I1 = A_I1;
    	this.A_O1 = A_O1;
    	this.S1 = S1;
    	this.A_I2 = A_I2;
    	this.A_O2 = A_O2;
    	this.S2 = S2;
    	this.T1 = T1;
    	this.T3 = T3;
    	this.T4 = T4;
    	this.T5 = T5;
    	this.T6 = T6;
    	this.tx = tx;
    	this.txBlinding = txBlinding;
    	this.eBlinding = eBlinding;
    	this.ippProof = ippProof;
    }
    
    public ECPoint getA_I1() {
    	return A_I1;
    }
    
    public ECPoint getA_O1() {
    	return A_O1;
    }
    
    public ECPoint getS1() {
    	return S1;
    }
    
    public ECPoint getA_I2() {
    	return A_I2;
    }
    
    public ECPoint getA_O2() {
    	return A_O2;
    }
    
    public ECPoint getS2() {
    	return S2;
    }
    
    public ECPoint getT1() {
    	return T1;
    }
    
    public ECPoint getT3() {
    	return T3;
    }
    
    public ECPoint getT4() {
    	return T4;
    }
    
    public ECPoint getT5() {
    	return T5;
    }
    
    public ECPoint getT6() {
    	return T6;
    }
    
    public Scalar getTx() {
    	return tx;
    }
    
    public Scalar getTxBlinding() {
    	return txBlinding;
    }
    
    public Scalar getEBlinding() {
    	return eBlinding;
    }
    
    public InnerProductProof getIppProof() {
    	return ippProof;
    }
}
