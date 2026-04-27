package org.playerinvbackup.backup.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.playerinvbackup.backup.config.MysqlConfig;
import org.playerinvbackup.backup.config.PostgresqlConfig;

/**
 * 该测试文件用于验证连接池配置对象的解析和修正逻辑
 * 覆盖默认值读取 配置缺失 边界修正和模板同步场景
 * 确保 MySQL 和 PostgreSQL 共用的参数处理行为稳定可用
 */
class JdbcPoolSettingsTest {
    private static final Logger LOGGER = Logger.getLogger(JdbcPoolSettingsTest.class.getName());

    @Test
    // 验证目标路径下缺少 pool 节点时
    // 会直接回退到内置默认连接池参数
    void defaultsAreReturnedWhenPoolSectionIsMissing() {
        YamlConfiguration config = new YamlConfiguration();

        JdbcPoolSettings settings = JdbcPoolSettings.fromConfig(config, "storage.mysql.pool", LOGGER, null);

        assertEquals(JdbcPoolSettings.defaults(), settings);
    }

    @Test
    // 验证 MySQL 和 PostgreSQL 配置对象在缺少 pool 节点时
    // 都会使用相同的默认连接池配置
    void mysqlAndPostgresqlConfigsUseDefaultPoolSettingsWhenPoolSectionIsMissing() {
        YamlConfiguration config = new YamlConfiguration();

        MysqlConfig mysql = MysqlConfig.from(config, LOGGER, null);
        PostgresqlConfig postgresql = PostgresqlConfig.from(config, LOGGER, null);

        assertEquals(JdbcPoolSettings.defaults(), mysql.poolSettings());
        assertEquals(JdbcPoolSettings.defaults(), postgresql.poolSettings());
    }

    @Test
    // 验证明显错误的连接池配置值
    // 会被自动修正到允许启动且满足约束的安全范围
    void invalidPoolValuesAreCorrected() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.mysql.pool.maximum-pool-size", 0);
        config.set("storage.mysql.pool.minimum-idle", 10);
        config.set("storage.mysql.pool.connection-timeout-ms", 100);
        config.set("storage.mysql.pool.validation-timeout-ms", 100);
        config.set("storage.mysql.pool.idle-timeout-ms", 1);
        config.set("storage.mysql.pool.max-lifetime-ms", 1);
        config.set("storage.mysql.pool.leak-detection-threshold-ms", 1);

        JdbcPoolSettings settings = JdbcPoolSettings.fromConfig(config, "storage.mysql.pool", LOGGER, null);

        assertEquals(1, settings.maximumPoolSize());
        assertEquals(1, settings.minimumIdle());
        assertEquals(251L, settings.connectionTimeoutMs());
        assertEquals(250L, settings.validationTimeoutMs());
        assertEquals(10_000L, settings.idleTimeoutMs());
        assertEquals(30_000L, settings.maxLifetimeMs());
        assertEquals(2_000L, settings.leakDetectionThresholdMs());
    }

    @Test
    // 验证字段上限约束和字段之间的关联约束
    // 能够在同一次解析过程中被同时修正
    void upperBoundsAndRelationsAreCorrected() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.postgresql.pool.maximum-pool-size", 99);
        config.set("storage.postgresql.pool.minimum-idle", 40);
        config.set("storage.postgresql.pool.connection-timeout-ms", 99_999L);
        config.set("storage.postgresql.pool.validation-timeout-ms", 60_000L);

        JdbcPoolSettings settings = JdbcPoolSettings.fromConfig(config, "storage.postgresql.pool", LOGGER, null);

        assertEquals(32, settings.maximumPoolSize());
        assertEquals(32, settings.minimumIdle());
        assertEquals(60_000L, settings.connectionTimeoutMs());
        assertEquals(59_999L, settings.validationTimeoutMs());
    }

    @Test
    // 验证允许设置为 0 的三个超时配置
    // 不会在解析过程中被误判并强制修正
    void zeroValuesRemainAllowedForIdleTimeoutMaxLifetimeAndLeakDetection() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.mysql.pool.idle-timeout-ms", 0L);
        config.set("storage.mysql.pool.max-lifetime-ms", 0L);
        config.set("storage.mysql.pool.leak-detection-threshold-ms", 0L);

        JdbcPoolSettings settings = JdbcPoolSettings.fromConfig(config, "storage.mysql.pool", LOGGER, null);

        assertEquals(0L, settings.idleTimeoutMs());
        assertEquals(0L, settings.maxLifetimeMs());
        assertEquals(0L, settings.leakDetectionThresholdMs());
    }

    @Test
    // 验证 validation timeout 超过 connection timeout 时
    // 会被压低到连接获取超时以下
    void validationTimeoutIsForcedBelowConnectionTimeout() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.mysql.pool.connection-timeout-ms", 5_000L);
        config.set("storage.mysql.pool.validation-timeout-ms", 6_000L);

        JdbcPoolSettings settings = JdbcPoolSettings.fromConfig(config, "storage.mysql.pool", LOGGER, null);

        assertEquals(5_000L, settings.connectionTimeoutMs());
        assertEquals(4_999L, settings.validationTimeoutMs());
    }

    @Test
    // 验证内置配置模板里的连接池默认值
    // 与代码中的默认配置保持一致
    void bundledConfigContainsExpectedPoolDefaults() {
        YamlConfiguration config = loadBundledConfig("config.yml");

        MysqlConfig mysql = MysqlConfig.from(config, LOGGER, null);
        PostgresqlConfig postgresql = PostgresqlConfig.from(config, LOGGER, null);
        JdbcPoolSettings defaults = JdbcPoolSettings.defaults();

        assertEquals(defaults, mysql.poolSettings());
        assertEquals(defaults, postgresql.poolSettings());
    }

    private static YamlConfiguration loadBundledConfig(String resourceName) {
        try (InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(
                        JdbcPoolSettingsTest.class.getClassLoader().getResourceAsStream(resourceName),
                        resourceName
                ),
                StandardCharsets.UTF_8
        )) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load resource: " + resourceName, e);
        }
    }
}
