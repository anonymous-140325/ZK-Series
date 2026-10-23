package zk.bulletproofs;

import ec.Scalar;

public interface Gadget<T extends GadgetParams> {

    GadgetType getType();

    boolean isBatchProof();

    boolean isNumericInput();

    boolean isMultiColumn();

    GadgetParams unpackParams(String params, Object value);

    BpProof generate(Object value, T params, Scalar rnd, PedersenCommitment pedersenCommitment, BulletProofGenerators generators);

    boolean verify(T params, BpProof proof, PedersenCommitment pedersenCommitment, BulletProofGenerators generators);
}
