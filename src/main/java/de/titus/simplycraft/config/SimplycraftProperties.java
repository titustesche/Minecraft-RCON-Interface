package de.titus.simplycraft.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * All settings live in application.properties (prefix "simplycraft") and can be overridden
 * with environment variables, e.g. SIMPLYCRAFT_SERVERS_PATH or SIMPLYCRAFT_SIMPLYFILE_URL.
 */
@ConfigurationProperties("simplycraft")
public record SimplycraftProperties(
        Path serversPath,
        Path backupsPath,
        String javaPath,
        String defaultXms,
        String defaultXmx,
        int consoleBacklog,
        Duration stopTimeout,
        String apiToken,
        boolean customCommandEnabled,
        List<String> corsAllowedOrigins,
        Simplyfile simplyfile,
        Modrinth modrinth,
        Sources sources
) {
    /** Simplyfile instance that receives backups. Empty url disables the integration. */
    public record Simplyfile(String url, String token) {
        public boolean enabled() {
            return url != null && !url.isBlank();
        }

        public String baseUrl() {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    public record Modrinth(String apiUrl, String userAgent) {
    }

    /** Where server jars come from */
    public record Sources(String mojangManifest, String fabricMeta) {
    }

    public boolean authRequired() {
        return apiToken != null && !apiToken.isBlank();
    }
}
