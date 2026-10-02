package de.titus.simplycraft.server;

import de.titus.simplycraft.common.ApiException;
import de.titus.simplycraft.config.SimplycraftProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Manages the server directories: servers/&lt;id&gt;/{simplycraft.json, server.jar, mods/, world/, ...}
 */
@Service
public class ServerService {

    public static final String CONFIG_FILE = "simplycraft.json";
    public static final String JAR_FILE = "server.jar";

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,47}$");
    private static final Pattern MEMORY = Pattern.compile("^[1-9][0-9]*[KMGkmg]?$");
    private static final Pattern LOADER = Pattern.compile("^[a-z0-9_-]{1,32}$");
    private static final Pattern GAME_VERSION = Pattern.compile("^[A-Za-z0-9._+ -]{1,32}$");

    private final SimplycraftProperties properties;
    private final ObjectMapper mapper;
    private final Path root;

    public ServerService(SimplycraftProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
        this.root = properties.serversPath().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create servers directory " + root, e);
        }
    }

    public List<ServerConfig> list() {
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs
                    .filter(dir -> Files.isRegularFile(dir.resolve(CONFIG_FILE)))
                    .map(dir -> read(dir.resolve(CONFIG_FILE)))
                    .sorted(Comparator.comparing(ServerConfig::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ServerConfig get(String id) {
        Path file = dir(id).resolve(CONFIG_FILE);
        if (!Files.isRegularFile(file)) throw ApiException.notFound("Server \"" + id + "\" existiert nicht");
        return read(file);
    }

    public synchronized ServerConfig create(CreateServerRequest request) {
        String id = uniqueId(slug(request.name()));
        ServerConfig config = new ServerConfig();
        config.setId(id);
        config.setName(request.name().trim());
        config.setPort(request.port());
        config.setXms(memory(request.xms(), properties.defaultXms()));
        config.setXmx(memory(request.xmx(), properties.defaultXmx()));
        config.setCreatedAt(Instant.now());

        try {
            Files.createDirectories(dir(id).resolve("mods"));
            if (request.port() != null) writeServerPort(id, request.port());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return save(config);
    }

    public synchronized ServerConfig update(String id, UpdateServerRequest request) {
        ServerConfig config = get(id);
        if (request.name() != null) config.setName(request.name().trim());
        if (request.xms() != null) config.setXms(memory(request.xms(), null));
        if (request.xmx() != null) config.setXmx(memory(request.xmx(), null));
        if (request.javaPath() != null) config.setJavaPath(request.javaPath().isBlank() ? null : request.javaPath().trim());
        if (request.eulaAccepted() != null) config.setEulaAccepted(request.eulaAccepted());
        if (request.customCommand() != null) {
            if (!properties.customCommandEnabled() && !request.customCommand().isBlank()) {
                throw ApiException.badRequest("Eigene Startbefehle sind deaktiviert (simplycraft.custom-command-enabled)");
            }
            if (!request.customCommand().isBlank()) StartCommand.tokenize(request.customCommand());
            config.setCustomCommand(request.customCommand().isBlank() ? null : request.customCommand().trim());
        }
        if (request.loader() != null) {
            String loader = request.loader().trim().toLowerCase(Locale.ROOT);
            if (!loader.isEmpty() && !LOADER.matcher(loader).matches()) throw ApiException.badRequest("Ungültiger Loader");
            config.setLoader(loader.isEmpty() ? null : loader);
        }
        if (request.gameVersion() != null) {
            String version = request.gameVersion().trim();
            if (!version.isEmpty() && !GAME_VERSION.matcher(version).matches()) throw ApiException.badRequest("Ungültige Minecraft-Version");
            config.setGameVersion(version.isEmpty() ? null : version);
        }
        if (request.port() != null) {
            config.setPort(request.port());
            try {
                writeServerPort(id, request.port());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (memoryBytes(config.getXms()) > memoryBytes(config.getXmx())) {
            throw ApiException.badRequest("Xms darf nicht größer als Xmx sein");
        }
        return save(config);
    }

    /** Callers make sure the server is not running */
    public synchronized void delete(String id) throws IOException {
        get(id);
        FileSystemUtils.deleteRecursively(dir(id));
    }

    public synchronized ServerConfig save(ServerConfig config) {
        Path file = dir(config.getId()).resolve(CONFIG_FILE);
        Path temp = file.resolveSibling(CONFIG_FILE + ".tmp");
        try {
            Files.writeString(temp, mapper.writeValueAsString(config), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return config;
    }

    public Path dir(String id) {
        if (id == null || !ID.matcher(id).matches()) throw ApiException.notFound("Server \"" + id + "\" existiert nicht");
        return root.resolve(id);
    }

    public Path jar(String id) {
        return dir(id).resolve(JAR_FILE);
    }

    public Path modsDir(String id) throws IOException {
        return Files.createDirectories(dir(id).resolve("mods"));
    }

    private ServerConfig read(Path file) {
        try {
            return mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), ServerConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeServerPort(String id, int port) throws IOException {
        Path file = dir(id).resolve("server.properties");
        List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.ISO_8859_1)) : new ArrayList<>();
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("server-port=")) {
                lines.set(i, "server-port=" + port);
                replaced = true;
            }
        }
        if (!replaced) lines.add("server-port=" + port);
        Files.write(file, lines, StandardCharsets.ISO_8859_1);
    }

    private String uniqueId(String base) {
        String id = base;
        for (int i = 2; Files.exists(root.resolve(id)); i++) id = base + "-" + i;
        return id;
    }

    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 40) s = s.substring(0, 40).replaceAll("-+$", "");
        return s.isEmpty() ? "server" : s;
    }

    private static String memory(String value, String fallback) {
        if (value == null || value.isBlank()) {
            if (fallback == null) throw ApiException.badRequest("Speicherangabe fehlt");
            return fallback;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (!MEMORY.matcher(v).matches()) throw ApiException.badRequest("Ungültige Speicherangabe \"" + value + "\" (z. B. 2G oder 512M)");
        return v;
    }

    static long memoryBytes(String value) {
        if (value == null) return 0;
        char unit = value.charAt(value.length() - 1);
        String digits = Character.isDigit(unit) ? value : value.substring(0, value.length() - 1);
        long n = Long.parseLong(digits);
        return switch (Character.toUpperCase(unit)) {
            case 'K' -> n << 10;
            case 'M' -> n << 20;
            case 'G' -> n << 30;
            default -> n;
        };
    }
}
