package org.playerinvbackup.backup.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.config.PluginConfig;
import org.playerinvbackup.backup.config.UpdateCheckerSettings;
import org.playerinvbackup.backup.text.Chat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class UpdateCheckerService {
    private static final URI LATEST_RELEASE_API =
            URI.create("https://api.github.com/repos/konsheng/PlayerInvBackup/releases/latest");
    private static final String LATEST_RELEASE_URL = "https://github.com/konsheng/PlayerInvBackup/releases/latest";
    private static final Duration STARTUP_CHECK_DELAY = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final PlayerInvBackupPlugin plugin;
    private final HttpClient httpClient;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicReference<UpdateCheckResult> lastResult = new AtomicReference<>();
    private final ConcurrentHashMap<UUID, String> notifiedVersions = new ConcurrentHashMap<>();

    private volatile UpdateCheckerSettings settings = UpdateCheckerSettings.defaults();
    private volatile ScheduledTask task;
    private volatile String lastConsoleNotifiedVersion;

    public UpdateCheckerService(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public synchronized void reload(PluginConfig config) {
        settings = config == null ? UpdateCheckerSettings.defaults() : config.updateChecker();
        cancelTask();

        if (!settings.enabled()) {
            return;
        }

        long intervalSeconds = Math.max(1L, settings.checkInterval().toSeconds());
        task = Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                ignored -> checkLatestRelease(),
                Math.max(1L, STARTUP_CHECK_DELAY.toSeconds()),
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    public synchronized void shutdown() {
        cancelTask();
        notifiedVersions.clear();
    }

    public void onPlayerJoin(Player player) {
        if (player == null) {
            return;
        }
        player.getScheduler().runDelayed(plugin, ignored -> notifyAdminIfNeeded(player, lastResult.get()), null, 40L);
    }

    public void onPlayerQuit(Player player) {
        if (player == null) {
            return;
        }
        notifiedVersions.remove(player.getUniqueId());
    }

    private void checkLatestRelease() {
        UpdateCheckerSettings currentSettings = settings;
        if (!plugin.isEnabled() || currentSettings == null || !currentSettings.enabled()) {
            return;
        }
        if (!checking.compareAndSet(false, true)) {
            return;
        }

        try {
            UpdateCheckResult result = fetchAndCompare();
            currentSettings = settings;
            if (!plugin.isEnabled() || currentSettings == null || !currentSettings.enabled()) {
                return;
            }
            lastResult.set(result);
            if (result.updateAvailable()) {
                logUpdateAvailableOnce(result);
                notifyOnlineAdmins(result);
            }
        } catch (Exception e) {
            if (plugin.isEnabled()) {
                logCheckFailed(e);
            }
        } finally {
            checking.set(false);
        }
    }

    private UpdateCheckResult fetchAndCompare() throws Exception {
        String currentVersionRaw = plugin.getPluginMeta().getVersion();
        if (isSnapshotVersion(currentVersionRaw)) {
            return noUpdate(currentVersionRaw, "", "");
        }

        Optional<SemanticVersion> currentVersion = SemanticVersion.parse(currentVersionRaw);
        if (currentVersion.isEmpty()) {
            logInvalidVersion("console.update-checker.invalid-current-version", currentVersionRaw);
            return noUpdate(currentVersionRaw, "", "");
        }

        GitHubRelease release = fetchLatestRelease();
        Optional<SemanticVersion> latestVersion = SemanticVersion.parse(release.tagName());
        if (latestVersion.isEmpty()) {
            logInvalidVersion("console.update-checker.invalid-remote-version", release.tagName());
            return noUpdate(currentVersionRaw, release.tagName(), release.releaseUrl());
        }

        boolean updateAvailable = latestVersion.get().compareTo(currentVersion.get()) > 0;
        return new UpdateCheckResult(
                updateAvailable,
                currentVersion.get().toString(),
                latestVersion.get().toString(),
                release.tagName(),
                release.releaseUrl(),
                Instant.now()
        );
    }

    private GitHubRelease fetchLatestRelease() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(LATEST_RELEASE_API)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PlayerInvBackup/" + plugin.getPluginMeta().getVersion())
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }

        int status = response.statusCode();
        if (status != 200) {
            throw new IllegalStateException("GitHub API returned HTTP " + status);
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String tagName = stringField(json, "tag_name");
        if (tagName.isBlank()) {
            throw new IllegalStateException("GitHub API response missing tag_name");
        }
        String releaseUrl = stringField(json, "html_url");
        if (releaseUrl.isBlank()) {
            releaseUrl = LATEST_RELEASE_URL;
        }
        return new GitHubRelease(tagName, releaseUrl);
    }

    private void notifyOnlineAdmins(UpdateCheckResult result) {
        UpdateCheckerSettings currentSettings = settings;
        if (result == null || !result.updateAvailable() || currentSettings == null || !currentSettings.notifyAdmins()) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            Collection<? extends Player> players = Bukkit.getOnlinePlayers();
            for (Player player : players) {
                if (player == null) {
                    continue;
                }
                player.getScheduler().run(plugin, ignored -> notifyAdminIfNeeded(player, result), null);
            }
        });
    }

    private void notifyAdminIfNeeded(Player player, UpdateCheckResult result) {
        UpdateCheckerSettings currentSettings = settings;
        if (player == null
                || result == null
                || !result.updateAvailable()
                || currentSettings == null
                || !currentSettings.enabled()
                || !currentSettings.notifyAdmins()
                || !player.isOnline()
                || !player.hasPermission(Permissions.ADMIN)) {
            return;
        }

        String versionKey = result.latestVersion();
        String previous = notifiedVersions.putIfAbsent(player.getUniqueId(), versionKey);
        if (versionKey.equals(previous)) {
            return;
        }
        if (previous != null) {
            notifiedVersions.replace(player.getUniqueId(), previous, versionKey);
        }

        Chat.warn(
                player,
                "warn.update-available",
                Placeholder.unparsed("current", result.currentVersion()),
                Placeholder.unparsed("latest", result.latestVersion()),
                Placeholder.unparsed("tag", result.latestTag()),
                Placeholder.unparsed("url", result.releaseUrl())
        );
    }

    private void logUpdateAvailableOnce(UpdateCheckResult result) {
        if (!plugin.isEnabled()) {
            return;
        }
        String latestVersion = result.latestVersion();
        if (latestVersion == null || latestVersion.isBlank()) {
            return;
        }
        if (latestVersion.equals(lastConsoleNotifiedVersion)) {
            return;
        }
        lastConsoleNotifiedVersion = latestVersion;
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> plugin.getLogger().info(plugin.lang().plain(
                "console.update-checker.update-available",
                Placeholder.unparsed("current", result.currentVersion()),
                Placeholder.unparsed("latest", result.latestVersion()),
                Placeholder.unparsed("tag", result.latestTag()),
                Placeholder.unparsed("url", result.releaseUrl())
        )));
    }

    private void logCheckFailed(Exception e) {
        if (!plugin.isEnabled()) {
            return;
        }
        String reason = e == null ? "-" : String.valueOf(e.getMessage());
        String fallbackReason = e == null ? "-" : e.getClass().getSimpleName();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> plugin.getLogger().log(
                Level.WARNING,
                plugin.lang().plain(
                        "console.update-checker.check-failed",
                        Placeholder.unparsed("reason", reason == null || reason.isBlank() ? fallbackReason : reason)
                )
        ));
    }

    private void logInvalidVersion(String key, String version) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> plugin.getLogger().warning(plugin.lang().plain(
                key,
                Placeholder.unparsed("version", version == null ? "-" : version)
        )));
    }

    private UpdateCheckResult noUpdate(String currentVersion, String latestTag, String releaseUrl) {
        return new UpdateCheckResult(
                false,
                currentVersion == null ? "" : currentVersion,
                "",
                latestTag == null ? "" : latestTag,
                releaseUrl == null || releaseUrl.isBlank() ? LATEST_RELEASE_URL : releaseUrl,
                Instant.now()
        );
    }

    private void cancelTask() {
        ScheduledTask currentTask = task;
        if (currentTask == null) {
            return;
        }
        currentTask.cancel();
        task = null;
    }

    private static String stringField(JsonObject json, String field) {
        if (json == null || field == null || !json.has(field) || json.get(field).isJsonNull()) {
            return "";
        }
        try {
            return json.get(field).getAsString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isSnapshotVersion(String version) {
        return version != null && version.toUpperCase(Locale.ROOT).contains("SNAPSHOT");
    }

    private record GitHubRelease(String tagName, String releaseUrl) {
    }
}
