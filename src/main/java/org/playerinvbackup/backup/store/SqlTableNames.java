package org.playerinvbackup.backup.store;

/**
 * SQL 存储后端使用的表名与索引名
 *
 * <p>前缀为空时保持默认表名, 非空时直接拼接到默认名称前面
 */
public record SqlTableNames(String prefix) {
    public SqlTableNames {
        prefix = normalize(prefix);
    }

    public String backups() {
        return prefix + "backups";
    }

    public String claims() {
        return prefix + "claims";
    }

    public String idxBackupsPlayerCreated() {
        return prefix + "idx_backups_player_created";
    }

    public String idxClaimsBackup() {
        return prefix + "idx_claims_backup";
    }

    public String idxClaimsActorDelivered() {
        return prefix + "idx_claims_actor_delivered";
    }

    private static String normalize(String rawPrefix) {
        if (rawPrefix == null) {
            return "";
        }
        String trimmed = rawPrefix.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!trimmed.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("table prefix only supports letters, numbers, and underscore");
        }
        return trimmed;
    }
}
