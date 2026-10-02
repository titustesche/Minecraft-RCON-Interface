package de.titus.simplycraft.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TestJars {

    private static final long FIXED_TIME = 1_700_000_000_000L;

    private TestJars() {
    }

    /** A minimal but valid jar (zip) file, byte-identical for the same marker */
    public static byte[] jar(String marker) {
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("META-INF/MANIFEST.MF");
            // Fixed timestamp: the same marker must always give the same bytes
            entry.setTime(FIXED_TIME);
            zip.putNextEntry(entry);
            zip.write(("Manifest-Version: 1.0\nX-Marker: " + marker + "\n").getBytes());
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
