package com.studyhub.concurrent;

import com.studyhub.transfer.SharedFileRecord;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [MEMBER 5: Multithreading & URL/HTTP Networking]
 * Central thread pool coordinator managing asynchronous background tasks
 * (DNS resolution, HTTP link scraping, file catalog searching) with live thread pool metrics.
 */
public class ResourceConcurrentEngine {

    private final ThreadPoolExecutor executor;
    private final AtomicInteger threadSequence = new AtomicInteger(1);

    public ResourceConcurrentEngine() {
        this(5);
    }

    public ResourceConcurrentEngine(int poolSize) {
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "StudyHub-Worker-" + threadSequence.getAndIncrement());
            t.setDaemon(true);
            return t;
        };

        this.executor = new ThreadPoolExecutor(
            poolSize,
            poolSize,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            threadFactory
        );
    }

    public Future<LinkPreview> submitLinkPreview(String rawUrl) {
        return executor.submit(new LinkPreviewCallable(rawUrl));
    }

    public Future<List<SharedFileRecord>> submitFileSearch(List<SharedFileRecord> snapshot, String query, String room) {
        return executor.submit(new FileSearchCallable(snapshot, query, room));
    }

    public <T> Future<T> submit(Callable<T> callable) {
        return executor.submit(callable);
    }

    public void execute(Runnable runnable) {
        executor.execute(runnable);
    }

    public int getActiveCount() {
        return executor.getActiveCount();
    }

    public int getPoolSize() {
        return executor.getPoolSize();
    }

    public int getCorePoolSize() {
        return executor.getCorePoolSize();
    }

    public long getCompletedTaskCount() {
        return executor.getCompletedTaskCount();
    }

    public long getTaskCount() {
        return executor.getTaskCount();
    }

    public int getQueueSize() {
        return executor.getQueue().size();
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
