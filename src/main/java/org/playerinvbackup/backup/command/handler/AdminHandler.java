package org.playerinvbackup.backup.command.handler;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.command.CommandContext;
import org.playerinvbackup.backup.command.SubcommandHandler;
import org.playerinvbackup.backup.config.SoundEffect;
import org.playerinvbackup.backup.command.support.CommandGuards;
import org.playerinvbackup.backup.command.support.CommandSuggestions;
import org.playerinvbackup.backup.text.Chat;
import org.playerinvbackup.backup.text.Lang;
import org.bukkit.entity.Player;

/**
 * 管理类命令处理器
 *
 * <p>当前负责 help 和 reload
 */
public final class AdminHandler implements SubcommandHandler {
    private record AuthorLink(String name, String url) {
    }

    private static final List<AuthorLink> AUTHORS = List.of(
            new AuthorLink("Konsheng", "https://github.com/konsheng"),
            new AuthorLink("", "")
    );
    private static final String MODRINTH_URL = "";
    private static final String SPIGOTMC_URL = "";
    private static final String PAPERMC_URL = "";
    private static final String BUILTBYBIT_URL = "";
    private static final String GITHUB_URL = "https://github.com/konsheng/PlayerInvBackup";
    private static final String AUTHOR_HOVER_KEY = "info.plugin-info-author-hover";
    private static final String DOWNLOAD_LINK_HOVER_KEY = "info.plugin-info-link-hover-download";
    private static final String SUPPORT_LINK_HOVER_KEY = "info.plugin-info-link-hover-support";

    @FunctionalInterface
    public interface ReloadAction {
        void reload() throws Exception;
    }

    private final Supplier<String> pluginNameSupplier;
    private final Supplier<String> versionSupplier;
    private final Supplier<SoundEffect> helpCommandClickSoundSupplier;
    private final ReloadAction reloadAction;
    private final BooleanSupplier pluginEnabled;
    private final BooleanSupplier storeReady;
    private final IntSupplier reloadCancelledBackupTargetsSupplier;
    private final IntSupplier reloadDiscardedIoTasksSupplier;
    private final CommandGuards guards;
    private final CommandSuggestions suggestions;
    private final Logger logger;
    private final Lang lang;

    public AdminHandler(
            Supplier<String> pluginNameSupplier,
            Supplier<String> versionSupplier,
            Supplier<SoundEffect> helpCommandClickSoundSupplier,
            ReloadAction reloadAction,
            BooleanSupplier pluginEnabled,
            BooleanSupplier storeReady,
            IntSupplier reloadCancelledBackupTargetsSupplier,
            IntSupplier reloadDiscardedIoTasksSupplier,
            CommandGuards guards,
            CommandSuggestions suggestions,
            Logger logger,
            Lang lang
    ) {
        this.pluginNameSupplier = pluginNameSupplier;
        this.versionSupplier = versionSupplier;
        this.helpCommandClickSoundSupplier = helpCommandClickSoundSupplier;
        this.reloadAction = reloadAction;
        this.pluginEnabled = pluginEnabled;
        this.storeReady = storeReady;
        this.reloadCancelledBackupTargetsSupplier = reloadCancelledBackupTargetsSupplier;
        this.reloadDiscardedIoTasksSupplier = reloadDiscardedIoTasksSupplier;
        this.guards = guards;
        this.suggestions = suggestions;
        this.logger = logger;
        this.lang = lang;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public List<String> aliases() {
        return List.of("tips", "reload", "helpclick");
    }

    @Override
    public boolean execute(CommandContext ctx) {
        return switch (ctx.subcommand().toLowerCase(Locale.ROOT)) {
            case "help" -> executeHelp(ctx);
            case "helpclick" -> executeHelpClick(ctx);
            case "tips" -> executeTips(ctx);
            case "reload" -> executeReload(ctx);
            default -> false;
        };
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        return List.of();
    }

    @Override
    public boolean isVisible(CommandContext ctx, String token) {
        if ("helpclick".equalsIgnoreCase(token)) {
            return false;
        }
        String permission = "reload".equalsIgnoreCase(token) ? Permissions.RELOAD : Permissions.ADMIN;
        return org.playerinvbackup.backup.Permissions.has(ctx.sender(), permission);
    }

    private boolean executeHelp(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.ADMIN)) {
            return true;
        }
        sendHelpContents(ctx);
        return true;
    }

    private boolean executeHelpClick(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.ADMIN)) {
            return true;
        }
        playHelpCommandClickSound(ctx);
        sendHelpContents(ctx);
        return true;
    }

    public boolean sendHelpDirect(CommandContext ctx) {
        sendHelpContents(ctx);
        return true;
    }

    public boolean sendPluginInfoDirect(CommandContext ctx) {
        sendPluginInfoContents(ctx);
        return true;
    }

    public boolean sendHelpHintDirect(CommandContext ctx) {
        Chat.info(
                ctx.sender(),
                "info.help-hint",
                Placeholder.component("help_command", createHelpCommand(ctx.label()))
        );
        return true;
    }

    private void sendHelpContents(CommandContext ctx) {
        Chat.plain(ctx.sender(), "help.header", Placeholder.unparsed("version", versionSupplier.get()));
        Chat.plainList(ctx.sender(), "help.lines", Placeholder.unparsed("label", ctx.label()));
        Chat.plainList(ctx.sender(), "help.commands", Placeholder.unparsed("label", ctx.label()));
        Chat.plain(ctx.sender(), "help.example", Placeholder.unparsed("label", ctx.label()));
    }

    private void sendPluginInfoContents(CommandContext ctx) {
        Chat.plain(
                ctx.sender(),
                "info.plugin-info-header",
                Placeholder.unparsed("name", pluginNameSupplier.get()),
                Placeholder.unparsed("version", versionSupplier.get())
        );
        if (hasAuthorLink()) {
            Chat.plain(
                    ctx.sender(),
                    "info.plugin-info-author",
                    Placeholder.component("author", createAuthorLinks())
            );
        }
        if (hasDownloadLinks()) {
            Chat.plain(
                    ctx.sender(),
                    "info.plugin-info-download",
                    Placeholder.component("download_links", createDownloadLinks())
            );
        }
        if (hasSupportLinks()) {
            Chat.plain(
                    ctx.sender(),
                    "info.plugin-info-support",
                    Placeholder.component("support_links", createSupportLinks())
            );
        }
        Chat.plain(
                ctx.sender(),
                "info.plugin-info-help",
                Placeholder.component("help_command", createHelpCommand(ctx.label()))
        );
    }

    private boolean executeTips(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.ADMIN)) {
            return true;
        }
        Chat.plain(ctx.sender(), "tips.header", Placeholder.unparsed("version", versionSupplier.get()));
        Chat.plainList(ctx.sender(), "tips.lines", Placeholder.unparsed("label", ctx.label()));
        return true;
    }

    private boolean executeReload(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.RELOAD)) {
            return true;
        }

        long startedAtNanos = System.nanoTime();
        boolean reloaded = false;
        try {
            reloadAction.reload();
            reloaded = true;
        } catch (Exception e) {
            logger.log(
                    Level.WARNING,
                    lang.plain(
                            "console.command.reload-failed",
                            Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                    ),
                    e
            );
        }

        if (!reloaded || !pluginEnabled.getAsBoolean() || !storeReady.getAsBoolean()) {
            Chat.error(ctx.sender(), "errors.reload-failed");
            return true;
        }
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        Chat.success(
                ctx.sender(),
                "success.reloaded",
                Placeholder.unparsed("elapsed", formatElapsed(elapsedMillis))
        );
        int cancelledTargets = reloadCancelledBackupTargetsSupplier.getAsInt();
        int discardedIoTasks = reloadDiscardedIoTasksSupplier.getAsInt();
        if (cancelledTargets > 0 || discardedIoTasks > 0) {
            Chat.info(
                    ctx.sender(),
                    "info.reload-backup-discarded",
                    Placeholder.unparsed("queue_targets", String.valueOf(cancelledTargets)),
                    Placeholder.unparsed("io_tasks", String.valueOf(discardedIoTasks))
            );
        }
        return true;
    }

    private static String formatElapsed(long elapsedMillis) {
        long safeMillis = Math.max(0L, elapsedMillis);
        long totalSeconds = safeMillis / 1000L;
        long millisPart = safeMillis % 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0L) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        }
        if (minutes > 0L) {
            return String.format("%dm %ds", minutes, seconds);
        }
        if (seconds > 0L) {
            return String.format("%d.%03ds", seconds, millisPart);
        }
        return safeMillis + "ms";
    }

    private Component createDownloadLinks() {
        return joinLinks(
                createLink("Modrinth", MODRINTH_URL, DOWNLOAD_LINK_HOVER_KEY),
                createLink("SpigotMC", SPIGOTMC_URL, DOWNLOAD_LINK_HOVER_KEY),
                createLink("PaperMC", PAPERMC_URL, DOWNLOAD_LINK_HOVER_KEY),
                createLink("BuiltByBit", BUILTBYBIT_URL, DOWNLOAD_LINK_HOVER_KEY)
        );
    }

    private Component createSupportLinks() {
        return createLink("GitHub", GITHUB_URL, SUPPORT_LINK_HOVER_KEY);
    }

    private Component createAuthorLinks() {
        return joinComponents(
                ", ",
                AUTHORS.stream()
                        .map(author -> createLink(author.name(), author.url(), AUTHOR_HOVER_KEY))
                        .toArray(Component[]::new)
        );
    }

    private static Component joinLinks(Component... links) {
        return joinComponents(" | ", links);
    }

    private static Component joinComponents(String separator, Component... components) {
        Component joined = Component.empty();
        boolean first = true;
        for (Component component : components) {
            if (component == null) {
                continue;
            }
            if (!first) {
                joined = joined.append(Component.text(separator).color(TextColor.color(0x78909C)));
            }
            joined = joined.append(component);
            first = false;
        }
        return joined;
    }

    private Component createLink(String label, String url, String hoverKey) {
        if (!hasText(label) || !hasText(url)) {
            return null;
        }
        return Component.text(label)
                .color(TextColor.color(0x4FC3F7))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(lang.msg(hoverKey)));
    }

    private Component createHelpCommand(String label) {
        String safeLabel = hasText(label) ? label : "playerinvbackup";
        String displayCommand = "/" + safeLabel + " help";
        String clickCommand = "/" + safeLabel + " helpclick";
        return Component.text(displayCommand)
                .color(TextColor.color(0x4FC3F7))
                .clickEvent(ClickEvent.runCommand(clickCommand))
                .hoverEvent(HoverEvent.showText(lang.msg("info.plugin-info-help-hover")));
    }

    private void playHelpCommandClickSound(CommandContext ctx) {
        Player player = ctx.senderAsPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }
        SoundEffect effect = helpCommandClickSoundSupplier.get();
        if (effect == null || !effect.enabled()) {
            return;
        }
        player.getScheduler().run(
                ctx.plugin(),
                ignored -> player.playSound(player.getLocation(), effect.sound(), effect.volume(), effect.pitch()),
                null
        );
    }

    private static boolean hasDownloadLinks() {
        return hasText(MODRINTH_URL) || hasText(SPIGOTMC_URL) || hasText(PAPERMC_URL) || hasText(BUILTBYBIT_URL);
    }

    private static boolean hasAuthorLink() {
        return AUTHORS.stream().anyMatch(author -> hasText(author.name()) && hasText(author.url()));
    }

    private static boolean hasSupportLinks() {
        return hasText(GITHUB_URL);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
