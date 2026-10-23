package zk.bulletproofs;

import ec.Curve25519Factory;
import ec.ECPointFactory;
import ec.Scalar;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class BulletProofs {

    //Based on the bulletproofs rust implementation https://github.com/dalek-cryptography/bulletproofs
    //  see LICENSE-orig.txt

    public static final int DEFAULT_GENERATORS_SIZE = 256;

    public final Map<String, Gadget> gadgets = new HashMap<>();

    public static ECPointFactory factory = new Curve25519Factory();

    public BulletProofs() {
    	
    }
    
    public static ECPointFactory getFactory() {
    	return factory;
    }

    public void registerGadget(Gadget gadget) {
        gadgets.put(gadget.getType().name(), gadget);
    }

    public boolean isBatchProof(String gadgetType) {
        Gadget gadget = gadgetType != null ? gadgets.get(gadgetType.toLowerCase(Locale.ROOT)) : null;
        if (gadget != null) {
            return gadget.isBatchProof();
        } else {
            System.out.println("Unknown gadget type " + gadgetType);
            throw new IllegalArgumentException("Unknown gadget type " + gadgetType);
        }
    }

    public boolean isNumericInput(String gadgetType) {
        Gadget gadget = gadgetType != null ? gadgets.get(gadgetType.toLowerCase(Locale.ROOT)) : null;
        if (gadget != null) {
            return gadget.isNumericInput();
        } else {
            System.out.println("Unknown gadget type " + gadgetType);
            throw new IllegalArgumentException("Unknown gadget type " + gadgetType);
        }
    }

    public boolean isMultiColumn(String gadgetType) {
        Gadget gadget = gadgetType != null ? gadgets.get(gadgetType.toLowerCase(Locale.ROOT)) : null;
        if (gadget != null) {
            return gadget.isMultiColumn();
        } else {
            System.out.println("Unknown gadget type " + gadgetType);
            throw new IllegalArgumentException("Unknown gadget type " + gadgetType);
        }
    }

    @SuppressWarnings("unchecked")
    public BpProof generate(String gadgetType, Object value, String gadgetParams, PedersenCommitment pedersenCommitment, Integer nGenerators) throws IOException {
        Gadget gadget = gadgetType != null ? gadgets.get(gadgetType.toLowerCase(Locale.ROOT)) : null;
        if (gadget != null) {
            Scalar rnd = Utils.randomScalar();
            GadgetParams params = gadget.unpackParams(gadgetParams, value);
            BulletProofGenerators generators = new BulletProofGenerators(nGenerators != null ? nGenerators : DEFAULT_GENERATORS_SIZE, 1);
            return gadget.generate(value, params, rnd, pedersenCommitment, generators);
        } else {
            System.out.println("Unknown gadget type " + gadgetType);
            throw new IllegalArgumentException("Unknown gadget type " + gadgetType);
        }
    }

    @SuppressWarnings("unchecked")
    public BpProof generate(GadgetType gadgetType, Object value, GadgetParams gadgetParams, Scalar rnd, PedersenCommitment pedersenCommitment, BulletProofGenerators generators) {
        Gadget gadget = gadgetType != null ? gadgets.get(gadgetType.name()) : null;
        if (gadget != null) {
            return gadget.generate(value, gadgetParams, rnd, pedersenCommitment, generators);
        } else {
            System.out.println("Unknown gadget type " + (gadgetType != null ? gadgetType.name() : null));
            return null;
        }
    }

//    @SuppressWarnings("unchecked")
//    public boolean verify(String gadgetType, String gadgetParams, String proof, PedersenCommitment pedersenCommitment, Integer nGenerators) throws IOException {
//        Gadget gadget = gadgetType != null ? gadgets.get(gadgetType.toLowerCase(Locale.ROOT)) : null;
//        if (gadget != null) {
//            GadgetParams params = gadget.unpackParams(gadgetParams, null);
//            BulletProofGenerators generators = new BulletProofGenerators(nGenerators != null ? nGenerators : DEFAULT_GENERATORS_SIZE, 1);
//            return gadget.verify(params, Proof.deserialize(Base58.decode(proof)), pedersenCommitment, generators);
//        } else {
//            System.out.println("Unknown gadget type " + gadgetType);
//            throw new IllegalArgumentException("Unknown gadget type " + gadgetType);
//        }
//    }

    @SuppressWarnings("unchecked")
    public boolean verify(GadgetType gadgetType, GadgetParams gadgetParams, BpProof proof, PedersenCommitment pedersenCommitment, BulletProofGenerators generators) {
        Gadget gadget = gadgetType != null ? gadgets.get(gadgetType.name()) : null;
        if (gadget != null) {
            try {
                return gadget.verify(gadgetParams, proof, pedersenCommitment, generators);
            } catch (Exception e) {
                System.out.println("Failed verfification");
                e.printStackTrace();
                return false;
            }
        } else {
            System.out.println("Unknown gadget type " + (gadgetType != null ? gadgetType.name() : null));
            return false;
        }
    }

    public GadgetParams paramsWithValue(GadgetType gadgetType, String gadgetParams, Object value) {
        Gadget gadget = gadgetType != null ? gadgets.get(gadgetType.name()) : null;
        if (gadget != null) {
            try {
                return gadget.unpackParams(gadgetParams, value);
            } catch (Exception e) {
                System.out.println("Failed unpacking params");
                e.printStackTrace();
                return null;
            }
        } else {
            System.out.println("Unknown gadget type " + gadgetType);
            return null;
        }
    }
}
