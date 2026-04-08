package org.playerinvbackup.backup.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 哈希工具
 *
 * <p>当前用于对快照内容计算 sha256, 以在恢复前做完整性校验
 */
public final class Hashing {
    private Hashing() {
    }

    public static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        byte[] hash = digest.digest(bytes);
        return toHex(hash);
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        int i = 0;
        for (byte b : bytes) {
            int v = b & 0xFF;
            out[i++] = toHexChar(v >>> 4);
            out[i++] = toHexChar(v & 0x0F);
        }
        return new String(out);
    }

    private static char toHexChar(int v) {
        return (char) (v < 10 ? ('0' + v) : ('a' + (v - 10)));
    }
}
