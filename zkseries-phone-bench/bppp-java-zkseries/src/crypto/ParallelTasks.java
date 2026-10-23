package crypto;

import java.util.concurrent.*;

/**
 * One shared, bounded budget of worker threads for all proof arithmetic: a cap of n threads
 * means the calling thread plus at most n - 1 workers. The default is 1 (no workers), so
 * proofs are single-threaded unless Group.configureThreads is called.
 *
 * start() never blocks and never queues: if no worker slot is free it returns null and the
 * caller does the work itself. Work started from a worker therefore cannot deadlock waiting
 * for another worker.
 */
final class ParallelTasks {
    private static volatile int workers = 1;
    private static volatile Pool pool;

    private static final class Pool {
        final Semaphore slots;
        final ThreadPoolExecutor executor;
        Pool(int width) {
            slots = new Semaphore(width);
            executor = new ThreadPoolExecutor(0, width, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                    r -> { Thread t = new Thread(r, "bppp-arithmetic"); t.setDaemon(true); return t; },
                    new ThreadPoolExecutor.AbortPolicy());
        }
    }

    static int workers() { return workers; }

    /** Sets the cap; call only when no proof is running. */
    static synchronized void configure(int cap) {
        if (cap < 1 || cap > 8) throw new IllegalArgumentException("thread cap must be 1..8");
        Pool previous = pool;
        if (previous != null) {
            previous.executor.shutdown();
            try {
                if (!previous.executor.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("arithmetic still running");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("reconfiguration interrupted", interrupted);
            }
        }
        pool = null;
        workers = cap;
    }

    private static synchronized Pool currentPool() {
        if (pool == null) pool = new Pool(workers - 1);
        return pool;
    }

    /** The job on a free worker, or null if there is none (the caller then runs it itself). */
    static <T> Future<T> start(Callable<T> job) {
        if (workers < 2) return null;
        Pool context = currentPool();
        if (!context.slots.tryAcquire()) return null;
        FutureTask<T> task = new FutureTask<T>(job) { @Override protected void done() { context.slots.release(); } };
        try { context.executor.execute(task); return task; }
        catch (RejectedExecutionException busy) { task.cancel(false); return null; }   // cancel runs done(): slot released
    }

    static <T> T await(Future<T> task) {
        try { return task.get(); }
        catch (InterruptedException interrupted) {
            task.cancel(true); Thread.currentThread().interrupt(); throw new IllegalStateException("parallel arithmetic interrupted", interrupted);
        } catch (ExecutionException failed) { throw new IllegalStateException("parallel arithmetic failed", failed.getCause()); }
    }
}
