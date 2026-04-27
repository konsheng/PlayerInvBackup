package org.playerinvbackup.backup.store.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import org.playerinvbackup.backup.store.SqlTableNames;

/**
 * 远程 JDBC 存储基类
 *
 * <p>MySQL 与 PostgreSQL 通过连接池获取连接, 不再长期持有单连接
 */
public abstract class AbstractPooledJdbcBackupStore extends AbstractJdbcBackupStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String poolName;
    private final JdbcPoolSettings poolSettings;
    private HikariDataSource dataSource;

    protected AbstractPooledJdbcBackupStore(
            JdbcDialect dialect,
            SqlTableNames tables,
            String jdbcUrl,
            String username,
            String password,
            String poolName,
            JdbcPoolSettings poolSettings
    ) {
        super(dialect, tables);
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.poolName = poolName;
        this.poolSettings = poolSettings == null ? JdbcPoolSettings.defaults() : poolSettings;
    }

    @Override
    protected final boolean useSharedConnection() {
        return false;
    }

    @Override
    protected void beforeConnect() {
        closeQuietly(dataSource);
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setDriverClassName(dialect().driverClassName());
        config.setJdbcUrl(jdbcUrl);
        if (hasText(username)) {
            config.setUsername(username);
        }
        if (hasText(password)) {
            config.setPassword(password);
        }
        config.setMaximumPoolSize(poolSettings.maximumPoolSize());
        config.setMinimumIdle(poolSettings.minimumIdle());
        config.setConnectionTimeout(poolSettings.connectionTimeoutMs());
        config.setValidationTimeout(poolSettings.validationTimeoutMs());
        config.setIdleTimeout(poolSettings.idleTimeoutMs());
        config.setMaxLifetime(poolSettings.maxLifetimeMs());
        if (poolSettings.leakDetectionThresholdMs() > 0L) {
            config.setLeakDetectionThreshold(poolSettings.leakDetectionThresholdMs());
        }
        config.setAutoCommit(true);
        this.dataSource = new HikariDataSource(config);
    }

    @Override
    protected final Connection openConnection() throws Exception {
        if (dataSource == null) {
            throw new IllegalStateException("JDBC connection pool is not initialized");
        }
        return dataSource.getConnection();
    }

    @Override
    protected void closeResources() {
        closeQuietly(dataSource);
        dataSource = null;
    }

    private static void closeQuietly(HikariDataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        try {
            dataSource.close();
        } catch (Exception ignored) {
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
