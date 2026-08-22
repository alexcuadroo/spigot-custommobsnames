package com.wazustudio.spigot.custommobsnames;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/** Checks the latest public GitHub release without blocking the server thread. */
final class UpdateChecker {

    private static final String GITHUB_API_URL =
            "https://api.github.com/repos/WazuStudio/spigot-custommobsnames/releases/latest";
    private static final String RELEASES_URL =
            "https://github.com/WazuStudio/spigot-custommobsnames/releases/tag/";

    private final JavaPlugin plugin;

    UpdateChecker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void checkForUpdates() {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(GITHUB_API_URL).toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", plugin.getName() + "-UpdateChecker");
                connection.setConnectTimeout(5_000);
                connection.setReadTimeout(5_000);

                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    plugin.getLogger().warning("No se pudo verificar actualizaciones (HTTP " + status + ").");
                    return;
                }

                String latestTag;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    latestTag = parseTagName(response.toString());
                }

                if (latestTag == null) {
                    plugin.getLogger().warning("No se pudo obtener la versión más reciente de GitHub.");
                    return;
                }

                String currentVersion = plugin.getPluginMeta().getVersion();
                if (!currentVersion.equals(normalizeVersion(latestTag))) {
                    plugin.getLogger().info("========================================");
                    plugin.getLogger().info("¡Nueva versión disponible! " + currentVersion + " -> " + latestTag);
                    plugin.getLogger().info("Descárgala en: " + RELEASES_URL + latestTag);
                    plugin.getLogger().info("========================================");
                }
            } catch (Exception exception) {
                plugin.getLogger().warning("Error al verificar actualizaciones: " + exception.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private static String normalizeVersion(String tag) {
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    private static @Nullable String parseTagName(String json) {
        String key = "\"tag_name\"";
        int keyIndex = json.indexOf(key);
        if (keyIndex == -1) {
            return null;
        }
        int colonIndex = json.indexOf(':', keyIndex + key.length());
        int firstQuote = colonIndex == -1 ? -1 : json.indexOf('"', colonIndex + 1);
        int secondQuote = firstQuote == -1 ? -1 : json.indexOf('"', firstQuote + 1);
        return secondQuote == -1 ? null : json.substring(firstQuote + 1, secondQuote);
    }
}
