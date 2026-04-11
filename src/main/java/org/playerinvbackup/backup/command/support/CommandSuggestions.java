package org.playerinvbackup.backup.command.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 命令补全辅助
 *
 * <p>集中处理前缀匹配和在线玩家补全
 */
public final class CommandSuggestions {
    public List<String> filterPrefix(String token, Collection<String> candidates) {
        String lower = token == null ? "" : token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (lower.isEmpty() || candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(candidate);
            }
        }
        return out;
    }

    public List<String> onlinePlayers(String token) {
        return filterPrefix(token, Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
    }
}
