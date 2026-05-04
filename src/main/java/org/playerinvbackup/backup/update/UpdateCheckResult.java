package org.playerinvbackup.backup.update;

import java.time.Instant;

public record UpdateCheckResult(
        boolean updateAvailable,
        String currentVersion,
        String latestVersion,
        String latestTag,
        String releaseUrl,
        Instant checkedAt
) {
}
