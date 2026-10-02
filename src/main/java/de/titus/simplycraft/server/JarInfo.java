package de.titus.simplycraft.server;

import java.time.Instant;

/**
 * Where the current server.jar came from.
 *
 * @param source  VANILLA, FABRIC, URL or UPLOAD
 * @param version Minecraft version for VANILLA/FABRIC, otherwise null
 * @param origin  download link or original file name
 */
public record JarInfo(String source, String version, String origin, long size, Instant installedAt) {
}
