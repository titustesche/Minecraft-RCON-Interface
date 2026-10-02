package de.titus.simplycraft.modrinth;

import de.titus.simplycraft.common.Http;
import de.titus.simplycraft.config.SimplycraftProperties;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ModrinthApiClient implements ModrinthClient {

    private final Http http;
    private final SimplycraftProperties properties;

    public ModrinthApiClient(Http http, SimplycraftProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public ModrinthModels.SearchResult search(String query, String loader, String gameVersion, int offset, int limit) {
        List<String> facets = new ArrayList<>();
        facets.add("[\"project_type:mod\"]");
        facets.add("[\"server_side:required\",\"server_side:optional\"]");
        if (present(loader)) facets.add("[\"categories:" + json(loader) + "\"]");
        if (present(gameVersion)) facets.add("[\"versions:" + json(gameVersion) + "\"]");

        String url = base() + "/search?query=" + encode(query == null ? "" : query)
                + "&facets=" + encode("[" + String.join(",", facets) + "]")
                + "&offset=" + Math.max(0, offset)
                + "&limit=" + Math.clamp(limit, 1, 100)
                + "&index=relevance";
        return http.getJson(url, ModrinthModels.SearchResult.class, headers());
    }

    @Override
    public ModrinthModels.Project project(String idOrSlug) {
        return http.getJson(base() + "/project/" + encode(idOrSlug), ModrinthModels.Project.class, headers());
    }

    @Override
    public List<ModrinthModels.Version> versions(String projectId, String loader, String gameVersion) {
        StringBuilder url = new StringBuilder(base() + "/project/" + encode(projectId) + "/version");
        String separator = "?";
        if (present(loader)) {
            url.append(separator).append("loaders=").append(encode("[\"" + json(loader) + "\"]"));
            separator = "&";
        }
        if (present(gameVersion)) {
            url.append(separator).append("game_versions=").append(encode("[\"" + json(gameVersion) + "\"]"));
        }
        var type = http.mapper().getTypeFactory().constructCollectionType(List.class, ModrinthModels.Version.class);
        return http.getJson(url.toString(), type, headers());
    }

    @Override
    public ModrinthModels.Version version(String versionId) {
        return http.getJson(base() + "/version/" + encode(versionId), ModrinthModels.Version.class, headers());
    }

    /** Modrinth asks every client to send a unique User-Agent */
    Map<String, String> headers() {
        return Map.of("User-Agent", properties.modrinth().userAgent());
    }

    private String base() {
        String url = properties.modrinth().apiUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
