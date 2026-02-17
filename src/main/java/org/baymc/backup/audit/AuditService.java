package org.baymc.backup.audit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.baymc.backup.BayMcBackUpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 管理员操作审计
 *
 * <p>将关键操作写入 {@code plugins/BayMcBackUp/audit.log}, 便于追溯:
 * 打开 GUI, 领取物品, 恢复备份, 置顶/备注等
 */
public final class AuditService {
    private final Object lock = new Object();
    private final BayMcBackUpPlugin plugin;
    private final Path auditFile;

    public AuditService(BayMcBackUpPlugin plugin) {
        this.plugin = plugin;
        this.auditFile = plugin.getDataFolder().toPath().resolve("audit.log");
    }

    public void log(
            String action,
            CommandSender actor,
            UUID targetUuid,
            String targetName,
            String backupId,
            String details
    ) {
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

        plugin.getLogger().info(plugin.lang().plain(
                "console.audit.line",
                Placeholder.unparsed("action", action),
                Placeholder.unparsed("actor", actorName),
                Placeholder.unparsed("target", targetNameStr),
                Placeholder.unparsed("backup_id", backupIdStr)
        ));

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            synchronized (lock) {
                try {
                    Files.createDirectories(auditFile.getParent());
                    Files.writeString(
                            auditFile,
                            line,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND
                    );
                } catch (Exception e) {
                    plugin.getLogger().warning(plugin.lang().plain(
                            "console.audit.write-failed",
                            Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                    ));
                }
            }
        });
    }
}
