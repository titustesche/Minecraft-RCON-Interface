package de.titus.simplycraft.common;

import java.nio.file.Path;
import java.util.regex.Pattern;

public final class SafePaths {

    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._+\\-]");

    private SafePaths() {
    }

    /** Resolves name inside base and refuses anything that escapes it (../, absolute paths). */
    public static Path resolveInside(Path base, String name) {
        if (name == null || name.isBlank()) throw ApiException.badRequest("Dateiname fehlt");
        Path root = base.toAbsolutePath().normalize();
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw ApiException.badRequest("Ungültiger Dateiname: " + name);
        return target;
    }

    /** Strips directories and replaces every character that is not file-name safe. */
    public static String sanitizeFileName(String original) {
        if (original == null) return null;
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = UNSAFE.matcher(name).replaceAll("_");
        while (name.startsWith(".")) name = name.substring(1);
        return name.isBlank() ? null : name;
    }
}
