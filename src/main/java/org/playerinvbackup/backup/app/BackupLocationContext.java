package org.playerinvbackup.backup.app;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * 备份位置上下文
 *
 * <p>用于在抓取快照时同步保留来源位置与目标位置, 避免异步阶段再访问 Bukkit Location
 */
public record BackupLocationContext(
        String worldName,
        Double locationX,
        Double locationY,
        Double locationZ,
        String targetWorldName,
        Double targetLocationX,
        Double targetLocationY,
        Double targetLocationZ,
        UUID killerPlayerUuid,
        String killerPlayerName
) {
    public static BackupLocationContext fromCurrentLocation(Location location) {
        return fromTransition(location, null);
    }

    public static BackupLocationContext fromDeath(Location location, Player killer) {
        return new BackupLocationContext(
                worldName(location),
                coordinateX(location),
                coordinateY(location),
                coordinateZ(location),
                null,
                null,
                null,
                null,
                killer == null ? null : killer.getUniqueId(),
                killer == null ? null : killer.getName()
        );
    }

    public static BackupLocationContext fromTransition(Location from, Location to) {
        return new BackupLocationContext(
                worldName(from),
                coordinateX(from),
                coordinateY(from),
                coordinateZ(from),
                worldName(to),
                coordinateX(to),
                coordinateY(to),
                coordinateZ(to),
                null,
                null
        );
    }

    private static String worldName(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return location.getWorld().getName();
    }

    private static Double coordinateX(Location location) {
        return location == null ? null : location.getX();
    }

    private static Double coordinateY(Location location) {
        return location == null ? null : location.getY();
    }

    private static Double coordinateZ(Location location) {
        return location == null ? null : location.getZ();
    }
}
