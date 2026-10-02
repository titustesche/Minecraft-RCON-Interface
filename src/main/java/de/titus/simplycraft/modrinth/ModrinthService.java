package de.titus.simplycraft.modrinth;

import de.titus.simplycraft.common.ApiException;
import de.titus.simplycraft.common.Http;
import de.titus.simplycraft.common.SafePaths;
import de.titus.simplycraft.mods.ModInfo;
import de.titus.simplycraft.mods.ModService;
import de.titus.simplycraft.mods.ModSource;
import de.titus.simplycraft.server.ServerConfig;
import de.titus.simplycraft.server.ServerService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Installs Modrinth mods into a server's mods folder, matching the server's loader and
 * Minecraft version, including required dependencies (e.g. Fabric API).
 */
@Service
public class ModrinthService {

    private static final int MAX_DEPENDENCY_DEPTH = 5;

    private final ModrinthClient client;
    private final ServerService servers;
    private final ModService mods;
    private final Http http;

    public ModrinthService(ModrinthClient client, ServerService servers, ModService mods, Http http) {
        this.client = client;
        this.servers = servers;
        this.mods = mods;
        this.http = http;
    }

    public record InstallResult(List<ModInfo> installed, List<String> skipped) {
    }

    public ModrinthModels.SearchResult search(String serverId, String query, int offset, int limit) {
        ServerConfig config = serverId != null ? servers.get(serverId) : null;
        String loader = config != null ? modLoader(config, false) : null;
        String gameVersion = config != null ? config.getGameVersion() : null;
        return client.search(query, loader, gameVersion, offset, limit);
    }

    public List<ModrinthModels.Version> versions(String serverId, String projectId) {
        ServerConfig config = servers.get(serverId);
        return client.versions(projectId, modLoader(config, false), config.getGameVersion());
    }

    public synchronized InstallResult install(String serverId, String projectId, String versionId) {
        ServerConfig config = servers.get(serverId);
        String loader = modLoader(config, true);
        if ((projectId == null || projectId.isBlank()) && (versionId == null || versionId.isBlank())) {
            throw ApiException.badRequest("Projekt oder Version fehlt");
        }
        var context = new Context(config, loader, mods.installedProjects(serverId));
        install(context, projectId, versionId, 0);
        return new InstallResult(context.installed, context.skipped);
    }

    private void install(Context ctx, String projectId, String versionId, int depth) {
        ModrinthModels.Version version;
        if (depth == 0 && versionId != null && !versionId.isBlank()) {
            version = client.version(versionId);
        } else if (projectId != null && !projectId.isBlank()) {
            version = latestCompatible(ctx, projectId);
        } else {
            version = client.version(versionId);
        }
        String project = version.projectId();
        if (!ctx.visited.add(project)) return;

        String existing = ctx.installedProjects.get(project);
        if (existing != null && depth > 0) {
            ctx.skipped.add(existing + " (bereits installiert)");
            return;
        }

        var file = version.primaryFile();
        if (file == null) throw ApiException.badGateway("Version " + version.versionNumber() + " hat keine Datei", null);
        String fileName = SafePaths.sanitizeFileName(file.filename());
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw ApiException.badGateway("Unerwartete Datei von Modrinth: " + file.filename(), null);
        }

        Path modsDir = mods.modsDir(ctx.config.getId());
        Path target = SafePaths.resolveInside(modsDir, fileName);
        String sha1 = file.hashes() != null ? file.hashes().get("sha1") : null;
        http.download(file.url(), target, sha1, Map.of());

        // An update replaces the previously installed file of the same project
        if (existing != null && !existing.equals(fileName)) {
            try {
                Files.deleteIfExists(SafePaths.resolveInside(modsDir, existing));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        var meta = client.project(project);
        mods.remember(ctx.config.getId(), fileName, new ModSource(
                project, meta.slug(), version.id(), meta.title(), version.versionNumber(), meta.iconUrl()));
        ctx.installed.add(mods.info(ctx.config.getId(), fileName));
        ctx.installedProjects.put(project, fileName);

        if (depth >= MAX_DEPENDENCY_DEPTH || version.dependencies() == null) return;
        for (var dependency : version.dependencies()) {
            if (!"required".equals(dependency.dependencyType())) continue;
            if (dependency.projectId() == null && dependency.versionId() == null) continue;
            install(ctx, dependency.projectId(), dependency.versionId(), depth + 1);
        }
    }

    private ModrinthModels.Version latestCompatible(Context ctx, String projectId) {
        var versions = client.versions(projectId, ctx.loader, ctx.config.getGameVersion());
        if (versions.isEmpty()) {
            throw ApiException.badRequest("Keine passende Version für " + ctx.loader
                    + (ctx.config.getGameVersion() != null ? " " + ctx.config.getGameVersion() : "") + " gefunden (" + projectId + ")");
        }
        // Prefer releases over beta/alpha builds
        return versions.stream().filter(v -> "release".equals(v.versionType())).findFirst().orElse(versions.getFirst());
    }

    /** Loader of the server for Modrinth, e.g. "fabric". Vanilla servers cannot load mods. */
    private static String modLoader(ServerConfig config, boolean required) {
        String loader = config.getLoader();
        if (loader == null || loader.isBlank() || loader.equals("vanilla")) {
            if (required) {
                throw ApiException.badRequest("Dieser Server hat keinen Mod-Loader. Installiere eine Fabric-Version oder trage den Loader in den Einstellungen ein.");
            }
            return null;
        }
        return loader;
    }

    private static final class Context {
        final ServerConfig config;
        final String loader;
        final Map<String, String> installedProjects;
        final Set<String> visited = new HashSet<>();
        final List<ModInfo> installed = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();

        Context(ServerConfig config, String loader, Map<String, String> installedProjects) {
            this.config = config;
            this.loader = loader;
            this.installedProjects = new java.util.HashMap<>(installedProjects);
        }
    }
}
