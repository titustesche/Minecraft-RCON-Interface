package de.titus.simplycraft.jar;

import de.titus.simplycraft.common.ApiException;
import de.titus.simplycraft.common.Http;
import de.titus.simplycraft.server.JarInfo;
import de.titus.simplycraft.server.ServerConfig;
import de.titus.simplycraft.server.ServerService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/**
 * Puts a server jar at servers/&lt;id&gt;/server.jar: by version (Mojang/Fabric), from a link or from an upload.
 * The old jar is only replaced once the new one is complete and looks like a jar.
 */
@Service
public class JarService {

    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};

    private final ServerService servers;
    private final VersionCatalog catalog;
    private final Http http;

    public JarService(ServerService servers, VersionCatalog catalog, Http http) {
        this.servers = servers;
        this.catalog = catalog;
        this.http = http;
    }

    public ServerConfig installVersion(String id, ServerType type, String version) {
        ServerConfig config = servers.get(id);
        if (version == null || version.isBlank()) throw ApiException.badRequest("Version fehlt");

        Path staging = staging(id);
        long size;
        switch (type) {
            case VANILLA -> {
                var entry = catalog.mojangVersion(version);
                var details = http.getJson(entry.url(), ExternalModels.MojangVersion.class, Map.of());
                var server = details.downloads() != null ? details.downloads().server() : null;
                if (server == null) throw ApiException.badRequest("Für Version " + version + " gibt es keinen Server-Download");
                size = http.download(server.url(), staging, server.sha1(), Map.of());
                config.setLoader("vanilla");
            }
            case FABRIC -> {
                boolean known = catalog.fabricGames().stream().anyMatch(g -> g.version().equals(version));
                if (!known) throw ApiException.badRequest("Fabric unterstützt Version " + version + " nicht");
                String url = catalog.fabricMeta() + "/versions/loader/" + segment(version) + "/"
                        + segment(catalog.latestFabricLoader()) + "/" + segment(catalog.latestFabricInstaller()) + "/server/jar";
                size = http.download(url, staging, null, Map.of());
                writeFabricLauncherProperties(id);
                config.setLoader("fabric");
            }
            default -> throw ApiException.badRequest("Unbekannter Servertyp");
        }
        config.setGameVersion(version);
        return activate(config, staging, new JarInfo(type.name(), version, null, size, Instant.now()));
    }

    public ServerConfig installFromUrl(String id, String url) {
        ServerConfig config = servers.get(id);
        if (url == null || url.isBlank()) throw ApiException.badRequest("Link fehlt");
        Path staging = staging(id);
        long size = http.download(url.trim(), staging, null, Map.of());
        return activate(config, staging, new JarInfo("URL", null, url.trim(), size, Instant.now()));
    }

    public ServerConfig installUpload(String id, MultipartFile file) {
        ServerConfig config = servers.get(id);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Datei fehlt");
        Path staging = staging(id);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, staging, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return activate(config, staging, new JarInfo("UPLOAD", null, file.getOriginalFilename(), file.getSize(), Instant.now()));
    }

    private ServerConfig activate(ServerConfig config, Path staging, JarInfo info) {
        try {
            if (!isJar(staging)) {
                Files.deleteIfExists(staging);
                throw ApiException.badRequest("Die Datei ist keine gültige .jar");
            }
            Files.move(staging, servers.jar(config.getId()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        config.setJar(info);
        return servers.save(config);
    }

    private Path staging(String id) {
        return servers.dir(id).resolve(ServerService.JAR_FILE + ".new");
    }

    /**
     * The Fabric launcher downloads the vanilla server itself, by default to "server.jar" –
     * which is where the launcher lives here. Point it to another file.
     */
    private void writeFabricLauncherProperties(String id) {
        try {
            Files.writeString(servers.dir(id).resolve("fabric-server-launcher.properties"), "serverJar=vanilla-server.jar\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean isJar(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) < ZIP_MAGIC.length) return false;
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(ZIP_MAGIC.length), ZIP_MAGIC);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
