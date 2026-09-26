package ru.lighthouse.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Publishes in completion order, never waits for a particular catalogue index. */
final class ProbeBatch {
    static <T> List<T> run(List<Callable<T>> tasks, int concurrency, long idleTimeoutMs, Consumer<T> progress) {
        return run(tasks, concurrency, idleTimeoutMs, Long.MAX_VALUE, progress);
    }

    static <T> List<T> run(List<Callable<T>> tasks, int concurrency, long idleTimeoutMs, long deadline, Consumer<T> progress) {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, NetworkDeadline.threads("probe"));
        ExecutorCompletionService<T> completion = new ExecutorCompletionService<>(pool);
        List<Future<T>> futures = new ArrayList<>();
        List<T> results = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) futures.add(completion.submit(task));
            for (int remaining = tasks.size(); remaining > 0; remaining--) {
                long remainingNanos = deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
                if (remainingNanos <= 0) break;
                Future<T> future = completion.poll(Math.min(TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs), remainingNanos), TimeUnit.NANOSECONDS);
                if (future == null && deadline != Long.MAX_VALUE && System.nanoTime() >= deadline) break;
                if (future == null) throw new IllegalStateException("Network workers did not finish in time", new TimeoutException());
                T result = future.get();
                results.add(result);
                if (progress != null) progress.accept(result);
            }
            return results;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Scan cancelled");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("Probe failed", cause);
        } finally {
            for (Future<T> future : futures) future.cancel(true);
            pool.shutdownNow();
        }
    }
}
