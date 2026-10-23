package zk.bulletproofs.gadgets;

import zk.bulletproofs.GadgetType;

/**
 * Gadget type constants.
 *
 * Vendored from Weavechain's bulletproofs-gadgets (MIT) -- see
 * gadgets/LICENSE-weavechain.txt. Reduced to the two range gadgets this tree needs:
 * the single-value baseline and its batched counterpart.
 */
public enum Gadgets implements GadgetType {

	number_in_range,

	numbers_in_range
}
