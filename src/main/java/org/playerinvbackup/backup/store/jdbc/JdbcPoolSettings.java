package org.playerinvbackup.backup.store.jdbc;

import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.playerinvbackup.backup.text.Lang;

/**
 * JDBC 连接池配置
 *
 * <p>用于从配置文件读取 MySQL / PostgreSQL 的 HikariCP 参数
 */
public record JdbcPoolSettings(
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeoutMs,
        long validationTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs,
        long leakDetectionThresholdMs
) {
    private static final int DEFAULT_MAXIMUM_POOL_SIZE = 4;
    private static final int DEFAULT_MINIMUM_IDLE = 1;
    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 5_000L;
    private static final long DEFAULT_VALIDATION_TIMEOUT_MS = 3_000L;
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 300_000L;
    private static final long DEFAULT_MAX_LIFETIME_MS = 1_800_000L;
    private static final long DEFAULT_LEAK_DETECTION_THRESHOLD_MS = 0L;

    private static final int MINIMUM_POOL_SIZE = 1;
    private static final int MAXIMUM_POOL_SIZE_LIMIT = 32;
    private static final int MINIMUM_IDLE_LIMIT = 0;
    private static final long MINIMUM_CONNECTION_TIMEOUT_MS = 250L;
    private static final long MAXIMUM_CONNECTION_TIMEOUT_MS = 60_000L;
    private static final long MINIMUM_VALIDATION_TIMEOUT_MS = 250L;
    private static final long MINIMUM_IDLE_TIMEOUT_MS = 10_000L;
    private static final long MINIMUM_MAX_LIFETIME_MS = 30_000L;
    private static final long MINIMUM_LEAK_DETECTION_THRESHOLD_MS = 2_000L;

    public static JdbcPoolSettings defaults() {
        return new JdbcPoolSettings(
                DEFAULT_MAXIMUM_POOL_SIZE,
                DEFAULT_MINIMUM_IDLE,
                DEFAULT_CONNECTION_TIMEOUT_MS,
                DEFAULT_VALIDATION_TIMEOUT_MS,
                DEFAULT_IDLE_TIMEOUT_MS,
                DEFAULT_MAX_LIFETIME_MS,
                DEFAULT_LEAK_DETECTION_THRESHOLD_MS
        );
    }

    public static JdbcPoolSettings fromConfig(ConfigurationSection config, String basePath, Logger logger, Lang lang) {
        JdbcPoolSettings defaults = defaults();
        String root = normalizeBasePath(basePath);

        int maximumPoolSize = config == null
                ? defaults.maximumPoolSize()
                : config.getInt(root + ".maximum-pool-size", defaults.maximumPoolSize());
        int minimumIdle = config == null
                ? defaults.minimumIdle()
                : config.getInt(root + ".minimum-idle", defaults.minimumIdle());
        long connectionTimeoutMs = config == null
                ? defaults.connectionTimeoutMs()
                : config.getLong(root + ".connection-timeout-ms", defaults.connectionTimeoutMs());
        long validationTimeoutMs = config == null
                ? defaults.validationTimeoutMs()
                : config.getLong(root + ".validation-timeout-ms", defaults.validationTimeoutMs());
        long idleTimeoutMs = config == null
                ? defaults.idleTimeoutMs()
                : config.getLong(root + ".idle-timeout-ms", defaults.idleTimeoutMs());
        long maxLifetimeMs = config == null
                ? defaults.maxLifetimeMs()
                : config.getLong(root + ".max-lifetime-ms", defaults.maxLifetimeMs());
        long leakDetectionThresholdMs = config == null
                ? defaults.leakDetectionThresholdMs()
                : config.getLong(root + ".leak-detection-threshold-ms", defaults.leakDetectionThresholdMs());

        if (maximumPoolSize < MINIMUM_POOL_SIZE) {
            maximumPoolSize = MINIMUM_POOL_SIZE;
            warnMin(logger, lang, root + ".maximum-pool-size", MINIMUM_POOL_SIZE, maximumPoolSize);
        } else if (maximumPoolSize > MAXIMUM_POOL_SIZE_LIMIT) {
            maximumPoolSize = MAXIMUM_POOL_SIZE_LIMIT;
            warnMax(logger, lang, root + ".maximum-pool-size", MAXIMUM_POOL_SIZE_LIMIT, maximumPoolSize);
        }

        if (minimumIdle < MINIMUM_IDLE_LIMIT) {
            minimumIdle = MINIMUM_IDLE_LIMIT;
            warnMin(logger, lang, root + ".minimum-idle", MINIMUM_IDLE_LIMIT, minimumIdle);
        }
        if (minimumIdle > maximumPoolSize) {
            minimumIdle = maximumPoolSize;
            warnExceedsOther(logger, lang, root + ".minimum-idle", "maximum-pool-size", minimumIdle);
        }

        if (connectionTimeoutMs < MINIMUM_CONNECTION_TIMEOUT_MS) {
            connectionTimeoutMs = MINIMUM_CONNECTION_TIMEOUT_MS;
            warnMin(logger, lang, root + ".connection-timeout-ms", MINIMUM_CONNECTION_TIMEOUT_MS, connectionTimeoutMs);
        } else if (connectionTimeoutMs > MAXIMUM_CONNECTION_TIMEOUT_MS) {
            connectionTimeoutMs = MAXIMUM_CONNECTION_TIMEOUT_MS;
            warnMax(logger, lang, root + ".connection-timeout-ms", MAXIMUM_CONNECTION_TIMEOUT_MS, connectionTimeoutMs);
        }

        if (validationTimeoutMs < MINIMUM_VALIDATION_TIMEOUT_MS) {
            validationTimeoutMs = MINIMUM_VALIDATION_TIMEOUT_MS;
            warnMin(logger, lang, root + ".validation-timeout-ms", MINIMUM_VALIDATION_TIMEOUT_MS, validationTimeoutMs);
        }
        if (validationTimeoutMs >= connectionTimeoutMs && connectionTimeoutMs <= MINIMUM_VALIDATION_TIMEOUT_MS) {
            connectionTimeoutMs = MINIMUM_VALIDATION_TIMEOUT_MS + 1L;
            warnGreaterThanOther(
                    logger,
                    lang,
                    root + ".connection-timeout-ms",
                    "validation-timeout-ms",
                    connectionTimeoutMs
            );
        }
        if (validationTimeoutMs >= connectionTimeoutMs) {
            validationTimeoutMs = Math.max(MINIMUM_VALIDATION_TIMEOUT_MS, connectionTimeoutMs - 1L);
            warnLessThanOther(
                    logger,
                    lang,
                    root + ".validation-timeout-ms",
                    "connection-timeout-ms",
                    validationTimeoutMs
            );
        }

        if (idleTimeoutMs < 0L) {
            idleTimeoutMs = 0L;
            warnMin(logger, lang, root + ".idle-timeout-ms", 0L, idleTimeoutMs);
        } else if (idleTimeoutMs > 0L && idleTimeoutMs < MINIMUM_IDLE_TIMEOUT_MS) {
            idleTimeoutMs = MINIMUM_IDLE_TIMEOUT_MS;
            warnMin(logger, lang, root + ".idle-timeout-ms", MINIMUM_IDLE_TIMEOUT_MS, idleTimeoutMs);
        }

        if (maxLifetimeMs < 0L) {
            maxLifetimeMs = 0L;
            warnMin(logger, lang, root + ".max-lifetime-ms", 0L, maxLifetimeMs);
        } else if (maxLifetimeMs > 0L && maxLifetimeMs < MINIMUM_MAX_LIFETIME_MS) {
            maxLifetimeMs = MINIMUM_MAX_LIFETIME_MS;
            warnMin(logger, lang, root + ".max-lifetime-ms", MINIMUM_MAX_LIFETIME_MS, maxLifetimeMs);
        }

        if (leakDetectionThresholdMs < 0L) {
            leakDetectionThresholdMs = 0L;
            warnMin(logger, lang, root + ".leak-detection-threshold-ms", 0L, leakDetectionThresholdMs);
        } else if (leakDetectionThresholdMs > 0L && leakDetectionThresholdMs < MINIMUM_LEAK_DETECTION_THRESHOLD_MS) {
            leakDetectionThresholdMs = MINIMUM_LEAK_DETECTION_THRESHOLD_MS;
            warnMin(
                    logger,
                    lang,
                    root + ".leak-detection-threshold-ms",
                    MINIMUM_LEAK_DETECTION_THRESHOLD_MS,
                    leakDetectionThresholdMs
            );
        }

        return new JdbcPoolSettings(
                maximumPoolSize,
                minimumIdle,
                connectionTimeoutMs,
                validationTimeoutMs,
                idleTimeoutMs,
                maxLifetimeMs,
                leakDetectionThresholdMs
        );
    }

    private static String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return "storage.mysql.pool";
        }
        return basePath.endsWith(".") ? basePath.substring(0, basePath.length() - 1) : basePath;
    }

    private static void warnMin(Logger logger, Lang lang, String path, long limit, long fixed) {
        warn(
                logger,
                lang,
                "console.config.jdbc-pool.min-corrected",
                Placeholder.unparsed("path", path),
                Placeholder.unparsed("limit", String.valueOf(limit)),
                Placeholder.unparsed("fixed", String.valueOf(fixed))
        );
    }

    private static void warnMax(Logger logger, Lang lang, String path, long limit, long fixed) {
        warn(
                logger,
                lang,
                "console.config.jdbc-pool.max-corrected",
                Placeholder.unparsed("path", path),
                Placeholder.unparsed("limit", String.valueOf(limit)),
                Placeholder.unparsed("fixed", String.valueOf(fixed))
        );
    }

    private static void warnExceedsOther(Logger logger, Lang lang, String path, String other, long fixed) {
        warn(
                logger,
                lang,
                "console.config.jdbc-pool.exceeds-other-corrected",
                Placeholder.unparsed("path", path),
                Placeholder.unparsed("other", other),
                Placeholder.unparsed("fixed", String.valueOf(fixed))
        );
    }

    private static void warnGreaterThanOther(Logger logger, Lang lang, String path, String other, long fixed) {
        warn(
                logger,
                lang,
                "console.config.jdbc-pool.greater-than-other-corrected",
                Placeholder.unparsed("path", path),
                Placeholder.unparsed("other", other),
                Placeholder.unparsed("fixed", String.valueOf(fixed))
        );
    }

    private static void warnLessThanOther(Logger logger, Lang lang, String path, String other, long fixed) {
        warn(
                logger,
                lang,
                "console.config.jdbc-pool.less-than-other-corrected",
                Placeholder.unparsed("path", path),
                Placeholder.unparsed("other", other),
                Placeholder.unparsed("fixed", String.valueOf(fixed))
        );
    }

    private static void warn(Logger logger, Lang lang, String key, TagResolver... placeholders) {
        if (logger == null || lang == null || key == null || key.isBlank()) {
            return;
        }
        logger.warning(lang.plain(key, placeholders));
    }
}
