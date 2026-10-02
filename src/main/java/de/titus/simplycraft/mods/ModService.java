package de.titus.simplycraft.mods;

import de.titus.simplycraft.common.ApiException;
import de.titus.simplycraft.common.SafePaths;
import de.titus.simplycraft.server.ServerService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mod browser: files in servers/&lt;id&gt;/mods. Disabled mods are renamed to *.jar.disabled,
 * which every common loader ignores. Modrinth metadata is kept in simplycraft-mods.json.
 */
@Service
public class ModService {

    static final String INDEX_FILE = "simplycraft-mods.json";
    private static final String DISABLED = ".disabled";

    private final ServerService servers;
    private final ObjectMapper mapper;

    public ModService(ServerService servers, ObjectMapper mapper) {
        this.servers = servers;
        this.mapper = mapper;
    }

    public List<ModInfo> list(String id) {
        servers.get(id);
        Map<String, ModSource> index = readIndex(id);
        try (Stream<Path> files = Files.list(servers.modsDir(id))) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(f -> isModFile(f.getFileName().toString()))
                    .map(f -> info(f, index))
                    .sorted(Comparator.comparing(m -> m.name().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<ModInfo> upload(String id, List<MultipartFile> files) {
        servers.get(id);
        if (files == null || files.isEmpty()) throw ApiException.badRequest("Keine Dateien ausgewählt");
        List<ModInfo> stored = new ArrayList<>();
        try {
            Path mods = servers.modsDir(id);
            for (MultipartFile file : files) {
                String name = SafePaths.sanitizeFileName(file.getOriginalFilename());
                if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    throw ApiException.badRequest("Nur .jar-Dateien können als Mod hochgeladen werden: " + file.getOriginalFilename());
                }
                Path target = SafePaths.resolveInside(mods, name);
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.deleteIfExists(target.resolveSibling(name + DISABLED));
                forget(id, name);
                stored.add(info(target, Map.of()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return stored;
    }

    public void delete(String id, String fileName) {
        Path file = existing(id, fileName);
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        forget(id, baseName(fileName));
    }

    public ModInfo setEnabled(String id, String fileName, boolean enabled) {
        Path file = existing(id, fileName);
        String base = baseName(file.getFileName().toString());
        Path target = file.resolveSibling(enabled ? base : base + DISABLED);
        try {
            if (!file.equals(target)) Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return info(target, readIndex(id));
    }

    // --- used by the Modrinth integration ----------------------------------------------

    public Path modsDir(String id) {
        try {
            return servers.modsDir(id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void remember(String id, String fileName, ModSource source) {
        Map<String, ModSource> index = readIndex(id);
        index.put(fileName, source);
        writeIndex(id, index);
    }

    /** File names (without .disabled) of installed mods by Modrinth project id */
    public Map<String, String> installedProjects(String id) {
        return list(id).stream()
                .filter(m -> m.modrinth() != null && m.modrinth().projectId() != null)
                .collect(Collectors.toMap(m -> m.modrinth().projectId(), m -> m.fileName(), (a, b) -> a));
    }

    public ModInfo info(String id, String fileName) {
        return info(existing(id, fileName), readIndex(id));
    }

    // --- helpers -----------------------------------------------------------------------

    private Path existing(String id, String fileName) {
        servers.get(id);
        if (fileName == null || !isModFile(fileName)) throw ApiException.badRequest("Ungültiger Mod-Dateiname");
        Path file = SafePaths.resolveInside(modsDir(id), fileName);
        if (!Files.isRegularFile(file)) throw ApiException.notFound("Mod \"" + fileName + "\" nicht gefunden");
        return file;
    }

    private synchronized void forget(String id, String fileName) {
        Map<String, ModSource> index = readIndex(id);
        if (index.remove(fileName) != null) writeIndex(id, index);
    }

    private ModInfo info(Path file, Map<String, ModSource> index) {
        String fileName = file.getFileName().toString();
        String base = baseName(fileName);
        try {
            return new ModInfo(
                    fileName,
                    base.substring(0, base.length() - ".jar".length()),
                    !fileName.endsWith(DISABLED),
                    Files.size(file),
                    Files.getLastModifiedTime(file).toInstant(),
                    index.get(base)
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, ModSource> readIndex(String id) {
        Path file = servers.dir(id).resolve(INDEX_FILE);
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            var type = mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, ModSource.class);
            return mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    private void writeIndex(String id, Map<String, ModSource> index) {
        Set<String> present = list(id).stream().map(m -> baseName(m.fileName())).collect(Collectors.toSet());
        index.keySet().retainAll(present);
        try {
            Files.writeString(servers.dir(id).resolve(INDEX_FILE), mapper.writeValueAsString(index), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isModFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".jar" + DISABLED);
    }

    private static String baseName(String fileName) {
        return fileName.endsWith(DISABLED) ? fileName.substring(0, fileName.length() - DISABLED.length()) : fileName;
    }
}
