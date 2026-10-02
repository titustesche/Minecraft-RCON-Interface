package de.titus.simplycraft.backup;

import de.titus.simplycraft.common.ApiException;
import de.titus.simplycraft.common.SafePaths;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Unpacks backups (.zip, .tar.gz). Our own backups put everything below "&lt;id&gt;/";
 * when every entry shares one top-level folder it is stripped, so archives of a plain
 * server folder work as well. Entries that would land outside the target are refused.
 */
final class ArchiveExtractor {

    private ArchiveExtractor() {
    }

    /** Entry names (files only), also checks that the file is a readable archive */
    static List<String> entries(Path archive, BackupFormat format) {
        List<String> names = new ArrayList<>();
        try (ArchiveInputStream<?> in = open(archive, format)) {
            for (ArchiveEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (!entry.isDirectory() && isRegular(entry)) names.add(normalize(entry.getName()));
            }
        } catch (IOException | RuntimeException e) {
            throw ApiException.badRequest("Das Archiv ist beschädigt oder kein " + format.extension() + "-Archiv");
        }
        Path probe = Path.of("probe");
        for (String name : names) {
            if (name.startsWith("/") || !probe.resolve(name).normalize().startsWith(probe)) {
                throw ApiException.badRequest("Das Archiv enthält einen ungültigen Pfad: " + name);
            }
        }
        return names;
    }

    static void extract(Path archive, BackupFormat format, Path target) throws IOException {
        List<String> names = entries(archive, format);
        if (names.isEmpty()) throw ApiException.badRequest("Das Backup ist leer");
        String prefix = commonFolder(names);

        Files.createDirectories(target);
        try (ArchiveInputStream<?> in = open(archive, format)) {
            for (ArchiveEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (!isRegular(entry)) continue;
                String name = normalize(entry.getName());
                if (prefix != null) name = name.length() > prefix.length() ? name.substring(prefix.length()) : "";
                if (name.isBlank()) continue;

                Path path = SafePaths.resolveInside(target, name);
                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                } else {
                    Files.createDirectories(path.getParent());
                    Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
                    if (entry.getLastModifiedDate() != null) {
                        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(entry.getLastModifiedDate().toInstant()));
                    }
                }
            }
        }
    }

    /** "survival/" when all entries live in that folder, otherwise null */
    static String commonFolder(List<String> names) {
        String first = names.getFirst();
        int slash = first.indexOf('/');
        if (slash <= 0) return null;
        String prefix = first.substring(0, slash + 1);
        return names.stream().allMatch(n -> n.startsWith(prefix)) ? prefix : null;
    }

    private static ArchiveInputStream<?> open(Path archive, BackupFormat format) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(archive));
        try {
            return format == BackupFormat.ZIP
                    ? new ZipArchiveInputStream(in)
                    : new TarArchiveInputStream(new GzipCompressorInputStream(in));
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    /** Skips symlinks, devices and the like from tar archives */
    private static boolean isRegular(ArchiveEntry entry) {
        return !(entry instanceof TarArchiveEntry tar) || tar.isFile() || tar.isDirectory();
    }

    private static String normalize(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        return n;
    }
}
