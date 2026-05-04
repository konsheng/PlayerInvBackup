package org.playerinvbackup.backup.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SemanticVersionTest {
    @Test
    void parsesTwoPartAndThreePartVersions() {
        assertEquals(new SemanticVersion(1, 2, 0), SemanticVersion.parse("1.2").orElseThrow());
        assertEquals(new SemanticVersion(1, 2, 3), SemanticVersion.parse("v1.2.3").orElseThrow());
    }

    @Test
    void rejectsUnsupportedVersionSuffixes() {
        assertTrue(SemanticVersion.parse("1.2.3-SNAPSHOT").isEmpty());
        assertTrue(SemanticVersion.parse("dev").isEmpty());
    }

    @Test
    void comparesPatchNumerically() {
        SemanticVersion older = SemanticVersion.parse("1.2.9").orElseThrow();
        SemanticVersion newer = SemanticVersion.parse("1.2.10").orElseThrow();

        assertTrue(newer.compareTo(older) > 0);
    }
}
