package org.playerinvbackup.backup.runtime;

import java.nio.file.Path;
import org.playerinvbackup.backup.config.PluginConfig;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.store.SqlTableNames;
import org.playerinvbackup.backup.store.h2.H2BackupStore;
import org.playerinvbackup.backup.store.local.LocalBackupStore;
import org.playerinvbackup.backup.store.mysql.MysqlBackupStore;
import org.playerinvbackup.backup.store.postgresql.PostgresqlBackupStore;
import org.playerinvbackup.backup.store.sqlite.SqliteBackupStore;

/**
 * 根据当前配置创建备份存储实例, 避免主类继续承担存储装配细节
 *
 * <p>这里保留原有存储类型分支, 表名前缀传递方式和文件路径解析方式
 * 只是把 createStore 这类基础设施工厂逻辑从插件入口中移出
 */
public final class BackupStoreFactory {
    private final Path dataFolder;

    public BackupStoreFactory(Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    /**
     * 按配置创建存储后端
     *
     * <p>创建顺序和参数传递保持与重构前一致, 这里只负责实例化, 不负责 init
     * 初始化时机仍由上层编排器控制
     */
    public BackupStore create(PluginConfig config) {
        return switch (config.storageType()) {
            case LOCAL -> new LocalBackupStore(dataFolder.resolve(config.localBasePath()));
            case SQLITE -> new SqliteBackupStore(
                    dataFolder.resolve(config.sqliteFile()),
                    new SqlTableNames(config.sqliteTablePrefix())
            );
            case MYSQL -> new MysqlBackupStore(
                    config.mysql().jdbcUrl(),
                    config.mysql().username(),
                    config.mysql().password(),
                    new SqlTableNames(config.mysql().tablePrefix()),
                    config.mysql().poolSettings()
            );
            case POSTGRESQL -> new PostgresqlBackupStore(
                    config.postgresql().jdbcUrl(),
                    config.postgresql().username(),
                    config.postgresql().password(),
                    new SqlTableNames(config.postgresql().tablePrefix()),
                    config.postgresql().poolSettings()
            );
            case H2 -> {
                Path fileBase = dataFolder.resolve(config.h2().file());
                String jdbcUrl = config.h2().jdbcUrl(fileBase);
                yield new H2BackupStore(
                        fileBase,
                        jdbcUrl,
                        config.h2().username(),
                        config.h2().password(),
                        new SqlTableNames(config.h2().tablePrefix())
                );
            }
        };
    }
}
