package org.playerinvbackup.backup.command;

import java.util.Arrays;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;

/**
 * 命令执行上下文
 *
 * <p>统一封装 plugin, sender, command, label 和参数, 减少 handler 里的样板代码
 */
public final class CommandContext {
    private final PlayerInvBackupPlugin plugin;
    private final CommandSender sender;
    private final Command command;
    private final String label;
    private final String[] rawArgs;

    public CommandContext(
            PlayerInvBackupPlugin plugin,
            CommandSender sender,
            Command command,
            String label,
            String[] rawArgs
    ) {
        this.plugin = plugin;
        this.sender = sender;
        this.command = command;
        this.label = label;
        this.rawArgs = rawArgs == null ? new String[0] : rawArgs.clone();
    }

    public PlayerInvBackupPlugin plugin() {
        return plugin;
    }

    public CommandSender sender() {
        return sender;
    }

    public Command command() {
        return command;
    }

    public String label() {
        return label;
    }

    public String[] rawArgs() {
        return rawArgs.clone();
    }

    public int rawArgCount() {
        return rawArgs.length;
    }

    public boolean hasRawArg(int index) {
        return index >= 0 && index < rawArgs.length;
    }

    public String rawArg(int index) {
        return hasRawArg(index) ? rawArgs[index] : null;
    }

    public String subcommand() {
        return hasRawArg(0) ? rawArgs[0] : "";
    }

    public int argCount() {
        return Math.max(0, rawArgs.length - 1);
    }

    public boolean hasArg(int index) {
        return index >= 0 && index + 1 < rawArgs.length;
    }

    public String arg(int index) {
        return hasArg(index) ? rawArgs[index + 1] : null;
    }

    public boolean isPlayerSender() {
        return sender instanceof Player;
    }

    public Player senderAsPlayer() {
        return sender instanceof Player player ? player : null;
    }

    public String joinArgs(int startArgIndex) {
        if (!hasArg(startArgIndex)) {
            return "";
        }
        return String.join(" ", Arrays.copyOfRange(rawArgs, startArgIndex + 1, rawArgs.length)).trim();
    }

    public CommandContext withRawArgs(String... newRawArgs) {
        return new CommandContext(plugin, sender, command, label, newRawArgs);
    }
}
