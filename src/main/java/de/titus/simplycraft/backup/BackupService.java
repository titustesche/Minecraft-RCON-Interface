package de.titus.simplycraft.backup;

import de.titus.simplycraft.common.ApiException;
import de.titus.simplycraft.common.SafePaths;
import de.titus.simplycraft.config.SimplycraftProperties;
import de.titus.simplycraft.process.ServerProcessManager;
import de.titus.simplycraft.process.ServerRuntime;
import de.titus.simplycraft.server.ServerService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Archives a whole server directory to backups/&lt;id&gt;/&lt;id&gt;-yyyyMMdd-HHmmss.zip|.tar.gz.
 * A running server is told to flush and pause saving while the archive is written.
 * Backups can be pushed to Simplyfile right away or later.
 */
@Service
public class BackupService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern NAME = Pattern.compile("^[a-z0-9-]+-\\d{8}-\\d{6}\\.(zip|tar\\.gz)$");
    private static final String PART = ".part";
    private static final Set<String> SKIP = Set.of("session.lock", ServerService.JAR_FILE + ".new");

    private final ServerService servers;
    private final ServerProcessManager processes;
    private final SimplyfileClient simplyfile;
    private final ObjectMapper mapper;
    private final Path root;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    public BackupService(ServerService servers, ServerProcessManager processes, SimplyfileClient simplyfile,
                         ObjectMapper mapper, SimplycraftProperties properties) {
        this.servers = servers;
        this.processes = processes;
        this.simplyfile = simplyfile;
        this.mapper = mapper;
        this.root = properties.backupsPath().toAbsolutePath().normalize();
    }

    public List<BackupInfo> list(String id) {
        servers.get(id);
        Path dir = dir(id);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .map(f -> f.getFileName().toString())
                    .filter(n -> NAME.matcher(n).matches() || (n.endsWith(PART) && NAME.matcher(n.substring(0, n.length() - PART.length())).matches()))
                    .filter(n -> !n.endsWith(PART) || running.contains(id) || !discard(dir.resolve(n)))
                    .map(n -> info(id, n))
                    .sorted(Comparator.comparing(BackupInfo::createdAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Leftover of a backup that was interrupted (e.g. by a restart of Simplycraft) */
    private static boolean discard(Path part) {
        try {
            Files.deleteIfExists(part);
        } catch (IOException ignored) {
        }
        return true;
    }

    /** Starts a backup in the background; the list shows it as "inProgress" until it is done. */
    public BackupInfo create(String id, BackupFormat format, boolean uploadToSimplyfile) {
        servers.get(id);
        if (uploadToSimplyfile && !simplyfile.enabled()) {
            throw ApiException.badRequest("Simplyfile ist nicht konfiguriert (simplycraft.simplyfile.url)");
        }
        if (!running.add(id)) throw ApiException.conflict("Für diesen Server läuft bereits ein Backup");

        String name = id + "-" + LocalDateTime.now().format(STAMP) + format.extension();
        Path part;
        try {
            Path dir = Files.createDirectories(dir(id));
            if (Files.exists(dir.resolve(name))) throw ApiException.conflict("Backup existiert bereits, bitte kurz warten");
            part = dir.resolve(name + PART);
            Files.createFile(part);
        } catch (IOException | RuntimeException e) {
            running.remove(id);
            if (e instanceof IOException io) throw new UncheckedIOException(io);
            throw (RuntimeException) e;
        }

        background.submit(() -> {
            try {
                run(id, name, format, part, uploadToSimplyfile);
            } finally {
                running.remove(id);
            }
        });
        return info(id, name + PART);
    }

    public Path file(String id, String name) {
        servers.get(id);
        Path file = SafePaths.resolveInside(dir(id), validName(name));
        if (!Files.isRegularFile(file)) throw ApiException.notFound("Backup \"" + name + "\" nicht gefunden");
        return file;
    }

    public void delete(String id, String name) {
        Path file = file(id, name);
        SimplyfileUpload upload = readUpload(id, name);
        if (upload != null && "UPLOADING".equals(upload.status())) throw ApiException.conflict("Das Backup wird gerade hochgeladen");
        try {
            Files.delete(file);
            Files.deleteIfExists(sidecar(id, name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public BackupInfo upload(String id, String name) {
        Path file = file(id, name);
        if (!simplyfile.enabled()) throw ApiException.badRequest("Simplyfile ist nicht konfiguriert (simplycraft.simplyfile.url)");
        SimplyfileUpload current = readUpload(id, name);
        if (current != null && "UPLOADING".equals(current.status())) throw ApiException.conflict("Das Backup wird bereits hochgeladen");
        writeUpload(id, name, new SimplyfileUpload("UPLOADING", null, null, null, Instant.now()));
        background.submit(() -> uploadNow(id, name, file));
        return info(id, name);
    }

    // --- work ---------------------------------------------------------------------------

    private void run(String id, String name, BackupFormat format, Path part, boolean upload) {
        boolean paused = false;
        ServerRuntime runtime = processes.runtime(id);
        try {
            runtime.system("Backup " + name + " wird erstellt");
            if (processes.isRunning(id)) {
                processes.command(id, "save-off");
                paused = true;
                var saved = runtime.expect(line -> line.contains("Saved the game") || line.contains("saved the game"));
                processes.command(id, "save-all flush");
                if (!saved.await(Duration.ofSeconds(60))) {
                    runtime.system("Keine Speicherbestätigung erhalten, Backup wird trotzdem erstellt");
                }
            }

            Path source = servers.dir(id);
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part))) {
                if (format == BackupFormat.ZIP) zip(source, id, out);
                else tarGz(source, id, out);
            }
            Path target = part.resolveSibling(name);
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            runtime.system("Backup " + name + " fertig (" + Files.size(target) / (1024 * 1024) + " MB)");

            if (upload) {
                writeUpload(id, name, new SimplyfileUpload("UPLOADING", null, null, null, Instant.now()));
                uploadNow(id, name, target);
            }
        } catch (Exception e) {
            log.error("Backup {} failed", name, e);
            runtime.system("Backup fehlgeschlagen: " + e.getMessage());
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
        } finally {
            if (paused && processes.isRunning(id)) {
                try {
                    processes.command(id, "save-on");
                } catch (ApiException ignored) {
                }
            }
        }
    }

    private void uploadNow(String id, String name, Path file) {
        ServerRuntime runtime = processes.runtime(id);
        try {
            var stored = simplyfile.upload(file, name);
            writeUpload(id, name, new SimplyfileUpload("DONE", stored.id(), stored.url(), null, Instant.now()));
            runtime.system("Backup " + name + " in Simplyfile gesichert: " + stored.url());
        } catch (Exception e) {
            log.warn("Upload of {} to Simplyfile failed: {}", name, e.getMessage());
            writeUpload(id, name, new SimplyfileUpload("FAILED", null, null, e.getMessage(), Instant.now()));
            runtime.system("Upload zu Simplyfile fehlgeschlagen: " + e.getMessage());
        }
    }

    private List<Path> files(Path source) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !SKIP.contains(p.getFileName().toString()))
                    .sorted()
                    .toList();
        }
    }

    private void zip(Path source, String prefix, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Path file : files(source)) {
                ZipEntry entry = new ZipEntry(prefix + "/" + entryName(source, file));
                entry.setLastModifiedTime(Files.getLastModifiedTime(file));
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private void tarGz(Path source, String prefix, OutputStream out) throws IOException {
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            for (Path file : files(source)) {
                TarArchiveEntry entry = new TarArchiveEntry(file, prefix + "/" + entryName(source, file));
                long size = entry.getSize();
                tar.putArchiveEntry(entry);
                // The header already holds the size: copy exactly that many bytes, even if the file changes meanwhile
                long written = 0;
                try (InputStream in = Files.newInputStream(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while (written < size && (read = in.read(buffer, 0, (int) Math.min(buffer.length, size - written))) != -1) {
                        tar.write(buffer, 0, read);
                        written += read;
                    }
                }
                byte[] zeros = new byte[8192];
                while (written < size) {
                    int n = (int) Math.min(zeros.length, size - written);
                    tar.write(zeros, 0, n);
                    written += n;
                }
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
    }

    private static String entryName(Path source, Path file) {
        return source.relativize(file).toString().replace('\\', '/');
    }

    // --- metadata -----------------------------------------------------------------------

    private BackupInfo info(String id, String fileName) {
        boolean inProgress = fileName.endsWith(PART);
        String name = inProgress ? fileName.substring(0, fileName.length() - PART.length()) : fileName;
        Path file = dir(id).resolve(fileName);
        try {
            long size = Files.exists(file) ? Files.size(file) : 0;
            Instant created = Files.exists(file) ? Files.getLastModifiedTime(file).toInstant() : Instant.now();
            try {
                created = LocalDateTime.parse(name.substring(id.length() + 1, id.length() + 16), STAMP)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant();
            } catch (RuntimeException ignored) {
            }
            return new BackupInfo(name, BackupFormat.of(name), size, created, inProgress, inProgress ? null : readUpload(id, name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SimplyfileUpload readUpload(String id, String name) {
        Path file = sidecar(id, name);
        if (!Files.isRegularFile(file)) return null;
        try {
            return mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), SimplyfileUpload.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void writeUpload(String id, String name, SimplyfileUpload upload) {
        try {
            Files.writeString(sidecar(id, name), mapper.writeValueAsString(upload), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path sidecar(String id, String name) {
        return dir(id).resolve(name + ".json");
    }

    private Path dir(String id) {
        servers.dir(id);
        return root.resolve(id);
    }

    private static String validName(String name) {
        if (name == null || !NAME.matcher(name).matches()) throw ApiException.badRequest("Ungültiger Backup-Name");
        return name;
    }

    @Override
    public void destroy() {
        background.shutdownNow();
    }
}
