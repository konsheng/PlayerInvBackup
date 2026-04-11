package org.playerinvbackup.backup.command.support;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.playerinvbackup.backup.command.CommandContext;
import org.playerinvbackup.backup.text.Chat;

/**
 * 目标解析器
 *
 * <p>集中处理在线玩家, 离线缓存目标和 offline-mode UUID 解析
 */
public final class TargetResolver {
    public record ResolvedTarget(UUID uuid, String name) {
    }

    public Player resolveOnlinePlayer(String token) {
        UUID uuid = tryParseUuid(token);
        if (uuid != null) {
            return Bukkit.getPlayer(uuid);
        }
        return findOnlinePlayerByName(token);
    }

    public ResolvedTarget resolveStoredTarget(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        UUID uuid = tryParseUuid(token);
        if (uuid != null) {
            Player online = Bukkit.getPlayer(uuid);
            String name = online != null ? online.getName() : uuid.toString();
            return new ResolvedTarget(uuid, name);
        }

        Player online = findOnlinePlayerByName(token);
        if (online != null) {
            return new ResolvedTarget(online.getUniqueId(), online.getName());
        }

        if (!Bukkit.getOnlineMode()) {
            UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + token.trim()).getBytes(StandardCharsets.UTF_8));
            return new ResolvedTarget(offlineUuid, token);
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(token);
        if (cached == null) {
            return null;
        }
        UUID cachedUuid = cached.getUniqueId();
        String name = cached.getName() == null ? cachedUuid.toString() : cached.getName();
        return new ResolvedTarget(cachedUuid, name);
    }

    public ResolvedTarget resolveStoredTargetOrNotify(CommandContext ctx, String token, String errorKey) {
        ResolvedTarget target = resolveStoredTarget(token);
        if (target == null) {
            Chat.error(ctx.sender(), errorKey, Placeholder.unparsed("label", ctx.label()));
        }
        return target;
    }

    private Player findOnlinePlayerByName(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Player exact = Bukkit.getPlayerExact(token);
        if (exact != null) {
            return exact;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).equals(lower)) {
                return player;
            }
        }
        return null;
    }

    private UUID tryParseUuid(String token) {
        try {
            return UUID.fromString(token);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
