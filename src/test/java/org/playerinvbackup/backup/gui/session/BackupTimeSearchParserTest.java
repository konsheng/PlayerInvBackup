package org.playerinvbackup.backup.gui.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BackupTimeSearchParserTest {
    private final BackupTimeSearchParser parser = new BackupTimeSearchParser(ZoneId.of("UTC"));

    @Test
    void dateSearchCoversWholeDay() {
        BackupTimeSearchParser.TimeRange range = parser.parse("2026-05-04");

        assertEquals("2026-05-04 00:00:00", range.startText());
        assertEquals("2026-05-04 23:59:59", range.endText());
        assertEquals(1_777_852_800_000L, range.startMillis());
        assertEquals(1_777_939_199_999L, range.endMillis());
    }

    @Test
    void minuteSearchCoversWholeMinute() {
        BackupTimeSearchParser.TimeRange range = parser.parse("2026-05-04 20:30");

        assertEquals("2026-05-04 20:30:00", range.startText());
        assertEquals("2026-05-04 20:30:59", range.endText());
    }

    @Test
    void secondSearchCoversWholeSecond() {
        BackupTimeSearchParser.TimeRange range = parser.parse("2026-05-04 20:30:15");

        assertEquals("2026-05-04 20:30:15", range.startText());
        assertEquals("2026-05-04 20:30:15", range.endText());
        assertEquals(range.startMillis() + 999L, range.endMillis());
    }

    @Test
    void rangeSupportsAsciiAndFullwidthTilde() {
        BackupTimeSearchParser.TimeRange ascii = parser.parse("2026-05-04 ~ 2026-05-05");
        BackupTimeSearchParser.TimeRange fullwidth = parser.parse("2026-05-04 ～ 2026-05-05");

        assertEquals("2026-05-04 00:00:00", ascii.startText());
        assertEquals("2026-05-05 23:59:59", ascii.endText());
        assertEquals(ascii.startMillis(), fullwidth.startMillis());
        assertEquals(ascii.endMillis(), fullwidth.endMillis());
    }

    @Test
    void rangeSupportsDashSeparatorWithSpaces() {
        BackupTimeSearchParser.TimeRange range = parser.parse("2026-05-04 20:00 - 2026-05-04 21:00");

        assertEquals("2026-05-04 20:00:00", range.startText());
        assertEquals("2026-05-04 21:00:59", range.endText());
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("20:30"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("2026/05/04"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("2026-05-05 ~ 2026-05-04"));
    }
}
