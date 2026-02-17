package org.baymc.backup.audit;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.baymc.backup.BayMcBackUpPlugin;
import org.baymc.backup.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 管理员操作审计
 *
 * <p>将关键操作写入 {@code plugins/BayMcBackUp/logs/audit-YYYY-MM-DD.log}, 便于追溯:
 * 打开 GUI, 领取物品, 恢复备份, 置顶/备注等
 */
public final class AuditService {
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Object lock = new Object();
    private final BayMcBackUpPlugin plugin;
    private final Path logsDir;
    private final ZoneId zoneId;

    private volatile boolean enabled = true;
    private volatile boolean consoleEnabled = true;
    private volatile int keepDays = 30;
    private volatile LocalDate lastDate;
    private ScheduledTask maintenanceTask;

    public AuditService(BayMcBackUpPlugin plugin) {
        this.plugin = plugin;
        this.logsDir = plugin.getDataFolder().toPath().resolve("logs");
        this.zoneId = ZoneId.systemDefault();
    }

    public void reload(PluginConfig config) {
        if (config == null) {
            this.enabled = true;
            this.consoleEnabled = true;
            this.keepDays = 30;
        } else {
            this.enabled = config.auditEnabled();
            this.consoleEnabled = config.auditConsole();
            this.keepDays = Math.max(0, config.auditKeepDays());
        }
        if (enabled) {
            ensureMaintenanceTask();
            runMaintenanceAsync();
        } else {
            cancelMaintenanceTask();
        }
    }

    public void log(
            String action,
            CommandSender actor,
            UUID targetUuid,
            String targetName,
            String backupId,
            String details
    ) {
        if (!enabled) {
            return;
        }

        String actorUuid = "-";
        String actorName = "-";
        if (actor != null) {
            actorName = actor.getName();
            if (actor instanceof Player player) {
                actorUuid = player.getUniqueId().toString();
            }
        }
        String targetUuidStr = targetUuid == null ? "-" : targetUuid.toString();
        String targetNameStr = targetName == null ? "-" : targetName;
        String backupIdStr = backupId == null ? "-" : backupId;
        String detailsStr = details == null ? "" : details.replace('\n', ' ').replace('\r', ' ');

        String line = Instant.now()
                + "\t" + action
                + "\t" + actorUuid
                + "\t" + actorName
                + "\t" + targetUuidStr
                + "\t" + targetNameStr
                + "\t" + backupIdStr
                + "\t" + detailsStr
                + "\n";

        if (consoleEnabled) {
            plugin.getLogger().info(plugin.lang().plain(
                    "console.audit.line",
                    Placeholder.unparsed("action", action),
                    Placeholder.unparsed("actor", actorName),
                    Placeholder.unparsed("target", targetNameStr),
                    Placeholder.unparsed("backup_id", backupIdStr)
            ));
        }

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            synchronized (lock) {
                if (!enabled) {
                    return;
                }
                Path file = null;
                try {
                    LocalDate today = LocalDate.now(zoneId);
                    rotateIfNeeded(today);
                    file = logFile(today);

                    Files.createDirectories(logsDir);
                    Files.writeString(
                            file,
                            line,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND
                    );
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, plugin.lang().plain(
                            "console.audit.write-failed",
                            Placeholder.unparsed("file", file == null ? "-" : file.toString()),
                            Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                    ), e);
                }
            }
        });
    }

    private void rotateIfNeeded(LocalDate today) {
        LocalDate previous = lastDate;
        if (previous == null || today == null) {
            lastDate = today;
            return;
        }
        if (previous.equals(today)) {
            return;
        }

        lastDate = today;
        Path previousLog = logFile(previous);
        if (Files.exists(previousLog)) {
            try {
                compress(previousLog, gzipFile(previous));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, plugin.lang().plain(
                        "console.audit.rotate-failed",
                        Placeholder.unparsed("file", previousLog.toString()),
                        Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                ), e);
            }
        }

        try {
            maintenance(today);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, plugin.lang().plain(
                    "console.audit.maintenance-failed",
                    Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
            ), e);
        }
    }

    private void ensureMaintenanceTask() {
        if (maintenanceTask != null) {
            return;
        }
        maintenanceTask = Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                ignored -> {
                    synchronized (lock) {
                        maintenance(LocalDate.now(zoneId));
                    }
                },
                1,
                1,
                TimeUnit.HOURS
        );
    }

    private void cancelMaintenanceTask() {
        ScheduledTask task = maintenanceTask;
        if (task == null) {
            return;
        }
        task.cancel();
        maintenanceTask = null;
    }

    private void runMaintenanceAsync() {
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            synchronized (lock) {
                maintenance(LocalDate.now(zoneId));
            }
        });
    }

    private void maintenance(LocalDate today) {
        if (today == null) {
            return;
        }

        // 没有开启保留策略时也要做“按天压缩”, 避免旧文件一直是 .log
        try {
            Files.createDirectories(logsDir);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, plugin.lang().plain(
                    "console.audit.write-failed",
                    Placeholder.unparsed("file", logsDir.toString()),
                    Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
            ), e);
            return;
        }

        rotateUncompressedOldLogs(today);
        cleanupOldLogs(today);
    }

    private void rotateUncompressedOldLogs(LocalDate today) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logsDir, "audit-*.log")) {
            for (Path logFile : stream) {
                LocalDate date = parseDateFromFileName(String.valueOf(logFile.getFileName()));
                if (date == null || !date.isBefore(today)) {
                    continue;
                }
                try {
                    compress(logFile, gzipFile(date));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, plugin.lang().plain(
                            "console.audit.rotate-failed",
                            Placeholder.unparsed("file", logFile.toString()),
                            Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                    ), e);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, plugin.lang().plain(
                    "console.audit.maintenance-failed",
                    Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
            ), e);
        }
    }

    private void cleanupOldLogs(LocalDate today) {
        int days = keepDays;
        if (days <= 0) {
            return;
        }

        LocalDate cutoff = today.minusDays(days);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logsDir)) {
            for (Path file : stream) {
                String name = String.valueOf(file.getFileName());
                LocalDate date = parseDateFromAnyFileName(name);
                if (date == null || !date.isBefore(cutoff)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(file);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, plugin.lang().plain(
                            "console.audit.cleanup-failed",
                            Placeholder.unparsed("file", file.toString()),
                            Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                    ), e);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, plugin.lang().plain(
                    "console.audit.maintenance-failed",
                    Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
            ), e);
        }
    }

    private Path logFile(LocalDate date) {
        return logsDir.resolve("audit-" + FILE_DATE.format(date) + ".log");
    }

    private Path gzipFile(LocalDate date) {
        return logsDir.resolve("audit-" + FILE_DATE.format(date) + ".log.gz");
    }

    private static LocalDate parseDateFromFileName(String name) {
        if (name == null) {
            return null;
        }
        if (!name.startsWith("audit-") || !name.endsWith(".log")) {
            return null;
        }
        String datePart = name.substring("audit-".length(), name.length() - ".log".length());
        return tryParseDate(datePart);
    }

    private static LocalDate parseDateFromAnyFileName(String name) {
        if (name == null || !name.startsWith("audit-")) {
            return null;
        }
        String datePart;
        if (name.endsWith(".log.gz")) {
            datePart = name.substring("audit-".length(), name.length() - ".log.gz".length());
        } else if (name.endsWith(".log")) {
            datePart = name.substring("audit-".length(), name.length() - ".log".length());
        } else if (name.endsWith(".log.gz.tmp")) {
            datePart = name.substring("audit-".length(), name.length() - ".log.gz.tmp".length());
        } else {
            return null;
        }
        return tryParseDate(datePart);
    }

    private static LocalDate tryParseDate(String datePart) {
        if (datePart == null || datePart.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(datePart, FILE_DATE);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void compress(Path sourceLog, Path targetGzip) throws Exception {
        if (sourceLog == null || targetGzip == null) {
            return;
        }

        Path tmp = targetGzip.resolveSibling(String.valueOf(targetGzip.getFileName()) + ".tmp");
        Files.deleteIfExists(tmp);

        try (InputStream in = Files.newInputStream(sourceLog);
             OutputStream fileOut = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             GZIPOutputStream gzOut = new GZIPOutputStream(fileOut)) {
            in.transferTo(gzOut);
        }

        try {
            Files.move(tmp, targetGzip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tmp, targetGzip, StandardCopyOption.REPLACE_EXISTING);
        }

        Files.deleteIfExists(sourceLog);
    }
}
