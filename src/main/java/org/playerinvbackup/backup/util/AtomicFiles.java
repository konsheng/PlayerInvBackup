package org.playerinvbackup.backup.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 原子写文件工具
 *
 * <p>通过临时文件 + move 的方式, 尽量避免写入过程中断导致目标文件损坏
 */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void writeBytesAtomic(Path target, byte[] bytes) throws IOException {
        writeBytesAtomic(target, bytes, true);
    }

    public static void writeBytesAtomic(Path target, byte[] bytes, boolean replaceExisting) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            moveAtomic(tmp, target, replaceExisting);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void writeStringAtomic(Path target, String content, Charset charset, boolean replaceExisting)
            throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, charset, StandardOpenOption.TRUNCATE_EXISTING);
            moveAtomic(tmp, target, replaceExisting);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void moveAtomic(Path source, Path target, boolean replaceExisting) throws IOException {
        try {
            if (replaceExisting) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (IOException e) {
            if (replaceExisting) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }
}
