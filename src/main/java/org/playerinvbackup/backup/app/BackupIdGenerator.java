package org.playerinvbackup.backup.app;

import java.security.SecureRandom;

/**
 * 备份编号生成器
 *
 * <p>格式: {@code <epochMillis>-<random>}，方便按时间排序, 同时避免同一毫秒冲突
 */
public final class BackupIdGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHANUM = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private BackupIdGenerator() {
    }

    public static String newId(long epochMillis) {
        return epochMillis + "-" + randomSuffix(8);
    }

    private static String randomSuffix(int len) {
        char[] out = new char[len];
        for (int i = 0; i < len; i++) {
            out[i] = ALPHANUM[RANDOM.nextInt(ALPHANUM.length)];
        }
        return new String(out);
    }
}
