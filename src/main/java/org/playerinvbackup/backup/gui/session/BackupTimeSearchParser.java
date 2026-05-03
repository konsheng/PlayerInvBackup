package org.playerinvbackup.backup.gui.session;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

/**
 * GUI 时间搜索输入解析器
 */
public final class BackupTimeSearchParser {
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter MINUTE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter SECOND_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DISPLAY_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern MINUTE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}");
    private static final Pattern SECOND = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}");
    private static final Pattern ASCII_TILDE_RANGE = Pattern.compile("\\s*~\\s*");
    private static final Pattern FULLWIDTH_TILDE_RANGE = Pattern.compile("\\s*～\\s*");
    private static final Pattern DASH_RANGE = Pattern.compile("\\s+-\\s+");

    private final ZoneId zoneId;

    public BackupTimeSearchParser(ZoneId zoneId) {
        this.zoneId = zoneId == null ? ZoneId.systemDefault() : zoneId;
    }

    public TimeRange parse(String input) {
        String safe = input == null ? "" : input.trim();
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("empty");
        }

        String[] parts = splitRange(safe);
        if (parts.length == 1) {
            ParsedTime time = parseTime(parts[0].trim());
            return range(time.start(), time.end());
        }
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("invalid_range");
        }

        ParsedTime start = parseTime(parts[0].trim());
        ParsedTime end = parseTime(parts[1].trim());
        return range(start.start(), end.end());
    }

    private TimeRange range(LocalDateTime start, LocalDateTime end) {
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("start_after_end");
        }
        long startMillis = start.atZone(zoneId).toInstant().toEpochMilli();
        long endMillis = end.atZone(zoneId).toInstant().toEpochMilli() + 999L;
        return new TimeRange(startMillis, endMillis, DISPLAY_FORMATTER.format(start), DISPLAY_FORMATTER.format(end));
    }

    private static String[] splitRange(String input) {
        String[] tilde = ASCII_TILDE_RANGE.split(input, -1);
        if (tilde.length > 1) {
            return tilde;
        }
        String[] fullwidthTilde = FULLWIDTH_TILDE_RANGE.split(input, -1);
        if (fullwidthTilde.length > 1) {
            return fullwidthTilde;
        }
        return DASH_RANGE.split(input, -1);
    }

    private static ParsedTime parseTime(String input) {
        try {
            if (DATE.matcher(input).matches()) {
                LocalDate date = LocalDate.parse(input, DATE_FORMATTER);
                return new ParsedTime(date.atStartOfDay(), date.atTime(LocalTime.of(23, 59, 59)));
            }
            if (MINUTE.matcher(input).matches()) {
                LocalDateTime minute = LocalDateTime.parse(input, MINUTE_FORMATTER);
                return new ParsedTime(minute.withSecond(0), minute.withSecond(59));
            }
            if (SECOND.matcher(input).matches()) {
                LocalDateTime second = LocalDateTime.parse(input, SECOND_FORMATTER);
                return new ParsedTime(second, second);
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid_time", e);
        }
        throw new IllegalArgumentException("unsupported_format");
    }

    private record ParsedTime(LocalDateTime start, LocalDateTime end) {
    }

    public record TimeRange(long startMillis, long endMillis, String startText, String endText) {
    }
}
