package org.playerinvbackup.backup.command.support;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.playerinvbackup.backup.command.CommandContext;
import org.playerinvbackup.backup.text.Lang;

/**
 * 命令异步模板
 *
 * <p>统一异步执行, 日志输出, 异常捕获和回 sender 线程
 */
public final class CommandAsync {
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public record LogSpec(String key, TagResolver[] placeholders) {
        public static LogSpec of(String key, TagResolver... placeholders) {
            return new LogSpec(key, placeholders);
        }
    }

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Lang lang;

    public CommandAsync(JavaPlugin plugin, Logger logger, Lang lang) {
        this.plugin = plugin;
        this.logger = logger;
        this.lang = lang;
    }

    public <T> void supply(
            CommandContext ctx,
            LogSpec logSpec,
            ThrowingSupplier<T> supplier,
            Consumer<T> success,
            Consumer<Exception> failure
    ) {
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            try {
                T value = supplier.get();
                runOnSender(ctx.sender(), () -> success.accept(value));
            } catch (Exception e) {
                logFailure(logSpec, e);
                runOnSender(ctx.sender(), () -> failure.accept(e));
            }
        });
    }

    public void run(
            CommandContext ctx,
            LogSpec logSpec,
            ThrowingRunnable runnable,
            Runnable success,
            Consumer<Exception> failure
    ) {
        supply(ctx, logSpec, () -> {
            runnable.run();
            return Boolean.TRUE;
        }, ignored -> success.run(), failure);
    }

    public void runOnSender(CommandSender sender, Runnable runnable) {
        if (sender instanceof Player player) {
            if (!player.isOnline()) {
                return;
            }
            player.getScheduler().run(plugin, ignored -> runnable.run(), null);
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
    }

    private void logFailure(LogSpec logSpec, Exception e) {
        if (logSpec == null || logSpec.key() == null || logSpec.key().isBlank()) {
            return;
        }
        TagResolver[] placeholders = logSpec.placeholders();
        if (placeholders == null || placeholders.length == 0) {
            logger.log(Level.WARNING, lang.plain(logSpec.key()), e);
            return;
        }
        logger.log(Level.WARNING, lang.plain(logSpec.key(), placeholders), e);
    }
}
