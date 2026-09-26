package ru.lighthouse.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Hard caller deadlines: native DNS and multi-address HTTPS may ignore interruption. */
final class NetworkDeadline {
    private static final ThreadPoolExecutor DNS = pool("dns", 8);
    private static final ThreadPoolExecutor HTTP = pool("http", 8);
    static final ThreadPoolExecutor EXTRA = pool("extra", 3);

    private NetworkDeadline() { }

    static ThreadFactory threads(String name) {
        AtomicInteger number = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "lighthouse-" + name + "-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    static ThreadPoolExecutor pool(String name, int maximum) {
        // No unbounded queue or replacement threads for uninterruptible OS calls.
        return new ThreadPoolExecutor(0, maximum, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), threads(name), new ThreadPoolExecutor.AbortPolicy());
    }

    static InetAddress[] resolve(String host, int timeoutMs) throws IOException {
        return call(DNS, () -> InetAddress.getAllByName(host), timeoutMs, "DNS " + host);
    }

    static <T> T http(Callable<T> operation, int timeoutMs) throws IOException {
        return call(HTTP, operation, timeoutMs, "HTTPS");
    }

    static <T> T call(ThreadPoolExecutor pool, Callable<T> operation, int timeoutMs, String stage) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Scan cancelled");
        Future<T> future;
        try { future = pool.submit(operation); }
        catch (RejectedExecutionException busy) {
            throw new IOException(stage + ": previous network calls have not stopped; retry later", busy);
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            throw new SocketTimeoutException(stage + ": exceeded " + timeoutMs + " ms");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Scan cancelled");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException(stage + ": " + cause, cause);
        } finally {
            future.cancel(true);
        }
    }
}
