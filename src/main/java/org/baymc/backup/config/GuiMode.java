package org.baymc.backup.config;

import java.util.Locale;

public enum GuiMode {
    AUTO("auto"),
    BUKKIT("bukkit"),
    PACKET("packet");

    private final String configValue;

    GuiMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static GuiMode parseOrNull(String value) {
        if (value == null) {
            return null;
        }

        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return null;
        }

        return switch (v) {
            case "auto" -> AUTO;
            case "bukkit", "native", "inventory" -> BUKKIT;
            case "packet", "protocollib" -> PACKET;
            default -> null;
        };
    }
}

