package org.playerinvbackup.backup.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.playerinvbackup.backup.store.SqlTableNames;

/**
 * 该测试文件用于验证抽象连接池存储与 Hikari 数据源之间的参数传递
 * 重点覆盖连接数 超时和可选阈值等连接池配置
 * 确保解析后的设置能够按预期生效
 */
class AbstractPooledJdbcBackupStoreTest {
    @Test
    // 验证 Hikari 数据源初始化后
    // 常规连接池参数会完整采用传入的配置值
    void hikariDataSourceUsesConfiguredPoolSettings() throws Exception {
        JdbcPoolSettings settings = new JdbcPoolSettings(
                4,
                1,
                5_000L,
                3_000L,
                300_000L,
                1_800_000L,
                10_000L
        );
        TestPooledStore store = new TestPooledStore(settings);

        try {
            store.init();
            HikariDataSource dataSource = dataSourceOf(store);

            assertEquals(4, dataSource.getMaximumPoolSize());
            assertEquals(1, dataSource.getMinimumIdle());
            assertEquals(5_000L, dataSource.getConnectionTimeout());
            assertEquals(3_000L, dataSource.getValidationTimeout());
            assertEquals(300_000L, dataSource.getIdleTimeout());
            assertEquals(1_800_000L, dataSource.getMaxLifetime());
            assertEquals(10_000L, dataSource.getLeakDetectionThreshold());
            assertTrue(dataSource.isAutoCommit());
        } finally {
            store.close();
        }
    }

    @Test
    // 验证允许为 0 的可选阈值参数
    // 不会在传入 Hikari 后被强制改写成非零值
    void hikariDataSourceKeepsZeroThresholdsDisabled() throws Exception {
        JdbcPoolSettings settings = new JdbcPoolSettings(
                2,
                0,
                4_000L,
                3_000L,
                0L,
                0L,
                0L
        );
        TestPooledStore store = new TestPooledStore(settings);

        try {
            store.init();
            HikariDataSource dataSource = dataSourceOf(store);

            assertEquals(0, dataSource.getMinimumIdle());
            assertEquals(0L, dataSource.getIdleTimeout());
            assertEquals(0L, dataSource.getMaxLifetime());
            assertEquals(0L, dataSource.getLeakDetectionThreshold());
        } finally {
            store.close();
        }
    }

    @Test
    // 验证单连接连接池配置
    // 可以被正常传递给 Hikari 并保持最小池大小一致
    void hikariDataSourceSupportsSingleConnectionPool() throws Exception {
        JdbcPoolSettings settings = new JdbcPoolSettings(
                1,
                1,
                5_000L,
                3_000L,
                300_000L,
                1_800_000L,
                0L
        );
        TestPooledStore store = new TestPooledStore(settings);

        try {
            store.init();
            HikariDataSource dataSource = dataSourceOf(store);

            assertEquals(1, dataSource.getMaximumPoolSize());
            assertEquals(1, dataSource.getMinimumIdle());
        } finally {
            store.close();
        }
    }

    private static HikariDataSource dataSourceOf(AbstractPooledJdbcBackupStore store) throws Exception {
        Field field = AbstractPooledJdbcBackupStore.class.getDeclaredField("dataSource");
        field.setAccessible(true);
        return (HikariDataSource) field.get(store);
    }

    private static final class TestPooledStore extends AbstractPooledJdbcBackupStore {
        private TestPooledStore(JdbcPoolSettings poolSettings) {
            super(
                    new H2Dialect(),
                    new SqlTableNames("testp_"),
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                    "sa",
                    "",
                    "PlayerInvBackup-Test",
                    poolSettings
            );
        }
    }
}
