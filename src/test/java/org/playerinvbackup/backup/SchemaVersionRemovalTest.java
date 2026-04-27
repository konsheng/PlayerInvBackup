package org.playerinvbackup.backup;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 该测试文件用于验证主源码和资源文件中已经移除旧的快照 schema 版本逻辑
 * 覆盖 Java 源码 语言文件和配置资源中的关键字残留检查
 * 确保项目主代码不再继续使用 schemaVersion schema_version 或 schema-version
 */
class SchemaVersionRemovalTest {
    @Test
    // 验证主源码和资源文件中不再残留旧的 schema 版本关键字
    // 避免未来修改时重新引入快照版本兼容逻辑
    void mainSourcesDoNotContainRemovedSchemaVersionTokens() throws IOException {
        List<String> tokens = List.of(
                "SCHEMA_VERSION",
                "schemaVersion",
                "schema_version",
                "schema-version",
                "<schema>",
                "Schema:"
        );

        List<String> hits = new ArrayList<>();
        scan(Paths.get("src/main/java"), tokens, hits);
        scan(Paths.get("src/main/resources"), tokens, hits);

        assertTrue(
                hits.isEmpty(),
                "主源码或资源仍然包含已移除的 schema 版本关键字\n" + String.join("\n", hits)
        );
    }

    private static void scan(Path root, List<String> tokens, List<String> hits) throws IOException {
        if (!Files.exists(root)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(root)) {
            stream
                    .filter(Files::isRegularFile)
                    .forEach(path -> collectMatches(path, tokens, hits));
        }
    }

    private static void collectMatches(Path path, List<String> tokens, List<String> hits) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取文件: " + path, e);
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String token : tokens) {
                if (line.contains(token)) {
                    hits.add(path + ":" + (i + 1) + ":" + token);
                }
            }
        }
    }
}
