package org.playerinvbackup.backup.app;

import java.util.concurrent.locks.LockSupport;

/**
 * 简单的速率限制器
 *
 * <p>按固定间隔控制 acquire() 调用频率, 适用于对磁盘写入做软限流
 */
public final class WriteRateLimiter {
    private final long minIntervalNanos;
    private volatile long nextAllowedTimeNanos;

    public WriteRateLimiter(double permitsPerSecond) {
        if (permitsPerSecond <= 0.0) {
            throw new IllegalArgumentException("permitsPerSecond 必须大于 0");
        }
        this.minIntervalNanos = (long) (1_000_000_000L / permitsPerSecond);
        this.nextAllowedTimeNanos = System.nanoTime();
    }

    public void acquire() {
        long now = System.nanoTime();
        long allowed = nextAllowedTimeNanos;
        if (now < allowed) {
            LockSupport.parkNanos(allowed - now);
            now = System.nanoTime();
        }
        nextAllowedTimeNanos = now + minIntervalNanos;
    }
}
