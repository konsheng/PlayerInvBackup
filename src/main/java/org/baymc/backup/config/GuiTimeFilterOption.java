package org.baymc.backup.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.baymc.backup.text.Lang;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

/**
 * GUI 备份列表的时间筛选项
 *
 * <p>用于把 {@code config.yml} 中的时间筛选字符串解析为不可变对象, 例如:
 * {@code all}、{@code 30m}、{@code 1h}、{@code 7d}。
 *
 * <p>其中:
 * 1) {@code all=true} 表示不过滤时间范围
 * 2) 其余项会同时保留原始数值、时间单位与对应的 {@link Duration}
 * 3) GUI 点击切换时直接基于该对象计算 {@code createdAfterMillis}
 */
public record GuiTimeFilterOption(
        boolean all,
        long amount,
        Unit unit,
        Duration duration
) {
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)([smhd])$");
    private static final List<String> DEFAULT_TOKENS = List.of("all", "30m", "1h", "6h", "12h", "24h", "7d", "30d");
    private static final List<GuiTimeFilterOption> DEFAULTS = buildDefaults();

    public GuiTimeFilterOption {
        if (all) {
            amount = 0L;
            unit = null;
            duration = Duration.ZERO;
        } else {
            if (amount <= 0L) {
                throw new IllegalArgumentException("amount must be positive");
            }
            if (unit == null) {
                throw new IllegalArgumentException("unit must not be null");
            }
            if (duration == null || duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("duration must be positive");
            }
        }
    }

    public static GuiTimeFilterOption allOption() {
        return new GuiTimeFilterOption(true, 0L, null, Duration.ZERO);
    }

    /**
     * 内置默认时间筛选顺序.
     *
     * <p>当配置缺失、为空或全部无效时, GUI 会回退到这组默认项。
     */
    public static List<GuiTimeFilterOption> defaults() {
        return DEFAULTS;
    }

    public long createdAfterMillis(long now) {
        if (all) {
            return 0L;
        }
        return now - duration.toMillis();
    }

    public String displayText(Lang lang) {
        if (lang == null) {
            return fallbackDisplayText();
        }
        if (all) {
            return lang.raw("gui.backup-list.filter-time.value.all");
        }
        String value = String.valueOf(amount);
        String key = amount == 1L ? unit.singularLangKey() : unit.pluralLangKey();
        return lang.plain(key, Placeholder.unparsed("value", value));
    }

    /**
     * 从配置中的时间筛选字符串列表构建 GUI 可用的筛选项集合.
     *
     * <p>规则:
     * 1) 总是保证 {@code all} 位于首位
     * 2) 无效项会被忽略并输出警告
     * 3) 重复项只保留第一次出现的值
     * 4) 若最终没有任何有效时间段, 则回退到内置默认项
     */
    public static List<GuiTimeFilterOption> fromConfig(
            Plugin plugin,
            Lang lang,
            ConfigurationSection section,
            String path
    ) {
        List<String> configured = section == null ? List.of() : section.getStringList(path);
        List<String> source = configured.isEmpty() ? DEFAULT_TOKENS : configured;

        LinkedHashMap<String, GuiTimeFilterOption> unique = new LinkedHashMap<>();
        for (String raw : source) {
            GuiTimeFilterOption option = parse(raw);
            if (option == null) {
                warnInvalid(plugin, lang, path, raw);
                continue;
            }
            if (option.all()) {
                continue;
            }
            unique.putIfAbsent(option.configToken(), option);
        }

        ArrayList<GuiTimeFilterOption> result = new ArrayList<>();
        result.add(allOption());
        result.addAll(unique.values());
        if (result.size() == 1) {
            return DEFAULTS;
        }
        return List.copyOf(result);
    }

    private static void warnInvalid(Plugin plugin, Lang lang, String path, String raw) {
        if (plugin == null || lang == null) {
            return;
        }
        String value = raw == null ? "null" : raw;
        plugin.getLogger().warning(lang.plain(
                "console.config.gui-time-filter-invalid",
                Placeholder.unparsed("path", path == null ? "-" : path),
                Placeholder.unparsed("value", value)
        ));
    }

    /**
     * 解析单个时间筛选令牌.
     *
     * <p>支持格式:
     * {@code all} 或 {@code 数字 + s/m/h/d}。
     */
    private static GuiTimeFilterOption parse(String raw) {
        if (raw == null) {
            return null;
        }

        String token = raw.trim().toLowerCase(Locale.ROOT);
        if (token.isEmpty()) {
            return null;
        }
        if ("all".equals(token)) {
            return allOption();
        }

        Matcher matcher = DURATION_PATTERN.matcher(token);
        if (!matcher.matches()) {
            return null;
        }

        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (amount <= 0L) {
            return null;
        }

        Unit unit = Unit.fromSuffix(matcher.group(2).charAt(0));
        if (unit == null) {
            return null;
        }

        Duration duration;
        try {
            duration = unit.toDuration(amount);
        } catch (ArithmeticException e) {
            return null;
        }
        return new GuiTimeFilterOption(false, amount, unit, duration);
    }

    private String configToken() {
        if (all) {
            return "all";
        }
        return amount + String.valueOf(unit.suffix());
    }

    private String fallbackDisplayText() {
        if (all) {
            return "All";
        }
        String unitText = amount == 1L ? unit.englishSingular() : unit.englishPlural();
        return "Last " + amount + " " + unitText;
    }

    private static List<GuiTimeFilterOption> buildDefaults() {
        ArrayList<GuiTimeFilterOption> defaults = new ArrayList<>();
        defaults.add(allOption());
        for (String token : DEFAULT_TOKENS) {
            GuiTimeFilterOption option = parse(token);
            if (option == null || option.all()) {
                continue;
            }
            defaults.add(option);
        }
        return List.copyOf(defaults);
    }

    /**
     * 时间筛选支持的单位.
     *
     * <p>同时维护:
     * 1) 配置中使用的后缀
     * 2) 语言文件中显示文本对应的键
     * 3) 无语言环境时的英文回退文本
     */
    public enum Unit {
        SECOND('s', "gui.backup-list.filter-time.value.second", "gui.backup-list.filter-time.value.seconds", "second", "seconds"),
        MINUTE('m', "gui.backup-list.filter-time.value.minute", "gui.backup-list.filter-time.value.minutes", "minute", "minutes"),
        HOUR('h', "gui.backup-list.filter-time.value.hour", "gui.backup-list.filter-time.value.hours", "hour", "hours"),
        DAY('d', "gui.backup-list.filter-time.value.day", "gui.backup-list.filter-time.value.days", "day", "days");

        private final char suffix;
        private final String singularLangKey;
        private final String pluralLangKey;
        private final String englishSingular;
        private final String englishPlural;

        Unit(
                char suffix,
                String singularLangKey,
                String pluralLangKey,
                String englishSingular,
                String englishPlural
        ) {
            this.suffix = suffix;
            this.singularLangKey = singularLangKey;
            this.pluralLangKey = pluralLangKey;
            this.englishSingular = englishSingular;
            this.englishPlural = englishPlural;
        }

        static Unit fromSuffix(char suffix) {
            for (Unit unit : values()) {
                if (unit.suffix == suffix) {
                    return unit;
                }
            }
            return null;
        }

        Duration toDuration(long amount) {
            return switch (this) {
                case SECOND -> Duration.ofSeconds(amount);
                case MINUTE -> Duration.ofMinutes(amount);
                case HOUR -> Duration.ofHours(amount);
                case DAY -> Duration.ofDays(amount);
            };
        }

        String singularLangKey() {
            return singularLangKey;
        }

        String pluralLangKey() {
            return pluralLangKey;
        }

        char suffix() {
            return suffix;
        }

        String englishSingular() {
            return englishSingular;
        }

        String englishPlural() {
            return englishPlural;
        }
    }
}
