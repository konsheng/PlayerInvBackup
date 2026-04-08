package org.playerinvbackup.backup.app;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * I/O 写入调度器
 *
 * <p>特点:
 * 1) 单线程顺序写入, 降低存储并发复杂度
 * 2) 队列满时直接拒绝, 避免无限堆积导致内存风险
 * 3) 通过 {@link WriteRateLimiter} 对写入速率做软限制
 */
public final class IoDispatcher implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final WriteRateLimiter writeRateLimiter;
    private final int queueLimit;

    public IoDispatcher(int queueLimit, double maxWritesPerSecond, String threadNamePrefix) {
        this.queueLimit = queueLimit;
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueLimit),
                new NamedThreadFactory(threadNamePrefix),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.writeRateLimiter = new WriteRateLimiter(maxWritesPerSecond);
    }

    public int queueLimit() {
        return queueLimit;
    }

    public int queueSize() {
        return executor.getQueue().size();
    }

    public int queueRemainingCapacity() {
        return executor.getQueue().remainingCapacity();
    }

    public boolean hasCapacity() {
        return executor.getQueue().remainingCapacity() > 0;
    }

    public boolean submitWrite(Runnable runnable) {
        try {
            executor.execute(() -> {
                writeRateLimiter.acquire();
                runnable.run();
            });
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger idx = new AtomicInteger();
        private final String prefix;

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName(prefix + "-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
