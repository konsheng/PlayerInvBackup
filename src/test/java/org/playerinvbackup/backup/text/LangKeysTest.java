package org.playerinvbackup.backup.text;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 语言键完整性测试
 *
 * <p>用于扫描源码中通过 {@code Chat}/{@code Lang} 调用到的语言键, 并校验默认
 * {@code zh_CN.yml} 是否全部提供了对应条目, 避免运行时出现缺失翻译。
 */
final class LangKeysTest {
    @Test
    void allUsedKeysExistInDefaultLangFile() throws IOException {
        Path javaRoot = Paths.get("src/main/java");
        Path langFile = Paths.get("src/main/resources/lang/zh_CN.yml");

        Set<String> codeKeys = extractCodeKeys(javaRoot);
        Set<String> yamlKeys = extractYamlKeys(langFile);

        List<String> missing = new ArrayList<>();
        for (String key : codeKeys) {
            if (!yamlKeys.contains(key)) {
                missing.add(key);
            }
        }
        missing.sort(String::compareTo);

        assertTrue(
                missing.isEmpty(),
                "zh_CN.yml 缺少以下键:\n" + String.join("\n", missing)
        );
    }

    private static Set<String> extractCodeKeys(Path javaRoot) throws IOException {
        Set<String> keys = new HashSet<>();
        if (!Files.exists(javaRoot)) {
            return keys;
        }

        Pattern[] patterns = new Pattern[]{
                Pattern.compile("\\bChat\\.(?:plain|plainList|info|warn|success|error)\\(\\s*[^,]+,\\s*\"([^\"]+)\""),
                Pattern.compile("\\.lang\\(\\)\\.(?:msg|msgNoPrefix|msgList|plain|raw)\\(\\s*\"([^\"]+)\""),
                Pattern.compile("\\blang\\.(?:msg|msgNoPrefix|msgList|plain|raw)\\(\\s*\"([^\"]+)\""),
        };

        try (Stream<Path> stream = Files.walk(javaRoot)) {
            stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        String text;
                        try {
                            text = Files.readString(path, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            return;
                        }

                        for (Pattern pattern : patterns) {
                            Matcher matcher = pattern.matcher(text);
                            while (matcher.find()) {
                                keys.add(matcher.group(1));
                            }
                        }
                    });
        }
        return keys;
    }

    private static Set<String> extractYamlKeys(Path langFile) throws IOException {
        Set<String> keys = new HashSet<>();
        if (!Files.exists(langFile)) {
            return keys;
        }

        record StackEntry(int indent, String key) {
        }

        List<StackEntry> stack = new ArrayList<>();
        List<String> lines = Files.readAllLines(langFile, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line == null) {
                continue;
            }

            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            int indent = countLeadingSpaces(line);
            String noIndent = stripLeadingSpaces(line);
            if (noIndent.startsWith("-")) {
                continue;
            }

            int colonIndex = noIndent.indexOf(':');
            if (colonIndex < 0) {
                continue;
            }

            String keyPart = normalizeYamlKey(noIndent.substring(0, colonIndex));
            if (keyPart.isEmpty()) {
                continue;
            }

            while (!stack.isEmpty() && indent <= stack.get(stack.size() - 1).indent) {
                stack.remove(stack.size() - 1);
            }
            stack.add(new StackEntry(indent, keyPart));

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < stack.size(); i++) {
                if (i > 0) {
                    sb.append('.');
                }
                sb.append(stack.get(i).key);
            }
            keys.add(sb.toString());
        }

        return keys;
    }

    private static int countLeadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String stripLeadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return line.substring(i);
    }

    private static String normalizeYamlKey(String keyPart) {
        String key = keyPart == null ? "" : keyPart.trim();
        if (key.length() >= 2) {
            char first = key.charAt(0);
            char last = key.charAt(key.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                key = key.substring(1, key.length() - 1);
            }
        }
        return key.trim();
    }
}
