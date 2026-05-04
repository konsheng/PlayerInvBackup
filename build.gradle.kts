// 启用 Java 和 Shadow 插件
plugins {
    id("java")
    id("com.gradleup.shadow") version "9.3.1"
}

// 项目 group
group = "org.playerinvbackup"
// 项目版本, 默认 1.0-SNAPSHOT, 可通过 -PpluginVersionOverride 覆盖
version = providers.gradleProperty("pluginVersionOverride").orElse("1.0-SNAPSHOT").get()

repositories {
    // 中央仓库
    mavenCentral()
    // PaperMC 仓库
    maven("https://repo.papermc.io/repository/maven-public/")
    // ProtocolLib 仓库
    maven("https://repo.dmulloy2.net/repository/public/")
}

dependencies {
    // Paper API
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    // 测试运行时也需要 Paper API
    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    // ProtocolLib API
    compileOnly("com.comphenix.protocol:ProtocolLib:5.3.0")
    // bStats
    implementation("org.bstats:bstats-bukkit:3.1.0")
    // SQLite 驱动
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    // MySQL 驱动
    implementation("com.mysql:mysql-connector-j:8.0.33")
    // PostgreSQL 驱动
    implementation("org.postgresql:postgresql:42.7.5")
    // H2 驱动
    implementation("com.h2database:h2:2.2.224")
    // HikariCP 连接池
    implementation("com.zaxxer:HikariCP:5.1.0")
    // Gson, 用于解析 GitHub Releases API 响应
    implementation("com.google.code.gson:gson:2.11.0")

    // JUnit 5
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        // 使用 Java 21
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // 使用 UTF-8
    options.encoding = "UTF-8"
    // 开启弃用警告
    options.compilerArgs.add("-Xlint:deprecation")
}

// 插件名
val pluginName = project.name
// 插件版本
val pluginVersion = project.version.toString()
// 仅在 CI 显式传入时附加提交哈希; 本地构建默认留空
val gitCommitShort = providers.gradleProperty("gitCommitShort").orElse("").get()
// 产物版本, 可通过 -PartifactVersionOverride 覆盖
val artifactVersion = providers.gradleProperty("artifactVersionOverride").orElse(pluginVersion).get()

tasks.processResources {
    // 资源过滤使用 UTF-8
    filteringCharset = "UTF-8"
    filesMatching(listOf("plugin.yml", "build-info.properties")) {
        // 展开资源占位符
        expand(
            mapOf(
                "name" to pluginName,
                "version" to pluginVersion,
                "gitCommitShort" to gitCommitShort,
            ),
        )
    }
}

tasks.test {
    // 使用 JUnit Platform
    useJUnitPlatform()
}

tasks.jar {
    // 关闭普通 jar
    enabled = false
}

tasks.shadowJar {
    // 设置产物基础名
    archiveBaseName.set(pluginName)
    // 设置产物版本
    archiveVersion.set(artifactVersion)
    // 不使用分类后缀
    archiveClassifier.set("")
    // 排除重复文件
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // 排除签名和索引文件
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
    // 重定位 bStats
    relocate("org.bstats", "org.playerinvbackup.backup.libs.bstats")
    // 重定位 Gson
    relocate("com.google.gson", "org.playerinvbackup.backup.libs.gson")
}

tasks.build {
    // build 依赖 shadowJar
    dependsOn(tasks.shadowJar)
}

tasks.register("printVersion") {
    // 输出当前插件版本
    group = "help"
    description = "输出当前插件版本号, 供 CI 读取开发版基础版本"
    doLast {
        println(pluginVersion)
    }
}
