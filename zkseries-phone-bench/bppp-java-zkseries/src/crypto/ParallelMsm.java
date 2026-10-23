package crypto;

import ec.ECPoint;
import ec.Scalar;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Two uses of the shared worker budget (ParallelTasks) for multiscalar multiplications:
 *
 *   - start/await: compute one multiplication on a worker while the caller computes another
 *     (the norm argument's X while it computes R);
 *   - evaluate: one large multiplication with its Pippenger windows spread over the workers.
 *
 * Neither changes the result: the same library algorithms compute the same group element.
 * Scalars may include secrets; the arithmetic is variable time, as it was before.
 * Input lists must stay unchanged until await returns.
 */
final class ParallelMsm {
    private static volatile boolean enabled = false;
    static void configure(boolean on) { enabled = on; }

    /** X on a worker, or null if parallel rounds are off, the input is small, or no worker is free. */
    static Future<ECPoint> start(List<Scalar> scalars, List<ECPoint> points) {
        if (!enabled || scalars.size() < 512) return null;
        return ParallelTasks.start(() -> Group.msm(scalars, points));
    }

    static ECPoint await(Future<ECPoint> task, List<Scalar> scalars, List<ECPoint> points) {
        return task == null ? Group.msm(scalars, points) : ParallelTasks.await(task);
    }

    /** Pippenger, with its windows computed concurrently when workers are available. */
    static com.weavechain.curve25519.RistrettoElement evaluate(
            List<com.weavechain.curve25519.Scalar> scalars, List<com.weavechain.curve25519.RistrettoElement> points) {
        int participants = Math.min(ParallelTasks.workers(), points.size() / 512);
        if (participants <= 1) return com.weavechain.curve25519.MulUtils.mulPippenger(scalars, points);
        com.weavechain.curve25519.PippengerWindows plan = new com.weavechain.curve25519.PippengerWindows(scalars, points);
        com.weavechain.curve25519.RistrettoElement[] sums = new com.weavechain.curve25519.RistrettoElement[plan.windows()];
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        Callable<Void> job = () -> { for (int w; (w = next.getAndIncrement()) < sums.length; ) sums[w] = plan.window(w); return null; };
        List<Future<Void>> tasks = new java.util.ArrayList<>();
        try {
            for (int i = 1; i < participants; i++) { Future<Void> t = ParallelTasks.start(job); if (t != null) tasks.add(t); }
            try { job.call(); } catch (Exception failed) { throw new IllegalStateException("window computation failed", failed); }
            for (Future<Void> t : tasks) ParallelTasks.await(t);                      // every window written before combine
            return plan.combine(sums);
        } finally {
            for (Future<?> t : tasks) if (!t.isDone()) t.cancel(true);
        }
    }
}
