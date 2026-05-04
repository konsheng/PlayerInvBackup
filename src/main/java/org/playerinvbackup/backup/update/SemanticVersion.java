package org.playerinvbackup.backup.update;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SemanticVersion(int major, int minor, int patch) implements Comparable<SemanticVersion> {
    private static final Pattern VERSION_PATTERN = Pattern.compile("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?$", Pattern.CASE_INSENSITIVE);

    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version parts must not be negative");
        }
    }

    public static Optional<SemanticVersion> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = VERSION_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            String patchGroup = matcher.group(3);
            int patch = patchGroup == null ? 0 : Integer.parseInt(patchGroup);
            return Optional.of(new SemanticVersion(major, minor, patch));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(SemanticVersion other) {
        Objects.requireNonNull(other, "other");
        int majorCompare = Integer.compare(major, other.major);
        if (majorCompare != 0) {
            return majorCompare;
        }
        int minorCompare = Integer.compare(minor, other.minor);
        if (minorCompare != 0) {
            return minorCompare;
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
