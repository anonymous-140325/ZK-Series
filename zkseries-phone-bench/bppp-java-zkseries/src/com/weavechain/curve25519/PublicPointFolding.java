package com.weavechain.curve25519;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Folding of public generators, out[i] = P[2i] + k P[2i+1] for one public scalar k:
 * the generator fold of the norm argument (secp256k1-zkp bppp_norm_product_impl.h,
 * lines 331-336 and 350-353), computed faster than one point multiplication per pair.
 *
 * <b>How.</b> k is recoded once per round into width-5 non-adjacent form (Plan), instead of
 * once per pair as a plain point multiplication would. Each k P[2i+1] is then evaluated from
 * the library's lookup table of odd multiples of P[2i+1], by Horner's rule over the nonzero
 * digits: double between digits, add or subtract the table entry. The first round's tables
 * can be prepared once per generator vector (PreparedPairs) and are used only if they belong
 * to the very same point objects (identity, not equality), so a stale table is never used.
 *
 * <b>Why in this package.</b> It uses package-private arithmetic of curve25519-elisabeth
 * 0.1.5 (Weavechain's fork): EdwardsPoint, ProjectiveNielsPoint and its NAF lookup table,
 * Scalar.nonAdjacentForm. It is therefore tied to that library version.
 *
 * Variable time: for PUBLIC points and PUBLIC scalars only (generators and transcript
 * challenges), never for witness values or blindings. Inputs and tables are read-only.
 */
public final class PublicPointFolding {
    private PublicPointFolding() {}

    /** Scheduler supplied by the proof layer, so that all arithmetic shares one thread budget. */
    public interface Scheduler { Future<?> start(Runnable job); }

    /** The public scalar k in non-adjacent form: its nonzero digits, most significant first. */
    public static final class Plan {
        private final int[] positions;
        private final byte[] digits;
        public Plan(Scalar publicScalar) {
            byte[] naf = publicScalar.reduce().nonAdjacentForm();
            int count = 0;
            for (byte digit : naf) if (digit != 0) count++;
            positions = new int[count]; digits = new byte[count];
            int cursor = 0;
            for (int bit = naf.length - 1; bit >= 0; bit--) {
                if (naf[bit] != 0) { positions[cursor] = bit; digits[cursor++] = naf[bit]; }
            }
        }
    }

    /** Lookup tables for the right-hand points P[2i+1] of the first round, owned by one generator vector. */
    public static final class PreparedPairs {
        private final RistrettoElement[] right;
        private final ProjectiveNielsPoint.NafLookupTable[] tables;
        public PreparedPairs(RistrettoElement[] points) {
            if ((points.length & 1) != 0) throw new IllegalArgumentException("even generator count");
            right = new RistrettoElement[points.length / 2];
            tables = new ProjectiveNielsPoint.NafLookupTable[right.length];
            for (int i = 0; i < right.length; i++) {
                right[i] = points[2*i+1];
                tables[i] = ProjectiveNielsPoint.buildNafLookupTable(right[i].repr);
            }
        }
        public boolean matches(RistrettoElement[] points, int length) {
            if (right.length * 2 != length) return false;
            for (int i = 0; i < right.length; i++) if (right[i] != points[2*i+1]) return false;
            return true;
        }
    }

    /** Sequential fold of the first length points. */
    public static RistrettoElement[] fold(RistrettoElement[] points, int length, Plan plan, PreparedPairs prepared) {
        checkShape(points, length);
        RistrettoElement[] out = new RistrettoElement[length / 2];
        foldRange(points, plan, prepared, prepared != null && prepared.matches(points, length), out, 0, out.length);
        return out;
    }

    /**
     * The same fold, with the pairs split in blocks of 32 among the calling thread and up to
     * workers - 1 scheduled ones. Each output entry is written by exactly one thread, and read
     * only after every task has been joined.
     */
    public static RistrettoElement[] foldScheduled(RistrettoElement[] points, int length, Plan plan, PreparedPairs prepared,
                                                   int workers, Scheduler scheduler) {
        if (workers < 1 || workers > 8) throw new IllegalArgumentException("workers must be 1..8");
        checkShape(points, length);
        if (workers == 1 || length < 512 || plan.digits.length == 0) return fold(points, length, plan, prepared);
        boolean cached = prepared != null && prepared.matches(points, length);
        RistrettoElement[] out = new RistrettoElement[length / 2];
        int participants = Math.min(workers, Math.max(1, out.length / 128));
        AtomicInteger next = new AtomicInteger();
        Runnable job = () -> { for (int start; (start = next.getAndAdd(32)) < out.length; )
            foldRange(points, plan, prepared, cached, out, start, Math.min(start + 32, out.length)); };
        Future<?>[] tasks = new Future<?>[participants - 1];
        try {
            for (int i = 0; i < tasks.length; i++) tasks[i] = scheduler.start(job);   // null: no worker free; the caller does the work
            job.run();
            for (Future<?> task : tasks) if (task != null) task.get();
            return out;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("folding interrupted", interrupted);
        } catch (ExecutionException failed) {
            throw new IllegalStateException("folding worker failed", failed.getCause());
        } finally {
            for (Future<?> task : tasks) if (task != null && !task.isDone()) task.cancel(true);
        }
    }

    private static void checkShape(RistrettoElement[] points, int length) {
        if (length < 2 || length > points.length || (length & 1) != 0) throw new IllegalArgumentException("fold length");
    }

    /** out[i] = P[2i] + k P[2i+1] for start <= i < end. */
    private static void foldRange(RistrettoElement[] points, Plan plan, PreparedPairs prepared,
                                  boolean cached, RistrettoElement[] out, int start, int end) {
        for (int i = start; i < end; i++) {
            if (plan.digits.length == 0) { out[i] = points[2*i]; continue; }                       // k = 0
            ProjectiveNielsPoint.NafLookupTable table = cached ? prepared.tables[i]
                    : ProjectiveNielsPoint.buildNafLookupTable(points[2*i+1].repr);
            EdwardsPoint acc = EdwardsPoint.IDENTITY;                                             // Horner over the NAF digits
            int previous = plan.positions[0];
            for (int j = 0; j < plan.digits.length; j++) {
                int gap = previous - plan.positions[j];
                if (gap != 0) acc = acc.multiplyByPow2(gap);
                int digit = plan.digits[j];
                acc = digit > 0 ? acc.add(table.select(digit)).toExtended()
                                : acc.subtract(table.select(-digit)).toExtended();
                previous = plan.positions[j];
            }
            if (previous != 0) acc = acc.multiplyByPow2(previous);                                // acc = k P[2i+1]
            out[i] = new RistrettoElement(points[2*i].repr.add(acc));
        }
    }
}
