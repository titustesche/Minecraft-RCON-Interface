package de.titus.simplycraft.modrinth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** Modrinth API v2 response shapes (https://docs.modrinth.com/api/), reduced to what the console needs */
public final class ModrinthModels {

    private ModrinthModels() {
    }

    public record SearchResult(List<Hit> hits, int offset, int limit, @JsonProperty("total_hits") int totalHits) {
    }

    public record Hit(
            @JsonProperty("project_id") String projectId,
            String slug,
            String title,
            String description,
            String author,
            long downloads,
            @JsonProperty("icon_url") String iconUrl,
            List<String> categories,
            List<String> versions,
            @JsonProperty("server_side") String serverSide,
            @JsonProperty("latest_version") String latestVersion
    ) {
    }

    public record Project(String id, String slug, String title, String description, @JsonProperty("icon_url") String iconUrl) {
    }

    public record Version(
            String id,
            @JsonProperty("project_id") String projectId,
            String name,
            @JsonProperty("version_number") String versionNumber,
            @JsonProperty("version_type") String versionType,
            @JsonProperty("game_versions") List<String> gameVersions,
            List<String> loaders,
            @JsonProperty("date_published") String datePublished,
            List<VersionFile> files,
            List<Dependency> dependencies
    ) {
        public VersionFile primaryFile() {
            if (files == null || files.isEmpty()) return null;
            return files.stream().filter(VersionFile::primary).findFirst().orElse(files.getFirst());
        }
    }

    public record VersionFile(String url, String filename, boolean primary, long size, Map<String, String> hashes) {
    }

    public record Dependency(
            @JsonProperty("version_id") String versionId,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("dependency_type") String dependencyType
    ) {
    }
}
