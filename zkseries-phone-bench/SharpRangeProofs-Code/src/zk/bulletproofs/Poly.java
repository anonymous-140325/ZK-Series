package zk.bulletproofs;

import ec.Scalar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Poly {

    private final List<Scalar> coefficients = new ArrayList<>();
    
    public Poly() {
    	
    }

    public Poly(Scalar... coef) {
        Collections.addAll(coefficients, coef);
    }

    public void add(Scalar w) {
        coefficients.add(w);
    }

    public Scalar get(int index) {
        return coefficients.get(index);
    }

    public Scalar at(Scalar x) {
        Scalar res = coefficients.get(0);

        Scalar cp = BulletProofs.getFactory().one();
        for (int i = 1; i < coefficients.size(); i++) {
            cp = cp.multiply(x);
            res = coefficients.get(i).multiplyAndAdd(cp, res);
        }

        return res;
    }
}